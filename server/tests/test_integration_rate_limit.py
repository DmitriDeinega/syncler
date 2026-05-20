import base64
from datetime import timedelta

import pytest
from freezegun import freeze_time
from httpx import AsyncClient


def b64_bytes(length: int, fill: int) -> str:
    return base64.b64encode(bytes([fill]) * length).decode("ascii")


def signup_payload(email: str) -> dict[str, object]:
    return {
        "email": email,
        "auth_key_hash": b64_bytes(32, 1),
        "encrypted_master_key": b64_bytes(48, 2),
        "auth_salt": b64_bytes(16, 3),
        "argon2_params_version": 1,
    }


@pytest.mark.asyncio
async def test_signup_rate_limit_resets_after_window(app_client: AsyncClient) -> None:
    headers = {"x-forwarded-for": "203.0.113.42"}

    with freeze_time("2026-01-01 12:00:00+00:00") as freezer:
        responses = [
            await app_client.post(
                "/v1/auth/signup",
                json=signup_payload(f"rate-limit-{index}@example.com"),
                headers=headers,
            )
            for index in range(4)
        ]

        assert [response.status_code for response in responses[:3]] == [201, 201, 201]
        assert responses[3].status_code == 429
        assert responses[3].json()["detail"] == "rate limited"

        freezer.tick(delta=timedelta(seconds=61))
        reset_response = await app_client.post(
            "/v1/auth/signup",
            json=signup_payload("rate-limit-reset@example.com"),
            headers=headers,
        )

    assert reset_response.status_code == 201
