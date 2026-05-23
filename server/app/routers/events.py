"""SSE event-stream endpoint — GET /v1/events.

Phase 2: a foreground-only Server-Sent Events stream replacing the
Android client's 15-second inbox polling loop. The client opens this
stream on `Lifecycle.Event.ON_RESUME`, closes it on `ON_PAUSE`. Server
publishes hint events (`inbox.changed`, `state.changed`, `dismiss`)
when relevant state mutates; the client pulls the actual content via
the existing REST endpoints.

Authentication and revocation enforcement use the same
`current_auth_context` as every other sensitive route — a revoked
device's handshake is rejected at 401 with `device_revoked`. A device
revoked mid-stream gets its connection torn down by the revoke handler
(via `EventBus.close_device_subscribers`), not by JWT expiry.

Heartbeats are SSE comment lines (`:` prefix) sent every ~25 seconds
to keep OkHttp's read timeout and intermediate proxies from killing
an idle connection.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.auth import AuthContext, ACCESS_TOKEN_EXPIRE_HOURS, current_auth_context
from app.services.events import HEARTBEAT_BYTES, encode_sse, get_event_bus

logger = logging.getLogger(__name__)

router = APIRouter(tags=["events"])

# How often to push heartbeat comment lines on an idle stream. Picked
# at ~25s because OkHttp's default read timeout is 30s — a heartbeat
# right below that keeps the connection alive without burning many
# wake-ups on the device.
_HEARTBEAT_INTERVAL_SECONDS = 25.0

# Max stream age. Auth is enforced at handshake; once the JWT used to
# authenticate expires, the stream is no longer covered by a valid
# token. Proactively close just below the JWT TTL so a stale stream
# can't outlive its authorization (Codex consultation 56 RED #6).
# Client reconnects with a fresh token if it still has one.
_MAX_STREAM_AGE_SECONDS = (ACCESS_TOKEN_EXPIRE_HOURS * 3600) - 60


@router.get("")
async def events(
    ctx: AuthContext = Depends(current_auth_context),
) -> StreamingResponse:
    """Open an SSE stream for the authenticated device.

    The handshake is enforced by `current_auth_context`: bootstrap
    tokens (no `did`), tokens for unknown devices, and tokens for
    revoked devices are all rejected with HTTP 401 before the stream
    opens.

    Streams stay open until:
    - The client disconnects (normal `ON_PAUSE` flow).
    - The device is revoked server-side
      (`EventBus.close_device_subscribers` enqueues the shutdown
      sentinel).
    - The server is restarted.
    """
    bus = get_event_bus()
    sub = await bus.subscribe(user_id=ctx.user.id, device_id=ctx.device.id)

    started_at = time.monotonic()

    async def stream() -> AsyncIterator[bytes]:
        try:
            while True:
                # Max-age check: if the stream's JWT has effectively
                # expired, close the connection so it can't outlive its
                # authorization. Client reconnects with a fresh token.
                if time.monotonic() - started_at > _MAX_STREAM_AGE_SECONDS:
                    logger.info(
                        "sse: stream max-age reached user=%s device=%s; closing",
                        ctx.user.id, ctx.device.id,
                    )
                    return

                try:
                    event = await asyncio.wait_for(
                        sub.queue.get(),
                        timeout=_HEARTBEAT_INTERVAL_SECONDS,
                    )
                except asyncio.TimeoutError:
                    # No events in the window; send a heartbeat comment so
                    # the connection stays alive.
                    yield HEARTBEAT_BYTES
                    continue

                if event is None:
                    # Shutdown sentinel — revoke flow asked us to close.
                    logger.info(
                        "sse: shutting down stream user=%s device=%s (server-initiated)",
                        ctx.user.id, ctx.device.id,
                    )
                    return

                yield encode_sse(event)
        finally:
            await bus.unsubscribe(sub)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={
            # Prevent any intermediate proxy from buffering / batching the
            # stream — nginx in particular will buffer text/* responses
            # unless told not to.
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )
