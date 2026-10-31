"""Shared pytest fixtures for the NEX backend tests.

No test in this suite needs a real Gemini key, real network access, or any
credential. Provider behaviour is faked at the provider boundary.
"""

from __future__ import annotations

from typing import Any, List, Optional

import pytest
from fastapi.testclient import TestClient

from src.config import Settings
from src.gemini_service import ProviderReply
from src.main import create_app

#: Obviously fake value, used only to prove secrets never leave the server.
FAKE_KEY = "AIzaSyFAKE-KEY-FOR-TESTS-ONLY-0123456789"

#: Environment variables that must not leak into a test run.
MANAGED_ENV_VARS = (
    "APP_NAME",
    "APP_VERSION",
    "ENVIRONMENT",
    "LOG_LEVEL",
    "AI_PROVIDER",
    "GEMINI_MODEL",
    "GEMINI_API_KEY",
    "GEMINI_BASE_URL",
    "PROVIDER_TIMEOUT_SECONDS",
    "MAX_REPLY_TOKENS",
    "MAX_INPUT_CHARS",
    "MAX_IMAGE_BYTES",
    "MAX_GROUNDING_SOURCES",
    "ALLOWED_ORIGINS",
    "GROUNDING_ENABLED",
)


class FakeProvider:
    """Records requests and returns a canned reply (or raises a canned error)."""

    name = "fake"

    def __init__(self, reply: Optional[ProviderReply] = None, error: Optional[Exception] = None) -> None:
        self.reply = reply or ProviderReply(text="Hi! I am listening.", grounded=False, sources=())
        self.error = error
        self.converse_calls: List[Any] = []
        self.vision_calls: List[Any] = []
        self.closed = False

    async def converse(self, request: Any) -> ProviderReply:
        self.converse_calls.append(request)
        if self.error is not None:
            raise self.error
        return self.reply

    async def describe_image(self, request: Any) -> ProviderReply:
        self.vision_calls.append(request)
        if self.error is not None:
            raise self.error
        return self.reply

    async def aclose(self) -> None:
        self.closed = True


@pytest.fixture(autouse=True)
def clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Make every test independent of the surrounding shell environment."""
    for name in MANAGED_ENV_VARS:
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def make_client():
    """Build a TestClient with injected settings and a fake provider."""

    def _make(provider: Optional[FakeProvider] = None, **overrides: Any):
        fake = provider if provider is not None else FakeProvider()
        settings = Settings(**overrides)
        app = create_app(settings=settings, provider=fake)
        return TestClient(app, raise_server_exceptions=False), fake, settings

    return _make


@pytest.fixture
def client(make_client):
    http_client, _, _ = make_client()
    return http_client


@pytest.fixture
def fake_provider_cls():
    """The FakeProvider class, for tests that need a specific reply or error."""
    return FakeProvider


@pytest.fixture
def fake_key():
    """An obviously fake credential used to assert secrets never leak."""
    return FAKE_KEY
