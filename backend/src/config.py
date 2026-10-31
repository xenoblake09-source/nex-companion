"""Runtime configuration for the NEX companion API.

Every value comes from the environment (Render env vars, or a local shell).
Nothing secret is ever hard-coded here, and nothing secret is ever returned to
a client: see :mod:`src.safety` for the redaction helpers used on the way out.
"""

from __future__ import annotations

from functools import lru_cache
from typing import List, Optional

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

#: Absolute ceiling for a single user message, independent of the configured
#: soft limit. Requests larger than this are rejected by schema validation
#: before any handler code runs.
HARD_INPUT_CHAR_LIMIT = 8_000

#: Absolute ceiling for an uploaded vision frame (base64 characters).
HARD_IMAGE_B64_LIMIT = 2_400_000

#: Providers this service knows how to drive.
SUPPORTED_PROVIDERS: tuple[str, ...] = ("gemini", "stub")

#: Languages NEX explicitly supports for reply/TTS alignment.
SUPPORTED_LANGUAGES: tuple[str, ...] = ("en", "hi")
DEFAULT_LANGUAGE = "en"

#: Rough characters-per-token factor used to bound the spoken reply length.
CHARS_PER_TOKEN = 4


class Settings(BaseSettings):
    """Environment-backed settings.

    Field names map case-insensitively to environment variables, so
    ``gemini_api_key`` is read from ``GEMINI_API_KEY`` and ``max_input_chars``
    from ``MAX_INPUT_CHARS`` (the names used in ``render.yaml``).
    """

    model_config = SettingsConfigDict(
        case_sensitive=False,
        extra="ignore",
        env_file=None,
    )

    app_name: str = "nex-companion-api"
    app_version: str = "1.0.0"
    environment: str = Field(default="production")
    log_level: str = Field(default="INFO")

    # --- provider -------------------------------------------------------
    ai_provider: str = Field(default="gemini")
    gemini_model: str = Field(default="gemini-2.5-flash")
    gemini_api_key: Optional[SecretStr] = Field(default=None)
    gemini_base_url: str = Field(
        default="https://generativelanguage.googleapis.com/v1beta"
    )
    provider_timeout_seconds: float = Field(default=20.0, gt=0, le=120)

    @field_validator("ai_provider", mode="before")
    @classmethod
    def _validate_provider(cls, value: object) -> str:
        """Fail fast on an unknown provider instead of silently serving nothing."""
        if not isinstance(value, str):
            raise ValueError("AI_PROVIDER must be a string")
        candidate = value.strip().lower()
        if candidate not in SUPPORTED_PROVIDERS:
            raise ValueError(
                f"AI_PROVIDER must be one of {sorted(SUPPORTED_PROVIDERS)}, got {value!r}"
            )
        return candidate


    # --- bounds ---------------------------------------------------------
    max_reply_tokens: int = Field(default=320, gt=0, le=4_096)
    max_input_chars: int = Field(default=1_200, gt=0, le=HARD_INPUT_CHAR_LIMIT)
    max_image_bytes: int = Field(default=1_500_000, gt=0, le=6_000_000)
    max_grounding_sources: int = Field(default=5, gt=0, le=20)

    # --- web security ---------------------------------------------------
    allowed_origins: str = Field(default="")
    grounding_enabled: bool = Field(default=True)

    # ------------------------------------------------------------------
    @field_validator("gemini_base_url")
    @classmethod
    def _validate_base_url(cls, value: str) -> str:
        """Refuse a non-HTTPS provider endpoint so a key can never be sent in clear."""
        if not value.lower().startswith("https://"):
            raise ValueError("GEMINI_BASE_URL must be an https URL")
        return value.rstrip("/")

    @property
    def is_ready(self) -> bool:
        """True only when the configured provider can actually serve a turn.

        ``stub`` is a deterministic local development mode; it reports ready so
        the app can be exercised end to end, and every stub reply says so.
        """
        provider = self.ai_provider.lower()
        if provider == "stub":
            return True
        if provider != "gemini" or self.gemini_api_key is None:
            return False
        return bool(self.gemini_api_key.get_secret_value().strip())

    @property
    def effective_input_limit(self) -> int:
        """Configured message limit, never above the hard schema ceiling."""
        return min(self.max_input_chars, HARD_INPUT_CHAR_LIMIT)

    @property
    def max_reply_chars(self) -> int:
        """Upper bound on reply characters derived from the token budget."""
        return max(120, self.max_reply_tokens * CHARS_PER_TOKEN)

    @property
    def cors_origins(self) -> List[str]:
        """Comma separated ALLOWED_ORIGINS parsed into a list.

        An empty value means "no CORS header at all" (same-origin / native app
        only), which is the safe default for a mobile backend.
        """
        raw = (self.allowed_origins or "").strip()
        if not raw:
            return []
        return [origin.strip() for origin in raw.split(",") if origin.strip()]

    def public_snapshot(self) -> dict:
        """A representation that is safe to log or return. Contains no secrets."""
        return {
            "provider": self.ai_provider,
            "model": self.gemini_model,
            "ready": self.is_ready,
            "grounding_enabled": self.grounding_enabled,
            "max_input_chars": self.effective_input_limit,
            "max_reply_tokens": self.max_reply_tokens,
            "cors_origins_configured": len(self.cors_origins),
            "environment": self.environment,
            "version": self.app_version,
        }


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Process-wide settings instance read from the environment."""
    return Settings()


def load_settings(**overrides: object) -> Settings:
    """Build a settings object with explicit overrides (used by tests)."""
    return Settings(**overrides)  # type: ignore[arg-type]
