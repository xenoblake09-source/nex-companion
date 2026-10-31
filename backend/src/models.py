"""Request / response schemas for the NEX companion API.

All schemas are strict (``extra="forbid"``) and bounded, so an oversized or
unexpected payload is rejected during validation and never reaches a provider.
"""

from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field

from .config import HARD_IMAGE_B64_LIMIT, HARD_INPUT_CHAR_LIMIT

#: BCP-47-ish tag: 2-3 letter primary subtag with optional subtags.
LANGUAGE_PATTERN = r"^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$"
VERSION_PATTERN = r"^[A-Za-z0-9._+-]{1,32}$"
STRICT = ConfigDict(extra="forbid")


class ClientContext(BaseModel):
    """Small, non-identifying client metadata. No device ids, no location."""

    model_config = STRICT

    platform: Optional[str] = Field(default=None, max_length=16)
    app_version: Optional[str] = Field(default=None, pattern=VERSION_PATTERN)
    locale: Optional[str] = Field(default=None, pattern=LANGUAGE_PATTERN, max_length=16)
    reduced_motion: Optional[bool] = None


class ConverseRequest(BaseModel):
    """POST /v1/converse body."""

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    text: str = Field(..., min_length=1, max_length=HARD_INPUT_CHAR_LIMIT)
    language: Optional[str] = Field(default=None, pattern=LANGUAGE_PATTERN, max_length=16)
    needs_current_info: bool = Field(default=False)
    context: Optional[ClientContext] = None


class SourceRef(BaseModel):
    """A single grounded web source. Title + URL metadata only."""

    model_config = STRICT

    title: str = Field(..., min_length=1, max_length=300)
    url: str = Field(..., min_length=8, max_length=1_000)


class ConverseResponse(BaseModel):
    """POST /v1/converse response body (HTTP 200)."""

    model_config = STRICT

    reply: str
    language: str = "en"
    grounded: bool = False
    sources: List[SourceRef] = Field(default_factory=list)
    error: Optional[str] = None


class VisionRequest(BaseModel):
    """POST /v1/vision body — a single frame the user explicitly asked about.

    Images are never stored by this service; they are forwarded to the model
    for one turn and then dropped.
    """

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    image_base64: str = Field(..., min_length=64, max_length=HARD_IMAGE_B64_LIMIT)
    mime_type: str = Field(default="image/jpeg", pattern=r"^image/(?:jpeg|png|webp)$")
    prompt: str = Field(default="What is this?", min_length=1, max_length=600)
    language: Optional[str] = Field(default=None, pattern=LANGUAGE_PATTERN, max_length=16)
    needs_current_info: bool = Field(default=False)


class VisionResponse(BaseModel):
    """POST /v1/vision response body (HTTP 200)."""

    model_config = STRICT

    reply: str
    language: str = "en"
    grounded: bool = False
    sources: List[SourceRef] = Field(default_factory=list)
    image_stored: bool = False
    error: Optional[str] = None


class HealthResponse(BaseModel):
    """GET /health — deliberately boring and secret-free."""

    model_config = STRICT

    status: str = "ok"
    provider: str
    model: str
    ready: bool
    grounding_enabled: bool
    version: str


class ErrorDetail(BaseModel):
    """Machine readable error code plus a message that is safe to show a user."""

    model_config = ConfigDict(extra="forbid")

    code: str
    message: str
    request_id: Optional[str] = None
    details: Optional[List[dict]] = None


class ErrorResponse(BaseModel):
    """Envelope for every non-200 response."""

    model_config = STRICT

    error: ErrorDetail
