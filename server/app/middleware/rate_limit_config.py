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
    "message_send": RateLimitConfig(name="message_send", max_count=60, window_seconds=60),
    "message_send_user_hour": RateLimitConfig(name="message_send_user_hour", max_count=600, window_seconds=3600),
    "manifest_fetch": RateLimitConfig(name="manifest_fetch", max_count=30, window_seconds=60),
    "action_callback": RateLimitConfig(name="action_callback", max_count=120, window_seconds=60),
}

# TODO: Wire these dependencies as the pairing, message, manifest, and callback routes are authored.
