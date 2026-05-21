from dataclasses import dataclass


@dataclass(frozen=True)
class RateLimitConfig:
    name: str
    max_count: int
    window_seconds: int


RATE_LIMITS: dict[str, RateLimitConfig] = {
    "login": RateLimitConfig(name="login", max_count=5, window_seconds=60),
    "signup": RateLimitConfig(name="signup", max_count=3, window_seconds=60),
    "pairing_initiate": RateLimitConfig(name="pairing_initiate", max_count=10, window_seconds=60),
    # Per-sender publish bucket. Senders publish new plugin versions
    # infrequently; the budget exists to absorb retries / CI hiccups, not
    # legitimate publish traffic. Applied AFTER sender-signature verification
    # (see plugins.publish) so a spoofer can't inflate someone else's bucket.
    "plugin_publish": RateLimitConfig(name="plugin_publish", max_count=10, window_seconds=60),
    # M5.1: pre-signature IP bucket. After signature verifies, the per-sender
    # "message_send" bucket applies as defense-in-depth.
    "message_send_ip": RateLimitConfig(name="message_send_ip", max_count=120, window_seconds=60),
    "message_send": RateLimitConfig(name="message_send", max_count=60, window_seconds=60),
    "message_send_user_hour": RateLimitConfig(name="message_send_user_hour", max_count=600, window_seconds=3600),
    "manifest_fetch": RateLimitConfig(name="manifest_fetch", max_count=30, window_seconds=60),
    "action_callback": RateLimitConfig(name="action_callback", max_count=120, window_seconds=60),
}

# TODO: Wire these dependencies as the pairing, message, manifest, and callback routes are authored.
