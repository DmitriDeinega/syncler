from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import __version__
from app.db import dispose_engine, init_engine
from app.routers import auth, devices, messages, pairing, senders, state


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    init_engine()
    try:
        yield
    finally:
        await dispose_engine()


app = FastAPI(title="Syncler Server", version=__version__, lifespan=lifespan)
app.include_router(auth.router, prefix="/v1/auth")
app.include_router(auth.account_router, prefix="/v1")
app.include_router(devices.router, prefix="/v1/auth/devices")
app.include_router(senders.router, prefix="/v1/senders")
app.include_router(pairing.router, prefix="/v1/pairing")
app.include_router(messages.router, prefix="/v1/messages")
app.include_router(state.router, prefix="/v1/state")


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=400, content=jsonable_encoder({"detail": exc.errors()}))


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}
