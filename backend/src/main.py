"""NEX companion API — FastAPI application.

Endpoints
    GET  /health        Safe readiness probe (no secrets, no internals).
    GET  /              Small service identity document.
    POST /v1/converse   Bounded text turn, optional server-side search grounding.
    POST /v1/vision     One explicitly requested image frame (never stored).

Security posture
    * The Gemini key lives only in this process, is sent only to Google as a
      header, and is scrubbed from every outbound string.
    * Error responses carry a stable ``code`` and a user-safe ``message``.
      Tracebacks, environment values and upstream bodies are logged server-side
      only, with redaction.
    * CORS is disabled unless ALLOWED_ORIGINS names explicit https origins.
    * Request bodies are size bounded before parsing.
"""

from __future__ import annotations

import base64
import binascii
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from typing import Any, List, Optional

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import Settings, get_settings
from .gemini_service import (
    ProviderError,
    ProviderRequest,
    VisionProviderRequest,
    build_default_provider,
)
from .grounding_service import enforce_grounding_honesty
from .models import (
    ConverseRequest,
    ConverseResponse,
    ErrorDetail,
    ErrorResponse,
    HealthResponse,
    VisionRequest,
    VisionResponse,
)
from .safety import (
    build_system_instruction,
    looks_like_injection,
    normalize_language,
    normalize_text,
    redact_for_log,
    sanitize_reply,
    scrub_secrets,
)

logger = logging.getLogger("nex.api")

MAX_BODY_BYTES = 3_000_000
REQUEST_ID_PATTERN = re.compile(r"[^A-Za-z0-9._-]")
FALLBACK_REPLY = "I could not form an answer for that. Could you say it once more?"

STATUS_CODES = {
    400: "bad_request",
    404: "not_found",
    405: "method_not_allowed",
    413: "payload_too_large",
    415: "unsupported_media_type",
    422: "invalid_request",
    429: "too_many_requests",
    500: "internal_error",
}


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------

