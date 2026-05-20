from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import __version__
from app.db import dispose_engine, init_engine


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    init_engine()
    try:
        yield
    finally:
        await dispose_engine()


app = FastAPI(title="Syncler Server", version=__version__, lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "version": __version__}
