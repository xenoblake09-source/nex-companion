"""Google Search grounding handling.

Honesty contract enforced here:

* ``sources`` are produced **only** from grounding metadata the provider really
  returned, and only for URLs that are valid ``https`` web references.
* ``is_grounded`` is True only when the provider reports a search entry point
  or at least one grounding chunk.
* ``enforce_grounding_honesty`` adds a truthful disclaimer when a reply claims
  live information but grounding did not happen.
"""

from __future__ import annotations

from typing import Any, Dict, List, Mapping, Optional, Tuple
from urllib.parse import urlsplit, urlunsplit

from .models import SourceRef
from .safety import contains_live_claim

MAX_TITLE_CHARS = 200
DEFAULT_SOURCE_LIMIT = 5


def _as_mapping(value: Any) -> Optional[Mapping[str, Any]]:
    return value if isinstance(value, Mapping) else None


def is_grounded(grounding_metadata: Any) -> bool:
    """True only when the provider reports that a live search actually happened."""
    metadata = _as_mapping(grounding_metadata)
    if not metadata:
        return False
    if metadata.get("searchEntryPoint"):
        return True
    chunks = metadata.get("groundingChunks")
    if isinstance(chunks, list):
        for chunk in chunks:
            mapping = _as_mapping(chunk)
            if mapping and _as_mapping(mapping.get("web")):
                return True
    return False


def _clean_https_url(raw: Any) -> Optional[str]:
    """Return a normalised https URL, or None when the value is not usable."""
    if not isinstance(raw, str):
        return None
    candidate = raw.strip()
    if not candidate:
        return None
    try:
        parts = urlsplit(candidate)
    except ValueError:
        return None
    if parts.scheme.lower() != "https":
        return None
    host = parts.netloc
    if not host or "@" in host:  # reject credentials embedded in the URL
        return None
    if not parts.path:
        parts = parts._replace(path="/")
    # Drop the fragment; keep the query because it is often the real citation.
    return urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ""))


def _clean_title(raw: Any, fallback_host: str) -> str:
    if isinstance(raw, str) and raw.strip():
        title = " ".join(raw.split())
        return title[:MAX_TITLE_CHARS]
    return fallback_host


def extract_sources(grounding_metadata: Any, limit: int = DEFAULT_SOURCE_LIMIT) -> List[SourceRef]:
    """Extract de-duplicated ``https`` title/URL pairs from grounding metadata."""
    metadata = _as_mapping(grounding_metadata)
    if not metadata:
        return []
    chunks = metadata.get("groundingChunks")
    if not isinstance(chunks, list):
        return []

    sources: List[SourceRef] = []
    seen: set[str] = set()
    effective_limit = max(1, int(limit))

    for chunk in chunks:
        mapping = _as_mapping(chunk)
        if not mapping:
            continue
        web = _as_mapping(mapping.get("web"))
        if not web:
            continue
        url = _clean_https_url(web.get("uri"))
        if url is None or url in seen:
            continue
        host = urlsplit(url).netloc
        title = _clean_title(web.get("title"), host)
        seen.add(url)
        sources.append(SourceRef(title=title, url=url))
        if len(sources) >= effective_limit:
            break

    return sources


def grounding_summary(grounding_metadata: Any) -> Dict[str, Any]:
    """A small, non-sensitive summary suitable for server-side logs."""
    metadata = _as_mapping(grounding_metadata)
    if not metadata:
        return {"grounded": False, "chunks": 0, "entry_point": False}
    chunks = metadata.get("groundingChunks")
    return {
        "grounded": is_grounded(metadata),
        "chunks": len(chunks) if isinstance(chunks, list) else 0,
        "entry_point": bool(metadata.get("searchEntryPoint")),
    }


def enforce_grounding_honesty(reply: str, grounded: bool) -> Tuple[str, bool]:
    """Keep an ungrounded reply from claiming live internet information.

    Returns ``(reply, disclaimer_added)``.
    """
    if grounded or not reply:
        return reply, False

    if not contains_live_claim(reply):
        return reply, False

    disclaimer = " Note: I answered from my own knowledge and did not browse the internet for this."
    trimmed = reply.rstrip()
    if trimmed.endswith((".", "!", "?")):
        return trimmed + disclaimer, True
    return trimmed + "." + disclaimer, True