class ApiError(Exception):
    """An error that is safe to expose to a client."""

    def __init__(self, status_code: int, code: str, message: str,
                 details: Optional[List[dict]] = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details


# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

class SecretRedactionFilter(logging.Filter):
    """Belt-and-braces filter so a credential can never reach the log stream."""

    def __init__(self, secrets: Optional[List[str]] = None) -> None:
        super().__init__()
        self.secrets: List[str] = [s for s in (secrets or []) if s and len(s) >= 8]

    def set_secrets(self, secrets: List[str]) -> None:
        self.secrets = [s for s in secrets if s and len(s) >= 8]

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:  # pragma: no cover - defensive
            return True
        redacted = scrub_secrets(message, self.secrets)
        if redacted != message:
            record.msg = redacted
            record.args = ()
        return True


_redaction_filter = SecretRedactionFilter()


def configure_logging(settings: Settings) -> None:
    """Configure the ``nex`` logger: structured-ish, secret-free, no bodies."""
    root = logging.getLogger("nex")
    root.handlers.clear()
    handler = logging.StreamHandler()
    handler.setFormatter(
        logging.Formatter("%(asctime)s level=%(levelname)s logger=%(name)s %(message)s")
    )
    root.addHandler(handler)
    root.setLevel((settings.log_level or "INFO").upper())
    root.propagate = False
    secrets = []
    if settings.gemini_api_key is not None:
        secrets.append(settings.gemini_api_key.get_secret_value())
    _redaction_filter.set_secrets(secrets)
    root.addFilter(_redaction_filter)


# ---------------------------------------------------------------------------
# Response helpers
# ---------------------------------------------------------------------------

def _request_id_of(request: Optional[Request]) -> str:
    if request is None:
        return ""
    return str(getattr(request.state, "request_id", "") or "")


def _error_response(status_code: int, code: str, message: str, request_id: str,
                    details: Optional[List[dict]] = None) -> JSONResponse:
    payload = ErrorResponse(
        error=ErrorDetail(code=code, message=message, request_id=request_id or None, details=details)
    ).model_dump(exclude_none=True)
    return JSONResponse(status_code=status_code, content=payload)


def _validation_details(exc: RequestValidationError) -> List[dict]:
    """Field-level validation info. Never includes the submitted value."""
    details: List[dict] = []
    for raw in exc.errors():
        loc = raw.get("loc") or ()
        field = ".".join(str(part) for part in loc if part not in ("body",))
        details.append(
            {
                "field": field or "body",
                "issue": str(raw.get("type", "invalid")),
            }
        )
    return details[:12]


# ---------------------------------------------------------------------------
# Turn helpers
# ---------------------------------------------------------------------------

def _bounded_text(payload_text: str, settings: Settings) -> str:
    text = payload_text.strip()
    if not text:
        raise ApiError(422, "invalid_request", "I did not receive any words to answer.")
    limit = settings.effective_input_limit
    if len(text) > limit:
        raise ApiError(
            422,
            "request_too_large",
            f"That is too long for one turn. Please keep it under {limit} characters.",
        )
    return normalize_text(text, limit)


def _final_reply(raw_reply: str, *, grounded: bool, settings: Settings) -> str:
    """Sanitise, scrub and honesty-check a provider reply."""
    secret = settings.gemini_api_key.get_secret_value() if settings.gemini_api_key else ""
    text = sanitize_reply(raw_reply, settings.max_reply_chars)
    text = scrub_secrets(text, [secret] if secret else [])
    text, _ = enforce_grounding_honesty(text, grounded)
    return text or FALLBACK_REPLY


def _call_provider(exc: ProviderError) -> ApiError:
    return ApiError(exc.status_hint, exc.code, exc.message)


# ---------------------------------------------------------------------------
# Application factory
# ---------------------------------------------------------------------------

def create_app(settings: Optional[Settings] = None, provider: Optional[Any] = None) -> FastAPI:
    """Build the FastAPI app. Injectable settings/provider keep tests hermetic."""
    settings = settings if settings is not None else get_settings()
    provider = provider if provider is not None else build_default_provider(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        logger.info("startup %s", app.state.settings.public_snapshot())
        try:
            yield
        finally:
            close = getattr(app.state.provider, "aclose", None)
            if close is not None:
                try:
                    await close()
                except Exception:  # pragma: no cover - defensive
                    logger.warning("provider close failed", exc_info=True)
            logger.info("shutdown complete")

    app = FastAPI(
        title="NEX Companion API",
        version=settings.app_version,
        summary="Backend for NEX — Ultimate AI Pet & Intelligent Companion.",
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.provider = provider

    configure_logging(settings)

    origins = settings.cors_origins
    if origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "OPTIONS"],
            allow_headers=["content-type", "x-request-id"],
            max_age=600,
        )

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        raw_id = request.headers.get("x-request-id", "")
        request_id = REQUEST_ID_PATTERN.sub("", raw_id)[:48] or uuid.uuid4().hex[:16]
        request.state.request_id = request_id

        content_length = request.headers.get("content-length")
        if content_length and content_length.isdigit() and int(content_length) > MAX_BODY_BYTES:
            logger.warning("body too large rid=%s bytes=%s", request_id, content_length)
            response = _error_response(
                413, "payload_too_large", "That message is far too large for NEX.", request_id
            )
        else:
            started = time.perf_counter()
            try:
                response = await call_next(request)
            except Exception:
                logger.exception("unhandled error rid=%s path=%s", request_id, request.url.path)
                response = _error_response(
                    500, "internal_error", "Something went wrong inside NEX's server.", request_id
                )
            elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
            logger.info(
                "http method=%s path=%s status=%s ms=%s rid=%s",
                request.method, request.url.path, response.status_code, elapsed_ms, request_id,
            )

        response.headers["x-request-id"] = request_id
        response.headers["x-content-type-options"] = "nosniff"
        response.headers["referrer-policy"] = "no-referrer"
        response.headers["cache-control"] = "no-store"
        return response

    @app.exception_handler(ApiError)
    async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
        rid = _request_id_of(request)
        logger.warning("api error rid=%s status=%s code=%s", rid, exc.status_code, exc.code)
        return _error_response(exc.status_code, exc.code, exc.message, rid, exc.details)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        rid = _request_id_of(request)
        details = _validation_details(exc)
        logger.warning("validation error rid=%s details=%s", rid, details)
        return _error_response(
            422, "invalid_request", "NEX could not read that request.", rid, details
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        rid = _request_id_of(request)
        code = STATUS_CODES.get(exc.status_code, "http_error")
        message = exc.detail if isinstance(exc.detail, str) and exc.status_code < 500 else code.replace("_", " ")
        return _error_response(exc.status_code, code, message.capitalize() + ".", rid)

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        rid = _request_id_of(request)
        logger.exception("unexpected error rid=%s type=%s", rid, type(exc).__name__)
        return _error_response(500, "internal_error", "Something went wrong inside NEX's server.", rid)

    # ------------------------------------------------------------------
    # Routes
    # ------------------------------------------------------------------

    @app.get("/", include_in_schema=False)
    async def root() -> dict:
        return {
            "service": settings.app_name,
            "version": settings.app_version,
            "status": "ok",
            "endpoints": ["/health", "/v1/converse", "/v1/vision"],
        }

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        """Readiness probe. ``ready`` is False when no key is configured."""
        return HealthResponse(
            status="ok",
            provider=settings.ai_provider,
            model=settings.gemini_model,
            ready=settings.is_ready,
            grounding_enabled=settings.grounding_enabled,
            version=settings.app_version,
        )

    @app.post("/v1/converse", response_model=ConverseResponse)
    async def converse(payload: ConverseRequest, request: Request) -> ConverseResponse:
        rid = _request_id_of(request)
        safe_text = _bounded_text(payload.text, settings)
        language = normalize_language(payload.language)

        if not settings.is_ready:
            raise ApiError(
                503,
                "provider_not_configured",
                "My cloud brain is not configured yet, so I can only answer offline.",
            )

        needs_current = bool(payload.needs_current_info) and settings.grounding_enabled
        injection = looks_like_injection(safe_text)
        system = build_system_instruction(
            language=language, needs_current_info=needs_current, injection_suspected=injection
        )
        logger.info(
            "converse rid=%s chars=%s lang=%s needs_current=%s injection=%s snippet=%s",
            rid, len(safe_text), language, needs_current, injection, redact_for_log(safe_text, 60),
        )

        try:
            reply = await provider.converse(
                ProviderRequest(
                    text=safe_text,
                    language=language,
                    needs_current_info=needs_current,
                    system_instruction=system,
                    max_output_tokens=settings.max_reply_tokens,
                    request_id=rid,
                )
            )
        except ProviderError as exc:
            raise _call_provider(exc) from exc

        sources = list(reply.sources) if reply.grounded else []
        return ConverseResponse(
            reply=_final_reply(reply.text, grounded=reply.grounded, settings=settings),
            language=language,
            grounded=bool(reply.grounded),
            sources=sources,
            error=None,
        )

    @app.post("/v1/vision", response_model=VisionResponse)
    async def vision(payload: VisionRequest, request: Request) -> VisionResponse:
        rid = _request_id_of(request)

        try:
            raw = base64.b64decode(payload.image_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ApiError(422, "invalid_image", "That picture did not arrive correctly.") from exc
        if not raw:
            raise ApiError(422, "invalid_image", "That picture was empty.")
        if len(raw) > settings.max_image_bytes:
            raise ApiError(
                422,
                "image_too_large",
                f"That picture is too big. Please keep it under {settings.max_image_bytes // 1000} KB.",
            )

        prompt = normalize_text(payload.prompt, 600) or "What is this?"
        language = normalize_language(payload.language)

        if not settings.is_ready:
            raise ApiError(
                503,
                "provider_not_configured",
                "My cloud brain is not configured yet, so I cannot look at pictures.",
            )

        needs_current = bool(payload.needs_current_info) and settings.grounding_enabled
        system = build_system_instruction(
            language=language, needs_current_info=needs_current, injection_suspected=False
        ) + "\nDescribe only what is clearly visible in the image. If you are not sure, say so."
        logger.info("vision rid=%s bytes=%s mime=%s lang=%s", rid, len(raw), payload.mime_type, language)

        try:
            reply = await provider.describe_image(
                VisionProviderRequest(
                    image_base64=payload.image_base64,
                    mime_type=payload.mime_type,
                    prompt=prompt,
                    language=language,
                    needs_current_info=needs_current,
                    system_instruction=system,
                    max_output_tokens=settings.max_reply_tokens,
                    request_id=rid,
                )
            )
        except ProviderError as exc:
            raise _call_provider(exc) from exc

        sources = list(reply.sources) if reply.grounded else []
        return VisionResponse(
            reply=_final_reply(reply.text, grounded=reply.grounded, settings=settings),
            language=language,
            grounded=bool(reply.grounded),
            sources=sources,
            image_stored=False,
            error=None,
        )

    return app


#: The ASGI application served by ``uvicorn src.main:app``.
app = create_app()
