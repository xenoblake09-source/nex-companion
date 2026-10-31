"""Tests for the optional vision endpoint (one explicitly requested frame)."""

from __future__ import annotations

import base64

import pytest

from src.gemini_service import ProviderReply

VISION_URL = "/v1/vision"
VISION_KEYS = {"reply", "language", "grounded", "sources", "image_stored", "error"}
FAKE_KEY = "AIzaSyFAKE-KEY-FOR-TESTS-ONLY-0123456789"


def image_b64(payload_size: int = 200) -> str:
    """A syntactically valid base64 JPEG-ish blob (never a real photo)."""
    return base64.b64encode(b"\xff\xd8\xff\xe0" + b"A" * payload_size).decode("ascii")


class TestVisionSuccess:
    def test_returns_structured_response_and_never_stores_the_image(self, make_client, fake_provider_cls):
        provider = fake_provider_cls(reply=ProviderReply(text="That looks like a notebook."))
        client, _, _ = make_client(gemini_api_key=FAKE_KEY, provider=provider)
        response = client.post(VISION_URL, json={"image_base64": image_b64()})

        assert response.status_code == 200
        body = response.json()
        assert set(body) == VISION_KEYS
        assert body["reply"] == "That looks like a notebook."
        assert body["image_stored"] is False
        assert body["grounded"] is False
        assert body["sources"] == []
        assert body["error"] is None
        assert len(provider.vision_calls) == 1

    def test_default_prompt_is_used_when_none_is_given(self, make_client, fake_provider_cls):
        provider = fake_provider_cls()
        client, _, _ = make_client(gemini_api_key=FAKE_KEY, provider=provider)
        client.post(VISION_URL, json={"image_base64": image_b64()})

        assert provider.vision_calls[0].prompt == "What is this?"

    def test_explicit_prompt_and_language_are_forwarded(self, make_client, fake_provider_cls):
        provider = fake_provider_cls()
        client, _, _ = make_client(gemini_api_key=FAKE_KEY, provider=provider)
        client.post(
            VISION_URL,
            json={"image_base64": image_b64(), "prompt": "  Is this a maths book?  ", "language": "hi"},
        )
        call = provider.vision_calls[0]

        assert call.prompt == "Is this a maths book?"
        assert call.language == "hi"
        assert call.mime_type == "image/jpeg"

    def test_mime_type_is_forwarded(self, make_client, fake_provider_cls):
        provider = fake_provider_cls()
        client, _, _ = make_client(gemini_api_key=FAKE_KEY, provider=provider)
        client.post(VISION_URL, json={"image_base64": image_b64(), "mime_type": "image/png"})

        assert provider.vision_calls[0].mime_type == "image/png"


class TestVisionValidation:
    def test_rejects_invalid_base64(self, make_client):
        client, provider, _ = make_client(gemini_api_key=FAKE_KEY)
        response = client.post(VISION_URL, json={"image_base64": "!!!not base64!!!" * 10})

        assert response.status_code == 422
        assert response.json()["error"]["code"] == "invalid_image"
        assert provider.vision_calls == []

    def test_rejects_empty_image(self, make_client):
        client, _, _ = make_client(gemini_api_key=FAKE_KEY)
        response = client.post(VISION_URL, json={"image_base64": ""})

        assert response.status_code == 422
        assert response.json()["error"]["code"] == "invalid_request"

    def test_rejects_image_above_configured_byte_limit(self, make_client):
        client, provider, _ = make_client(gemini_api_key=FAKE_KEY, max_image_bytes=50)
        response = client.post(VISION_URL, json={"image_base64": image_b64(400)})

        assert response.status_code == 422
        assert response.json()["error"]["code"] == "image_too_large"
        assert provider.vision_calls == []

    @pytest.mark.parametrize("mime", ["image/gif", "application/pdf", "text/plain", "IMAGE/JPEG"])
    def test_rejects_unsupported_mime_types(self, make_client, mime):
        client, _, _ = make_client(gemini_api_key=FAKE_KEY)
        response = client.post(VISION_URL, json={"image_base64": image_b64(), "mime_type": mime})

        assert response.status_code == 422

    def test_rejects_unknown_field(self, make_client):
        client, _, _ = make_client(gemini_api_key=FAKE_KEY)
        response = client.post(
            VISION_URL, json={"image_base64": image_b64(), "save_to_drive": True}
        )

        assert response.status_code == 422

    def test_rejects_missing_image(self, make_client):
        client, _, _ = make_client(gemini_api_key=FAKE_KEY)
        response = client.post(VISION_URL, json={"prompt": "what is this?"})

        assert response.status_code == 422


class TestVisionReadiness:
    def test_returns_503_when_no_key_is_configured(self, make_client):
        client, provider, _ = make_client()
        response = client.post(VISION_URL, json={"image_base64": image_b64()})

        assert response.status_code == 503
        assert response.json()["error"]["code"] == "provider_not_configured"
        assert provider.vision_calls == []

    def test_image_bytes_never_appear_in_an_error_body(self, make_client):
        client, _, _ = make_client(max_image_bytes=10)
        payload = image_b64(400)
        response = client.post(VISION_URL, json={"image_base64": payload})

        assert response.status_code == 422
        assert payload not in response.text
