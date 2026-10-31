"""Tests for the real Gemini HTTP provider (no network, no real key).

The HTTP layer is exercised with ``httpx.MockTransport`` so we can assert the
exact wire behaviour: the key travels only in a header, the search tool is only
attached when grounding is really requested, and grounding metadata is the only
thing that can produce sources.
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from src.config import Settings
from src.gemini_service import (
    GeminiHttpProvider,
    ProviderBlocked,
    ProviderNotConfigured,
    ProviderRejected,
    ProviderRequest,
    ProviderTimeout,
    StubProvider,
    VisionProviderRequest,
    build_converse_payload,
    build_default_provider,
    parse_generate_content,
)

FAKE_KEY = "AIzaSyFAKE-KEY-FOR-TESTS-ONLY-0123456789"

GROUNDING_METADATA = {
    "searchEntryPoint": {"renderedContent": "entry"},
    "groundingChunks": [
        {"web": {"uri": "https://example.org/a", "title": "Alpha"}},
        {"web": {"uri": "https://example.org/b", "title": "Beta"}},
    ],
}


def gemini_body(text="Hello from Gemini.", *, grounding=None, finish="STOP", model="gemini-2.5-flash"):
    candidate = {
        "content": {"role": "model", "parts": [{"text": text}]},
        "finishReason": finish,
        "index": 0,
    }
    if grounding is not None:
        candidate["groundingMetadata"] = grounding
    return {
        "candidates": [candidate],
        "modelVersion": model,
        "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 6, "totalTokenCount": 18},
    }


def make_provider(handler, **overrides):
    settings = Settings(gemini_api_key=FAKE_KEY, **overrides)
    provider = GeminiHttpProvider(settings, transport=httpx.MockTransport(handler))
    return provider, settings


def run(coro):
    return asyncio.run(coro)


def request(text="hello", **kwargs):
    return ProviderRequest(text=text, language="en", max_output_tokens=320, request_id="rid-1", **kwargs)


# ---------------------------------------------------------------------------
# Pure payload construction / parsing
# ---------------------------------------------------------------------------

class TestPayloadAndParsing:
    def test_search_tool_attached_only_when_grounding_requested(self):
        with_tool = build_converse_payload(request(needs_current_info=True), grounding=True)
        without_tool = build_converse_payload(request(needs_current_info=False), grounding=False)

        assert with_tool["tools"] == [{"google_search": {}}]
        assert "tools" not in without_tool

    def test_payload_carries_system_instruction_and_token_budget(self):
        payload = build_converse_payload(
            request(text="hi", system_instruction="Be kind."), grounding=False
        )

        assert payload["systemInstruction"]["parts"][0]["text"] == "Be kind."
        assert payload["generationConfig"]["maxOutputTokens"] == 320
        assert payload["contents"][0]["parts"][0]["text"] == "hi"
        assert len(payload["safetySettings"]) == 4

    def test_vision_payload_includes_inline_image(self):
        from src.gemini_service import build_vision_payload

        payload = build_vision_payload(
            VisionProviderRequest(
                image_base64="QUJDRA==",
                mime_type="image/jpeg",
                prompt="What is this?",
                system_instruction="Describe it.",
            ),
            grounding=False,
        )
        parts = payload["contents"][0]["parts"]

        assert parts[0]["text"] == "What is this?"
        assert parts[1]["inline_data"] == {"mime_type": "image/jpeg", "data": "QUJDRA=="}

    def test_parse_extracts_text_model_and_usage(self):
        reply = parse_generate_content(gemini_body("Hi there."), source_limit=5)

        assert reply.text == "Hi there."
        assert reply.model_version == "gemini-2.5-flash"
        assert reply.finish_reason == "STOP"
        assert reply.grounded is False
        assert reply.sources == ()
        assert reply.usage["totalTokenCount"] == 18

    def test_parse_joins_multiple_parts(self):
        body = gemini_body("")
        body["candidates"][0]["content"]["parts"] = [{"text": "Part one. "}, {"text": "Part two."}]

        assert parse_generate_content(body).text == "Part one. Part two."

    def test_parse_reports_grounding_only_from_metadata(self):
        grounded = parse_generate_content(gemini_body("Rain today.", grounding=GROUNDING_METADATA))
        plain = parse_generate_content(gemini_body("Rain today."))

        assert grounded.grounded is True
        assert [source.url for source in grounded.sources] == [
            "https://example.org/a",
            "https://example.org/b",
        ]
        assert plain.grounded is False
        assert plain.sources == ()

    def test_parse_respects_source_limit(self):
        reply = parse_generate_content(gemini_body("x", grounding=GROUNDING_METADATA), source_limit=1)

        assert len(reply.sources) == 1

    @pytest.mark.parametrize(
        "body",
        [
            {},
            {"candidates": []},
            {"candidates": [{}]},
            {"candidates": [{"content": {"parts": []}}]},
            {"candidates": [{"content": {"parts": [{"text": "   "}]}}]},
            "not-an-object",
        ],
    )
    def test_parse_rejects_empty_or_malformed_responses(self, body):
        with pytest.raises(ProviderRejected):
            parse_generate_content(body)

    def test_parse_reports_prompt_level_block(self):
        with pytest.raises(ProviderBlocked):
            parse_generate_content({"promptFeedback": {"blockReason": "SAFETY"}, "candidates": []})

    @pytest.mark.parametrize("finish", ["SAFETY", "PROHIBITED_CONTENT"])
    def test_parse_reports_candidate_level_block(self, finish):
        with pytest.raises(ProviderBlocked):
            parse_generate_content(gemini_body("nope", finish=finish))


# ---------------------------------------------------------------------------
# HTTP behaviour
# ---------------------------------------------------------------------------

class TestHttpBehaviour:
    def test_key_is_sent_as_header_and_never_in_the_url(self):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen["url"] = str(request.url)
            seen["header"] = request.headers.get("x-goog-api-key")
            return httpx.Response(200, json=gemini_body("ok"))

        provider, _ = make_provider(handler)
        run(provider.converse(request()))

        assert seen["header"] == FAKE_KEY
        assert FAKE_KEY not in seen["url"]
        assert "key=" not in seen["url"]
        assert seen["url"].startswith("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")

    def test_missing_key_raises_before_any_http_call(self):
        called = {"hit": False}

        def handler(request: httpx.Request) -> httpx.Response:
            called["hit"] = True
            return httpx.Response(200, json=gemini_body("ok"))

        settings = Settings()
        provider = GeminiHttpProvider(settings, transport=httpx.MockTransport(handler))

        with pytest.raises(ProviderNotConfigured):
            run(provider.converse(request()))
        assert called["hit"] is False

    def test_grounding_flag_is_forwarded_to_the_wire(self):
        bodies = []

        def handler(request: httpx.Request) -> httpx.Response:
            bodies.append(json.loads(request.content))
            return httpx.Response(200, json=gemini_body("ok", grounding=GROUNDING_METADATA))

        provider, _ = make_provider(handler)
        run(provider.converse(request(needs_current_info=True)))
        run(provider.converse(request(needs_current_info=False)))

        assert "tools" in bodies[0]
        assert "tools" not in bodies[1]

    def test_grounding_disabled_in_settings_blocks_the_tool(self):
        bodies = []

        def handler(request: httpx.Request) -> httpx.Response:
            bodies.append(json.loads(request.content))
            return httpx.Response(200, json=gemini_body("ok"))

        provider, _ = make_provider(handler, grounding_enabled=False)
        run(provider.converse(request(needs_current_info=True)))

        assert "tools" not in bodies[0]

    def test_sources_only_returned_when_upstream_grounded(self):
        def handler(request: httpx.Request) -> httpx.Response:
            payload = json.loads(request.content)
            grounded = "tools" in payload
            return httpx.Response(
                200, json=gemini_body("answer", grounding=GROUNDING_METADATA if grounded else None)
            )

        provider, _ = make_provider(handler)
        grounded_reply = run(provider.converse(request(needs_current_info=True)))
        plain_reply = run(provider.converse(request(needs_current_info=False)))

        assert grounded_reply.grounded is True and len(grounded_reply.sources) == 2
        assert plain_reply.grounded is False and plain_reply.sources == ()

    def test_timeout_maps_to_provider_timeout(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("timed out", request=request)

        provider, _ = make_provider(handler)

        with pytest.raises(ProviderTimeout):
            run(provider.converse(request()))

    @pytest.mark.parametrize(
        "status,expected_type,expected_code",
        [
            (401, ProviderNotConfigured, "provider_not_configured"),
            (403, ProviderNotConfigured, "provider_not_configured"),
            (429, ProviderRejected, "provider_rate_limited"),
            (400, ProviderRejected, "provider_rejected"),
            (503, ProviderRejected, "provider_rejected"),
        ],
    )
    def test_upstream_status_mapping(self, status, expected_type, expected_code):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(status, json={"error": {"message": "upstream detail"}})

        provider, _ = make_provider(handler)

        with pytest.raises(expected_type) as excinfo:
            run(provider.converse(request()))
        assert excinfo.value.code == expected_code
        assert "upstream detail" not in excinfo.value.message

    def test_transport_error_maps_to_unreachable(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("dns failure", request=request)

        provider, _ = make_provider(handler)

        with pytest.raises(ProviderRejected) as excinfo:
            run(provider.converse(request()))
        assert excinfo.value.code == "provider_unreachable"

    def test_invalid_json_maps_to_rejected(self):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, text="<html>not json</html>")

        provider, _ = make_provider(handler)

        with pytest.raises(ProviderRejected):
            run(provider.converse(request()))

    def test_key_is_scrubbed_from_model_output(self):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=gemini_body(f"My key is {FAKE_KEY} okay"))

        provider, _ = make_provider(handler)
        reply = run(provider.converse(request()))

        assert FAKE_KEY not in reply.text
        assert "[redacted]" in reply.text

    def test_vision_call_sends_image_part(self):
        bodies = []

        def handler(request: httpx.Request) -> httpx.Response:
            bodies.append(json.loads(request.content))
            return httpx.Response(200, json=gemini_body("It is a notebook."))

        provider, _ = make_provider(handler)
        reply = run(
            provider.describe_image(
                VisionProviderRequest(image_base64="QUJDRA==", mime_type="image/jpeg", prompt="What is this?")
            )
        )

        assert reply.text == "It is a notebook."
        assert bodies[0]["contents"][0]["parts"][1]["inline_data"]["data"] == "QUJDRA=="

    def test_aclose_closes_the_client(self):
        provider, _ = make_provider(lambda request: httpx.Response(200, json=gemini_body("ok")))
        run(provider.converse(request()))
        run(provider.aclose())

        assert provider._client is None or provider._client.is_closed


# ---------------------------------------------------------------------------
# Stub provider and factory
# ---------------------------------------------------------------------------

class TestStubProviderAndFactory:
    def test_stub_never_claims_grounding_and_labels_itself(self):
        provider = StubProvider()
        reply = run(provider.converse(request(text="weather today", needs_current_info=True)))

        assert reply.grounded is False
        assert reply.sources == ()
        assert "stub provider" in reply.text
        assert "cannot check live information" in reply.text

    def test_stub_responds_in_requested_language(self):
        provider = StubProvider()
        reply = run(provider.converse(ProviderRequest(text="namaste", language="hi")))

        assert reply.text.startswith("Namaste")

    def test_factory_selects_provider_from_settings(self):
        assert isinstance(build_default_provider(Settings(ai_provider="gemini")), GeminiHttpProvider)
        assert isinstance(build_default_provider(Settings(ai_provider="stub")), StubProvider)

    def test_factory_rejects_unknown_provider(self):
        with pytest.raises(ValueError):
            build_default_provider(Settings(ai_provider="openai"))

    def test_stub_settings_report_ready_without_a_key(self):
        assert Settings(ai_provider="stub").is_ready is True
        assert Settings(ai_provider="gemini").is_ready is False
        assert Settings(ai_provider="gemini", gemini_api_key=FAKE_KEY).is_ready is True
        assert Settings(ai_provider="gemini", gemini_api_key="   ").is_ready is False
