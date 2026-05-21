from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import __version__
from app.config import get_settings
from app.db import dispose_engine, init_engine
from app.routers import auth, devices, messages, pairing, plugins, senders, state


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    init_engine()
    try:
        yield
    finally:
        await dispose_engine()


def _app_title() -> str:
    """Append the environment label to the FastAPI title for any non-prod env.

    ``ENVIRONMENT=production`` → "Syncler Server". Anything else
    (development, staging, etc.) → "Syncler Server DEV" (uppercased label).
    """
    env = (get_settings().environment or "development").upper()
    return "Syncler Server" if env == "PRODUCTION" else f"Syncler Server {env}"


app = FastAPI(title=_app_title(), version=__version__, lifespan=lifespan)
app.include_router(auth.router, prefix="/v1/auth")
app.include_router(auth.account_router, prefix="/v1")
app.include_router(devices.router, prefix="/v1/auth/devices")
app.include_router(senders.router, prefix="/v1/senders")
app.include_router(pairing.router, prefix="/v1/pairing")
app.include_router(messages.router, prefix="/v1/messages")
app.include_router(state.router, prefix="/v1/state")
app.include_router(plugins.router, prefix="/v1/plugins")


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    print(f"[validation_error] {request.method} {request.url.path} -> {exc.errors()}", flush=True)
    return JSONResponse(status_code=400, content=jsonable_encoder({"detail": exc.errors()}))


@app.get("/health")
async def health() -> dict[str, str]:
    return {
        "status": "ok",
        "version": __version__,
        "environment": get_settings().environment,
        "title": _app_title(),
    }
