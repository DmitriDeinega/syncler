"""Phase 5d — validator polish batch. Pure schema-level unit tests
(no DB / no app client) for the 5 new validation rules. Mirror of
sdk-plugin/test/manifest.test.ts.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas import (
    PluginPublishRequest,
    _is_allowed_action_endpoint_scheme,
    _PLUGIN_IDENTIFIER_REGEX,
)


_VALID_B64_32 = "A" * 43 + "="          # 32-byte base64 (32 / 3 * 4 with pad)
_VALID_B64_64 = "B" * 86 + "=="         # 64-byte base64

_BASE_PUBLISH_BODY: dict = {
    "sender_id": "00000000-1111-2222-3333-444444444444",
    "plugin_identifier": "com.example.weather",
    "version": "1.0.0",
    "manifest_hash": _VALID_B64_32,
    "bundle_hash": _VALID_B64_32,
    "signature": _VALID_B64_64,
    "signed_bundle_url": "https://example.com/bundle.js",
    "capabilities": ["network"],
    "endpoints": ["https://api.example.com/*"],
    "sender_signature": _VALID_B64_64,
}


# ---------------------------- Plugin identifier regex ----------------------------


def test_plugin_identifier_regex_accepts_reverse_dns() -> None:
    assert _PLUGIN_IDENTIFIER_REGEX.match("com.example.weather")
    assert _PLUGIN_IDENTIFIER_REGEX.match("com.example.deep.sub")
    assert _PLUGIN_IDENTIFIER_REGEX.match("org.trad-bot.app")


def test_plugin_identifier_regex_rejects_non_reverse_dns() -> None:
    assert not _PLUGIN_IDENTIFIER_REGEX.match("not-a-reverse-dns")
    assert not _PLUGIN_IDENTIFIER_REGEX.match("123.example.app")  # starts with digit
    assert not _PLUGIN_IDENTIFIER_REGEX.match("com")  # no dot
    assert not _PLUGIN_IDENTIFIER_REGEX.match("")
    assert not _PLUGIN_IDENTIFIER_REGEX.match(".com.example")


def test_publish_request_rejects_non_reverse_dns_identifier() -> None:
    bad = {**_BASE_PUBLISH_BODY, "plugin_identifier": "not-a-reverse-dns"}
    with pytest.raises(ValidationError) as exc:
        PluginPublishRequest.model_validate(bad)
    assert "reverse-DNS" in str(exc.value)


# ---------------------------- cardKeyPath grammar ----------------------------


def test_publish_request_rejects_card_key_path_with_array_indexing() -> None:
    bad = {
        **_BASE_PUBLISH_BODY,
        "card_type": "live",
        "card_key_path": "$.items[0].id",
    }
    with pytest.raises(ValidationError) as exc:
        PluginPublishRequest.model_validate(bad)
    assert "$.field(.subfield)" in str(exc.value)


def test_publish_request_accepts_nested_card_key_path() -> None:
    good = {
        **_BASE_PUBLISH_BODY,
        "card_type": "live",
        "card_key_path": "$.order.id",
    }
    parsed = PluginPublishRequest.model_validate(good)
    assert parsed.card_key_path == "$.order.id"


# ---------------------------- Action endpoint scheme ----------------------------


def test_action_endpoint_scheme_helper() -> None:
    assert _is_allowed_action_endpoint_scheme("https://api.example.com/x")
    assert _is_allowed_action_endpoint_scheme("http://localhost:8001/x")
    assert _is_allowed_action_endpoint_scheme("http://10.0.2.2:8001/x")
    assert _is_allowed_action_endpoint_scheme("http://192.168.1.5/x")
    assert _is_allowed_action_endpoint_scheme("http://172.20.0.5/x")
    assert _is_allowed_action_endpoint_scheme("http://127.0.0.1/x")
    # Cleartext to public hosts:
    assert not _is_allowed_action_endpoint_scheme("http://api.example.com/x")
    # 172.32 is OUTSIDE the 172.16-31 private range:
    assert not _is_allowed_action_endpoint_scheme("http://172.32.0.5/x")
    # Localhost-lookalike doesn't count:
    assert not _is_allowed_action_endpoint_scheme("http://localhost.evil.com/x")
    # user:pass@ form rejected for HTTP:
    assert not _is_allowed_action_endpoint_scheme(
        "http://user:pass@10.0.0.1/x"
    )
    # Non-http schemes:
    assert not _is_allowed_action_endpoint_scheme("ftp://api.example.com/x")
    assert not _is_allowed_action_endpoint_scheme("file:///etc/passwd")


# Codex consultation 80 RED #2a: HTTPS with userinfo was previously accepted
# because the userinfo guard only fired on HTTP. The URL-parser rewrite
# rejects userinfo for BOTH schemes.
def test_action_endpoint_scheme_rejects_https_userinfo() -> None:
    assert not _is_allowed_action_endpoint_scheme(
        "https://user:pass@api.example.com/x"
    )
    assert not _is_allowed_action_endpoint_scheme(
        "https://user@api.example.com/x"
    )


# Codex consultation 84 RED: Python `re.IGNORECASE` does Unicode case
# folding by default; JS `/i` is ASCII-only. With re.ASCII added,
# Python no longer folds `K` (U+212A Kelvin), `İ`, `ı`, `ſ` into [A-Z].
def test_action_endpoint_scheme_rejects_unicode_casefold_host_chars() -> None:
    assert not _is_allowed_action_endpoint_scheme("https://K.example/x")
    assert not _is_allowed_action_endpoint_scheme("https://İ.example/x")
    assert not _is_allowed_action_endpoint_scheme("https://ı.example/x")
    assert not _is_allowed_action_endpoint_scheme("https://ſ.example/x")
    # Unicode-fold scheme:
    assert not _is_allowed_action_endpoint_scheme("httpſ://localhost/x")


# Codex consultation 84 RED: Python `\d` matches Unicode digits like
# the Arabic-Indic ١٢; JS `\d` is ASCII-only. Both sides use `[0-9]`.
def test_action_endpoint_scheme_rejects_unicode_port_digits() -> None:
    assert not _is_allowed_action_endpoint_scheme(
        "https://api.example:١٢/x"
    )


# Codex consultation 84 RED: Python `.` matches `\r`; JS `.` doesn't.
# Both sides restrict path/query/fragment to printable ASCII.
def test_action_endpoint_scheme_rejects_trailing_carriage_return() -> None:
    assert not _is_allowed_action_endpoint_scheme("https://api.example/x\r")
    assert not _is_allowed_action_endpoint_scheme("http://10.0.0.1/x\r")
    # Tab in path also rejected:
    assert not _is_allowed_action_endpoint_scheme("https://api.example/x\ty")


# Codex consultation 83 RED: Python `$` in `re.match` matches before
# a trailing newline; JS regex `$` is end-of-string. Server uses
# `re.fullmatch` to align. Test rejects the trailing-newline form.
def test_action_endpoint_scheme_rejects_trailing_newline() -> None:
    assert not _is_allowed_action_endpoint_scheme(
        "https://api.example.com/ack\n"
    )
    assert not _is_allowed_action_endpoint_scheme(
        "http://10.0.0.1/ack\n"
    )


# Codex consultation 83 host-class tightening: ASCII alnum + . - _ only.
def test_action_endpoint_scheme_rejects_whitespace_and_idn_in_host() -> None:
    assert not _is_allowed_action_endpoint_scheme("https://api example.com/x")
    assert not _is_allowed_action_endpoint_scheme("https://api\texample.com/x")
    # Raw IDN (non-ASCII):
    assert not _is_allowed_action_endpoint_scheme("https://münich.example/x")
    # Percent-encoded host byte:
    assert not _is_allowed_action_endpoint_scheme("https://%31%32%37.0.0.1/x")
    # Sanity: punycoded ASCII IDN still accepted (HTTPS public):
    assert _is_allowed_action_endpoint_scheme("https://xn--mnich-kva.example/x")


# Codex consultation 82 RED: WHATWG treats empty userinfo as no
# userinfo, and normalizes backslash schemes; urlparse doesn't.
# Strict raw-URL regex (both sides) rejects all of these.
def test_action_endpoint_scheme_rejects_empty_userinfo_and_backslash() -> None:
    assert not _is_allowed_action_endpoint_scheme("http://@10.0.0.1/x")
    assert not _is_allowed_action_endpoint_scheme("http://:@10.0.0.1/x")
    assert not _is_allowed_action_endpoint_scheme("https://@api.example.com/x")
    # Backslash schemes — WHATWG normalizes to forward slash; urlparse
    # leaves them as-is. Either way the strict regex rejects.
    assert not _is_allowed_action_endpoint_scheme("https:\\api.example.com/x")
    assert not _is_allowed_action_endpoint_scheme("https:\\\\api.example.com/x")
    # Sanity: real userinfo (already covered) is still rejected.
    assert not _is_allowed_action_endpoint_scheme(
        "http://user:pass@10.0.0.1/x"
    )


# Codex consultation 81 RED: WHATWG URL normalizes legacy IPv4 forms
# while Python urlparse doesn't, creating SDK/server mismatch. Fix:
# require canonical 4-octet decimal IPv4, reject leading-zero octets.
def test_action_endpoint_scheme_rejects_legacy_ipv4_forms() -> None:
    # urlparse leaves 0177.0.0.1 as-is; we still reject because the
    # leading "0" in "0177" makes it non-canonical (also octal in many
    # parsers — keeps parity with WHATWG-normalizing SDK).
    assert not _is_allowed_action_endpoint_scheme("http://0177.0.0.1/x")
    # Compressed 3-octet form: urlparse leaves 127.1 as-is; our strict
    # 4-octet regex rejects.
    assert not _is_allowed_action_endpoint_scheme("http://127.1/x")
    # Leading-zero octet that urlparse would otherwise accept as 10.x.
    assert not _is_allowed_action_endpoint_scheme("http://010.0.0.1/x")
    # Single-zero octets ARE valid (0.0.0.0 / x.0.x.x etc., though
    # not in a LAN range — these are still well-formed).
    assert _is_allowed_action_endpoint_scheme("http://127.0.0.1/x")


# Codex consultation 80 RED #2b: previously only the first two IPv4 octets
# were range-checked, so 10.999.999.999 passed. Every octet must be 0..255.
def test_action_endpoint_scheme_rejects_out_of_range_ipv4_octets() -> None:
    assert not _is_allowed_action_endpoint_scheme("http://10.999.999.999/x")
    assert not _is_allowed_action_endpoint_scheme("http://10.0.0.999/x")
    assert not _is_allowed_action_endpoint_scheme("http://10.0.999.0/x")
    assert not _is_allowed_action_endpoint_scheme("http://192.168.1.256/x")
    # 256 is one past the limit:
    assert not _is_allowed_action_endpoint_scheme("http://10.256.0.0/x")
    # 255 is the max valid octet:
    assert _is_allowed_action_endpoint_scheme("http://10.255.255.255/x")


def test_publish_request_rejects_cleartext_action_endpoint_to_public_host() -> None:
    bad = {
        **_BASE_PUBLISH_BODY,
        "renderer": "template",
        # The endpoint glob is also cleartext, so the
        # declaredEndpoints match would succeed; we test the
        # scheme rule specifically.
        "endpoints": ["http://api.example.com/*"],
        "template": {
            "layout": "standard_card",
            "fields": {"title": {"path": "$.title"}},
            "actions": [{"id": "ack", "label": "Ack", "endpoint": "http://api.example.com/ack"}],
        },
    }
    with pytest.raises(ValidationError) as exc:
        PluginPublishRequest.model_validate(bad)
    assert "HTTPS" in str(exc.value)


def test_publish_request_accepts_lan_http_action_endpoint() -> None:
    good = {
        **_BASE_PUBLISH_BODY,
        "renderer": "template",
        "endpoints": ["http://192.168.1.10:8001/*"],
        "template": {
            "layout": "standard_card",
            "fields": {"title": {"path": "$.title"}},
            "actions": [
                {"id": "ack", "label": "Ack", "endpoint": "http://192.168.1.10:8001/ack"},
            ],
        },
    }
    PluginPublishRequest.model_validate(good)


# ---------------------------- standard_card field key set ----------------------------


def test_publish_request_rejects_unknown_template_field_key() -> None:
    bad = {
        **_BASE_PUBLISH_BODY,
        "renderer": "template",
        "template": {
            "layout": "standard_card",
            "fields": {
                "title": {"path": "$.title"},
                "headline": {"path": "$.headline"},  # not in allowed set
            },
        },
    }
    with pytest.raises(ValidationError) as exc:
        PluginPublishRequest.model_validate(bad)
    assert "does not accept fields" in str(exc.value)
