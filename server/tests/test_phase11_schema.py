"""Phase 11 — server schema validation for native Kotlin plugins.

Exercises ``PluginPublishRequest`` validation for:
- renderer == "native_kotlin" requires both entry_class and
  native_sdk_abi.
- renderer != "native_kotlin" must NOT carry those fields.
- entry_class matches the Java/Kotlin binary class name regex.
- native_sdk_abi >= 1.

Spec: docs/plugin-host-native-kotlin.md.
"""

from __future__ import annotations

import base64

import pytest

from app.schemas import PluginPublishRequest


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _base_publish_body(**overrides) -> dict:
    body = {
        "sender_id": "11111111-1111-1111-1111-111111111111",
        "plugin_identifier": "com.example.test",
        "version": "1.0.0",
        "manifest_hash": _b64(b"\x00" * 32),
        "bundle_hash": _b64(b"\x00" * 32),
        "signature": _b64(b"\x00" * 64),
        "signed_bundle_url": "https://example.com/plugin",
        "capabilities": [],
        "endpoints": [],
        "sender_signature": _b64(b"\x00" * 64),
    }
    body.update(overrides)
    return body


def test_script_renderer_default_accepted() -> None:
    PluginPublishRequest.model_validate(_base_publish_body())


def test_native_kotlin_with_both_fields_accepted() -> None:
    req = PluginPublishRequest.model_validate(_base_publish_body(
        renderer="native_kotlin",
        entry_class="com.example.test.MyPlugin",
        native_sdk_abi=1,
    ))
    assert req.entry_class == "com.example.test.MyPlugin"
    assert req.native_sdk_abi == 1


def test_native_kotlin_without_entry_class_rejected() -> None:
    with pytest.raises(Exception) as exc_info:
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="native_kotlin",
            native_sdk_abi=1,
        ))
    assert "entry_class required when renderer == 'native_kotlin'" in str(exc_info.value)


def test_native_kotlin_without_abi_rejected() -> None:
    with pytest.raises(Exception) as exc_info:
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="native_kotlin",
            entry_class="com.example.test.MyPlugin",
        ))
    assert "native_sdk_abi required when renderer == 'native_kotlin'" in str(exc_info.value)


def test_script_with_entry_class_rejected() -> None:
    """entry_class is forbidden on non-native renderers — anti-spoofing
    defense so a sender can't slip native metadata into a script
    bundle that won't actually use it."""
    with pytest.raises(Exception) as exc_info:
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="script",
            entry_class="com.example.WontBeUsed",
        ))
    assert "entry_class must be omitted when renderer != 'native_kotlin'" in str(exc_info.value)


def test_script_with_native_sdk_abi_rejected() -> None:
    with pytest.raises(Exception) as exc_info:
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="script",
            native_sdk_abi=1,
        ))
    assert "native_sdk_abi must be omitted when renderer != 'native_kotlin'" in str(exc_info.value)


def test_entry_class_regex_rejects_bad_names() -> None:
    for bad in (
        "",                               # empty
        "1starts.with.digit",             # segment starts with digit
        "no_dot_at_all",                  # no dot — valid simple name; OK
        "com..double.dot",                # empty segment
        "trailing.",                      # trailing dot
        ".leading",                       # leading dot
        "com.example.has space",          # contains space
        "com.example/slash",              # contains slash
    ):
        if bad == "no_dot_at_all":
            # Bare class names ARE valid binary names; should accept.
            PluginPublishRequest.model_validate(_base_publish_body(
                renderer="native_kotlin",
                entry_class=bad,
                native_sdk_abi=1,
            ))
            continue
        with pytest.raises(Exception):
            PluginPublishRequest.model_validate(_base_publish_body(
                renderer="native_kotlin",
                entry_class=bad,
                native_sdk_abi=1,
            ))


def test_entry_class_regex_accepts_valid_names() -> None:
    for good in (
        "MyPlugin",
        "com.example.MyPlugin",
        "com.example._underscore.Class",
        "com.example.$Dollar",
        "com.example.OuterClass$InnerClass",  # nested classes use $
        "io.x.y.z.PluginV2",
    ):
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="native_kotlin",
            entry_class=good,
            native_sdk_abi=1,
        ))


def test_entry_class_length_cap() -> None:
    too_long = "a." * 130  # 260 chars
    with pytest.raises(Exception) as exc_info:
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="native_kotlin",
            entry_class=too_long,
            native_sdk_abi=1,
        ))
    assert "256" in str(exc_info.value)


def test_native_sdk_abi_must_be_positive() -> None:
    with pytest.raises(Exception) as exc_info:
        PluginPublishRequest.model_validate(_base_publish_body(
            renderer="native_kotlin",
            entry_class="com.example.MyPlugin",
            native_sdk_abi=0,
        ))
    assert "native_sdk_abi must be >= 1" in str(exc_info.value)
