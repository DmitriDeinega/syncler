from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from math import ceil
from typing import Any

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_db
from app.middleware.rate_limit_config import RATE_LIMITS, RateLimitConfig


def rate_limit(name: str) -> Callable[[Request, AsyncSession], Awaitable[None]]:
    config = RATE_LIMITS[name]

    async def dependency(request: Request, db: AsyncSession = Depends(get_db)) -> None:
        await check_rate_limit(db, request, config)

    return dependency


async def check_rate_limit(db: AsyncSession, request: Request, config: RateLimitConfig) -> None:
    now = datetime.now(UTC)
    window_epoch = int(now.timestamp()) // config.window_seconds * config.window_seconds
    window_start = datetime.fromtimestamp(window_epoch, UTC)
    actor_type, actor_id = _identify_actor(config.name, request)

    count = await _increment_and_read(
        db,
        actor_type=actor_type,
        actor_id=actor_id,
        route=config.name,
        window_start=window_start,
    )
    await db.commit()

    if count > config.max_count:
        retry_after = max(1, ceil(window_epoch + config.window_seconds - now.timestamp()))
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="rate limited",
            headers={"Retry-After": str(retry_after)},
        )


async def _increment_and_read(
    db: AsyncSession,
    *,
    actor_type: str,
    actor_id: str,
    route: str,
    window_start: datetime,
) -> int:
    bind = db.get_bind()
    if bind.dialect.name == "sqlite":
        return await _increment_and_read_sqlite(
            db,
            actor_type=actor_type,
            actor_id=actor_id,
            route=route,
            window_start=window_start,
        )

    result = await db.execute(
        text(
            """
            INSERT INTO rate_limit_events (actor_type, actor_id, route, window_start, count)
            VALUES (:actor_type, :actor_id, :route, :window_start, 1)
            ON CONFLICT (actor_type, actor_id, route, window_start)
            DO UPDATE SET count = rate_limit_events.count + 1
            RETURNING count
            """
        ),
        {
            "actor_type": actor_type,
            "actor_id": actor_id,
            "route": route,
            "window_start": window_start,
        },
    )
    return int(result.scalar_one())


async def _increment_and_read_sqlite(
    db: AsyncSession,
    *,
    actor_type: str,
    actor_id: str,
    route: str,
    window_start: datetime,
) -> int:
    existing = await db.execute(
        text(
            """
            SELECT id, count
            FROM rate_limit_events
            WHERE actor_type = :actor_type
              AND actor_id = :actor_id
              AND route = :route
              AND window_start = :window_start
            """
        ),
        {
            "actor_type": actor_type,
            "actor_id": actor_id,
            "route": route,
            "window_start": window_start,
        },
    )
    row = existing.first()
    if row is not None:
        row_data = row._mapping
        next_count = int(row_data["count"]) + 1
        await db.execute(
            text("UPDATE rate_limit_events SET count = :count WHERE id = :id"),
            {"count": next_count, "id": row_data["id"]},
        )
        return next_count

    max_id = await db.execute(text("SELECT COALESCE(MAX(id), 0) + 1 FROM rate_limit_events"))
    await db.execute(
        text(
            """
            INSERT INTO rate_limit_events (id, actor_type, actor_id, route, window_start, count)
            VALUES (:id, :actor_type, :actor_id, :route, :window_start, 1)
            """
        ),
        {
            "id": int(max_id.scalar_one()),
            "actor_type": actor_type,
            "actor_id": actor_id,
            "route": route,
            "window_start": window_start,
        },
    )
    return 1


def _identify_actor(name: str, request: Request) -> tuple[str, str]:
    if name in {"login", "signup", "message_send_ip"}:
        return "ip", _client_ip(request)

    if name in {"pairing_initiate", "message_send", "plugin_publish"}:
        return "sender", _required_actor_value(request, "sender_id", "X-Sender-ID")

    if name == "message_send_user_hour":
        sender_id = _required_actor_value(request, "sender_id", "X-Sender-ID")
        user_id = _state_or_request_value(request, "user_id", "X-User-ID")
        if user_id is None:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="missing rate limit actor")
        return "sender_user", f"{sender_id}:{user_id}"

    if name == "manifest_fetch":
        # `/v1/plugins/{sender_id}/{plugin_identifier}/latest` is unauthenticated
        # (devices don't carry a session token on this lookup), so we key the
        # bucket off the sender_id path param — protects each sender's catalog
        # from being hammered without requiring callers to advertise a device id.
        return "sender", _required_actor_value(request, "sender_id", "X-Sender-ID")

    if name == "action_callback":
        return "pairing", _required_actor_value(request, "pairing_id", "X-Pairing-ID")

    user_id = getattr(request.state, "user_id", None)
    if user_id is not None:
        return "user", str(user_id)
    return "ip", _client_ip(request)


def _required_actor_value(request: Request, path_param: str, header: str) -> str:
    value = _state_or_request_value(request, path_param, header)
    if value is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="missing rate limit actor")
    return value


def _state_or_request_value(request: Request, path_param: str, header: str) -> str | None:
    state_value = getattr(request.state, path_param, None)
    if state_value is not None:
        return str(state_value)

    path_value: Any = request.path_params.get(path_param)
    if path_value is not None:
        return str(path_value)

    header_value = request.headers.get(header)
    if header_value:
        return header_value

    return None


def _client_ip(request: Request) -> str:
    forwarded_for = request.headers.get("x-forwarded-for")
    if forwarded_for:
        return forwarded_for.split(",", 1)[0].strip()
    if request.client is None:
        return "unknown"
    return request.client.host
