"""Gemini provider integration for the NEX companion API.

The Android app never talks to Gemini and never holds a key: it calls this
service over HTTPS, and this module is the only place the key is used. The key
is sent to Google as the ``x-goog-api-key`` header (never in a URL, never
logged, never returned to a client).

Two providers exist:

``GeminiHttpProvider``
    The real integration: ``POST {base}/models/{model}:generateContent`` with an
    optional ``google_search`` tool for server-side grounding.

``StubProvider``
    A deterministic, clearly labelled local mode (``AI_PROVIDER=stub``) used for
    development and demos. It never pretends to be a real model and never
    claims grounding or live data.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple

import httpx

from .config import DEFAULT_LANGUAGE, Settings
from .grounding_service import extract_sources, is_grounded
from .models import SourceRef
from .safety import build_system_instruction, redact_for_log, scrub_secrets

logger = logging.getLogger("nex.provider")

GENERATE_CONTENT_PATH = "/models/{model}:generateContent"


# ---------------------------------------------------------------------------
# Errors — every one carries a *safe* message suitable for an end user.
# ---------------------------------------------------------------------------

class ProviderError(Exception):
    """Base class for provider failures."""

    code = "provider_error"
    status_hint = 502

    def __init__(self, message: str, *, code: Optional[str] = None,
                 status_hint: Optional[int] = None, detail: str = "") -> None:
        super().__init__(message)
        self.message = message
        if code:
            self.code = code
        if status_hint:
            self.status_hint = status_hint
        self.detail = detail  # server-side only, never returned to clients


class ProviderNotConfigured(ProviderError):
    code = "provider_not_configured"
    status_hint = 503

    def __init__(self, message: str = "The NEX server has no AI key configured yet, "
                                      "so I cannot answer from the cloud right now.") -> None:
        super().__init__(message)


class ProviderTimeout(ProviderError):
    code = "provider_timeout"
    status_hint = 504

    def __init__(self, message: str = "My cloud brain took too long to answer. Please try again in a moment.") -> None:
        super().__init__(message)


class ProviderBlocked(ProviderError):
    code = "blocked_by_safety"
    status_hint = 422

    def __init__(self, message: str = "I cannot answer that one. Let us talk about something else.") -> None:
        super().__init__(message)


class ProviderRejected(ProviderError):
    code = "provider_rejected"
    status_hint = 502


# ---------------------------------------------------------------------------
# Value objects
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ProviderRequest:
    """A bounded, already-sanitised conversation turn."""

    text: str
    language: str = DEFAULT_LANGUAGE
    needs_current_info: bool = False
    system_instruction: str = ""
    max_output_tokens: int = 320
    request_id: str = ""


@dataclass(frozen=True)
class VisionProviderRequest:
    """A single explicitly requested vision frame."""

    image_base64: str
    mime_type: str
    prompt: str
    language: str = DEFAULT_LANGUAGE
    needs_current_info: bool = False
    system_instruction: str = ""
    max_output_tokens: int = 320
    request_id: str = ""


@dataclass(frozen=True)
class ProviderReply:
    """What a provider returned, already reduced to safe values."""

    text: str
    grounded: bool = False
    sources: Tuple[SourceRef, ...] = ()
    model_version: Optional[str] = None
    finish_reason: Optional[str] = None
    usage: Mapping[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Parsing helpers (pure, unit-testable)
# ---------------------------------------------------------------------------

def _as_mapping(value: Any) -> Optional[Mapping[str, Any]]:
    return value if isinstance(value, Mapping) else None


def parse_generate_content(payload: Any, *, source_limit: int = 5) -> ProviderReply:
    """Convert a ``generateContent`` response body into a :class:`ProviderReply`."""
    body = _as_mapping(payload)
    if body is None:
        raise ProviderRejected("The AI service sent an unreadable answer.", detail="response body was not an object")

    prompt_feedback = _as_mapping(body.get("promptFeedback"))
    if prompt_feedback and prompt_feedback.get("blockReason"):
        raise ProviderBlocked()

    candidates = body.get("candidates")
    if not isinstance(candidates, Sequence) or not candidates:
        raise ProviderRejected("The AI service returned no answer for that.", detail="no candidates in response")

    first = _as_mapping(candidates[0])
    if first is None:
        raise ProviderRejected("The AI service returned a malformed answer.", detail="candidate was not an object")

    finish_reason = first.get("finishReason")
    if isinstance(finish_reason, str) and finish_reason.upper() in {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST"}:
        raise ProviderBlocked()

    content = _as_mapping(first.get("content")) or {}
    parts = content.get("parts")
    chunks: List[str] = []
    if isinstance(parts, Sequence):
        for part in parts:
            mapping = _as_mapping(part)
            if mapping and isinstance(mapping.get("text"), str):
                chunks.append(mapping["text"])
    text = "".join(chunks).strip()

    if not text:
        raise ProviderRejected("The AI service answered with empty text.", detail="empty candidate text")

    grounding_metadata = first.get("groundingMetadata")
    grounded = is_grounded(grounding_metadata)
    sources = extract_sources(grounding_metadata, source_limit) if grounded else []

    usage = _as_mapping(body.get("usageMetadata")) or {}
    model_version = body.get("modelVersion")

    return ProviderReply(
        text=text,
        grounded=grounded,
        sources=tuple(sources),
        model_version=model_version if isinstance(model_version, str) else None,
        finish_reason=finish_reason if isinstance(finish_reason, str) else None,
        usage={key: usage[key] for key in ("promptTokenCount", "candidatesTokenCount", "totalTokenCount") if key in usage},
    )


def build_generation_config(max_output_tokens: int) -> Dict[str, Any]:
    return {
        "temperature": 0.75,
        "topP": 0.92,
        "maxOutputTokens": max(32, int(max_output_tokens)),
        "responseMimeType": "text/plain",
    }


def build_converse_payload(request: ProviderRequest, *, grounding: bool) -> Dict[str, Any]:
    """Build the JSON body for a text conversation turn."""
    payload: Dict[str, Any] = {
        "contents": [{"role": "user", "parts": [{"text": request.text}]}],
        "generationConfig": build_generation_config(request.max_output_tokens),
        "safetySettings": [
            {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
            {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
            {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
            {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
        ],
    }
    instruction = request.system_instruction or build_system_instruction(
        language=request.language, needs_current_info=request.needs_current_info
    )
    payload["systemInstruction"] = {"role": "system", "parts": [{"text": instruction}]}
    if grounding:
        payload["tools"] = [{"google_search": {}}]
    return payload


def build_vision_payload(request: VisionProviderRequest, *, grounding: bool) -> Dict[str, Any]:
    """Build the JSON body for a single explicitly requested image turn."""
    payload = build_converse_payload(
        ProviderRequest(
            text=request.prompt,
            language=request.language,
            needs_current_info=request.needs_current_info,
            system_instruction=request.system_instruction,
            max_output_tokens=request.max_output_tokens,
            request_id=request.request_id,
        ),
        grounding=grounding,
    )
    payload["contents"] = [
        {
            "role": "user",
            "parts": [
                {"text": request.prompt},
                {"inline_data": {"mime_type": request.mime_type, "data": request.image_base64}},
            ],
        }
    ]
    return payload


# ---------------------------------------------------------------------------
# Providers
# ---------------------------------------------------------------------------

class GeminiHttpProvider:
    """Real Gemini REST integration. The only component that sees the API key."""

    name = "gemini"

    def __init__(self, settings: Settings, *, transport: Optional[httpx.AsyncBaseTransport] = None) -> None:
        self._settings = settings
        self._transport = transport
        self._client: Optional[httpx.AsyncClient] = None

    # -- lifecycle ------------------------------------------------------
    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            timeout = httpx.Timeout(
                self._settings.provider_timeout_seconds,
                connect=min(5.0, self._settings.provider_timeout_seconds),
            )
            self._client = httpx.AsyncClient(timeout=timeout, transport=self._transport)
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()

    # -- internals ------------------------------------------------------
    def _api_key(self) -> str:
        secret = self._settings.gemini_api_key
        key = secret.get_secret_value().strip() if secret else ""
        if not key:
            raise ProviderNotConfigured()
        return key

    async def _generate(self, payload: Mapping[str, Any], *, request_id: str) -> ProviderReply:
        key = self._api_key()
        url = self._settings.gemini_base_url.rstrip("/") + GENERATE_CONTENT_PATH.format(
            model=self._settings.gemini_model
        )
        headers = {
            "x-goog-api-key": key,
            "content-type": "application/json",
            "accept": "application/json",
        }
        client = self._get_client()
        try:
            response = await client.post(url, json=dict(payload), headers=headers)
            response.raise_for_status()
        except httpx.TimeoutException as exc:
            logger.warning("provider timeout request_id=%s", request_id)
            raise ProviderTimeout() from exc
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            logger.warning("provider http error request_id=%s status=%s body=%s",
                           request_id, status, redact_for_log(exc.response.text, 200))
            if status in (401, 403):
                raise ProviderNotConfigured(
                    "The NEX server key was rejected by the AI service, so I cannot answer from the cloud."
                ) from exc
            if status == 429:
                raise ProviderRejected(
                    "The AI service is busy right now. Please try again in a little while.",
                    code="provider_rate_limited", status_hint=503,
                ) from exc
            raise ProviderRejected(
                "The AI service could not process that request.",
                status_hint=502 if status >= 500 else 422,
                detail=f"upstream status {status}",
            ) from exc
        except httpx.HTTPError as exc:
            logger.warning("provider transport error request_id=%s type=%s", request_id, type(exc).__name__)
            raise ProviderRejected(
                "I could not reach the AI service. Please check the connection and try again.",
                code="provider_unreachable", status_hint=503,
            ) from exc

        try:
            body = response.json()
        except ValueError as exc:
            raise ProviderRejected(
                "The AI service sent an unreadable answer.", detail="invalid json"
            ) from exc

        reply = parse_generate_content(body, source_limit=self._settings.max_grounding_sources)
        reply = ProviderReply(
            text=scrub_secrets(reply.text, [key]),
            grounded=reply.grounded,
            sources=reply.sources,
            model_version=reply.model_version,
            finish_reason=reply.finish_reason,
            usage=reply.usage,
        )
        logger.info(
            "provider ok request_id=%s grounded=%s sources=%s usage=%s model_version=%s",
            request_id, reply.grounded, len(reply.sources), dict(reply.usage), reply.model_version,
        )
        return reply

    # -- public API -----------------------------------------------------
    async def converse(self, request: ProviderRequest) -> ProviderReply:
        grounding = bool(request.needs_current_info and self._settings.grounding_enabled)
        payload = build_converse_payload(request, grounding=grounding)
        return await self._generate(payload, request_id=request.request_id)

    async def describe_image(self, request: VisionProviderRequest) -> ProviderReply:
        grounding = bool(request.needs_current_info and self._settings.grounding_enabled)
        payload = build_vision_payload(request, grounding=grounding)
        return await self._generate(payload, request_id=request.request_id)


class StubProvider:
    """Deterministic offline development provider (``AI_PROVIDER=stub``).

    It is explicitly labelled in every reply and never claims grounding, live
    data or real model behaviour. It exists so the Android app and the API can
    be exercised end to end before a key is provisioned.
    """

    name = "stub"

    def __init__(self, *, latency_seconds: float = 0.0) -> None:
        self._latency = max(0.0, latency_seconds)
        self.calls: List[Mapping[str, Any]] = []

    async def converse(self, request: ProviderRequest) -> ProviderReply:
        if self._latency:
            await asyncio.sleep(self._latency)
        self.calls.append({"kind": "converse", "text": request.text, "language": request.language,
                           "needs_current_info": request.needs_current_info})
        prefix = "Namaste" if request.language == "hi" else "Hello"
        text = (
            f"{prefix}. I am the NEX stub provider, so this is a fixed local answer, not a real AI reply. "
            f"You said: {request.text[:120]}"
        )
        if request.needs_current_info:
            text += " I cannot check live information in stub mode."
        return ProviderReply(text=text, grounded=False, sources=(), model_version="stub-1")

    async def describe_image(self, request: VisionProviderRequest) -> ProviderReply:
        if self._latency:
            await asyncio.sleep(self._latency)
        self.calls.append({"kind": "vision", "prompt": request.prompt, "bytes_b64": len(request.image_base64)})
        return ProviderReply(
            text="I received your picture, but this is the NEX stub provider, so I cannot really describe it.",
            grounded=False, sources=(), model_version="stub-1",
        )

    async def aclose(self) -> None:  # parity with GeminiHttpProvider
        return None


def build_default_provider(settings: Settings) -> Any:
    """Create the provider selected by ``AI_PROVIDER``."""
    provider = (settings.ai_provider or "").lower()
    if provider == "gemini":
        return GeminiHttpProvider(settings)
    if provider == "stub":
        return StubProvider()
    raise RuntimeError(f"Unsupported AI_PROVIDER value: {settings.ai_provider!r}")
