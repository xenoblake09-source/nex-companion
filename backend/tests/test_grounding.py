"""Tests for grounding extraction and the honesty guards around it."""

from __future__ import annotations

import pytest

from src.grounding_service import (
    enforce_grounding_honesty,
    extract_sources,
    grounding_summary,
    is_grounded,
)

REALISTIC_METADATA = {
    "searchEntryPoint": {"renderedContent": "<search-entry-point/>"},
    "groundingChunks": [
        {"web": {"uri": "https://example.org/weather?city=patna", "title": "Patna Weather Today"}},
        {"web": {"uri": "http://insecure.example/not-allowed", "title": "Insecure source"}},
        {"web": {"uri": "https://example.org/weather?city=patna", "title": "Duplicate of the first"}},
        {"web": {"uri": "https://nocreds.example/path", "title": "   Whitespace   title  "}},
        {"web": {"title": "Missing URI"}},
        {"retrievedContext": {"uri": "https://internal.example/context"}},
        "not-a-mapping",
    ],
    "groundingSupports": [{"segment": {"endIndex": 12}, "groundingChunkIndices": [0]}],
}


class TestIsGrounded:
    @pytest.mark.parametrize("metadata", [None, {}, [], "nope", 42, {"groundingChunks": []}])
    def test_not_grounded_without_real_metadata(self, metadata):
        assert is_grounded(metadata) is False

    def test_grounded_with_search_entry_point(self):
        assert is_grounded({"searchEntryPoint": {"renderedContent": "x"}}) is True

    def test_grounded_with_web_chunk(self):
        assert is_grounded({"groundingChunks": [{"web": {"uri": "https://a.example"}}]}) is True

    def test_not_grounded_with_only_non_web_chunks(self):
        assert is_grounded({"groundingChunks": [{"retrievedContext": {"uri": "https://a.example"}}]}) is False


class TestExtractSources:
    def test_only_valid_https_sources_are_returned(self):
        sources = extract_sources(REALISTIC_METADATA, limit=10)
        urls = [source.url for source in sources]

        assert urls == [
            "https://example.org/weather?city=patna",
            "https://nocreds.example/path",
        ]

    def test_duplicates_are_removed_and_first_title_wins(self):
        sources = extract_sources(REALISTIC_METADATA, limit=10)

        assert sources[0].title == "Patna Weather Today"

    def test_titles_are_normalised(self):
        sources = extract_sources(REALISTIC_METADATA, limit=10)

        assert sources[1].title == "Whitespace title"

    def test_limit_is_respected(self):
        metadata = {
            "groundingChunks": [
                {"web": {"uri": f"https://example.org/page-{index}", "title": f"Page {index}"}}
                for index in range(8)
            ]
        }

        assert len(extract_sources(metadata, limit=3)) == 3

    def test_urls_with_credentials_are_rejected(self):
        metadata = {"groundingChunks": [{"web": {"uri": "https://user:pass@example.org/x", "title": "Bad"}}]}

        assert extract_sources(metadata, limit=5) == []

    def test_fragments_are_dropped_query_is_kept(self):
        metadata = {"groundingChunks": [{"web": {"uri": "https://example.org/a?b=1#frag", "title": "T"}}]}

        assert extract_sources(metadata, limit=5)[0].url == "https://example.org/a?b=1"

    def test_missing_title_falls_back_to_host(self):
        metadata = {"groundingChunks": [{"web": {"uri": "https://example.org/only-path"}}]}

        assert extract_sources(metadata, limit=5)[0].title == "example.org"

    @pytest.mark.parametrize("metadata", [None, {}, {"groundingChunks": "not-a-list"}])
    def test_malformed_metadata_returns_no_sources(self, metadata):
        assert extract_sources(metadata, limit=5) == []


class TestGroundingSummary:
    def test_summary_is_safe_and_accurate(self):
        summary = grounding_summary(REALISTIC_METADATA)

        assert summary == {"grounded": True, "chunks": 7, "entry_point": True}

    def test_summary_without_metadata(self):
        assert grounding_summary(None) == {"grounded": False, "chunks": 0, "entry_point": False}


class TestGroundingHonesty:
    @pytest.mark.parametrize(
        "reply",
        [
            "According to the latest reports, it is raining.",
            "I just searched the web for you.",
            "As of today the price is 92 rupees.",
            "Here is the live score you asked for.",
        ],
    )
    def test_live_claims_are_disclaimed_when_not_grounded(self, reply):
        text, added = enforce_grounding_honesty(reply, grounded=False)

        assert added is True
        assert "did not browse the internet" in text

    def test_no_disclaimer_for_plain_knowledge_answer(self):
        text, added = enforce_grounding_honesty("Patna is the capital of Bihar.", grounded=False)

        assert added is False
        assert text == "Patna is the capital of Bihar."

    def test_grounded_reply_is_left_untouched(self):
        reply = "According to the latest reports, it is raining."
        text, added = enforce_grounding_honesty(reply, grounded=True)

        assert added is False
        assert text == reply

    def test_empty_reply_is_safe(self):
        assert enforce_grounding_honesty("", grounded=False) == ("", False)
