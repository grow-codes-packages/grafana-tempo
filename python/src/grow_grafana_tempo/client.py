"""Grafana Tempo clients (sync and async).

This file is owned by the service package (generated once by sdk-kit). The query API hangs off
the client as ``client.query`` (:class:`~grow_grafana_tempo.TempoQueryClient`); spans are sent
with :func:`~grow_grafana_tempo.init_tracing` (OTLP).
"""

from __future__ import annotations

import contextlib
import dataclasses
import re
import time
from collections.abc import Mapping
from types import TracebackType
from typing import Any

import httpx

from ._core import (
    AsyncHttpTransport,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    HttpTransport,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
    redact_text,
    truncate,
)
from .config import TempoConfig
from .query import AsyncTempoQueryClient, BuildInfo, TempoQueryClient

__all__ = ["AsyncTempoClient", "TempoClient"]

_HEALTH = Request("GET", "/ready", operation="health", raise_for_status=False, max_retries=0)
_PING = Request("GET", "/api/v2/search/tags", operation="ping", params={"scope": "intrinsic"})
_STATUS_VERSION = Request(
    "GET", "/status/version", operation="server_version", raise_for_status=False
)
_VERSION_TEXT = re.compile(r"version\s+v?([0-9][0-9A-Za-z.+-]*)")


def _health_from_response(resp: Response, secrets: list[str]) -> HealthStatus:
    word = resp.text.strip()
    if resp.ok and word == "ready":
        return HealthStatus(healthy=True, status="ok", details={"ready": word})
    details = {"reason": truncate(redact_text(word, secrets), 300)}
    if not resp.ok:
        details["httpStatus"] = str(resp.status)
    return HealthStatus(healthy=False, status="unhealthy", details=details)


def _health_from_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    word = "unreachable" if isinstance(err, (UnavailableError, TimeoutError)) else "unhealthy"
    return HealthStatus(healthy=False, status=word, details={"error": err.message})


def _version_from_text(resp: Response) -> str:
    match = _VERSION_TEXT.search(resp.text) if resp.ok else None
    if match is None:
        raise UnsupportedError("server_version: the service does not report a version")
    return match.group(1)


def _with_headers(config: TempoConfig, req: Request) -> Request:
    return dataclasses.replace(req, headers={**config.request_headers(), **(req.headers or {})})


class TempoClient:
    """Synchronous Grafana Tempo client (query API, port 3200). Thread-safe; use as a context
    manager or call :meth:`close`.

    ``client.query`` gives the query API (``get_trace``, ``search``, ``search_tags``,
    ``search_tag_values``, ``traceql_metrics`` and the SPEC ``TraceQueryClient`` methods). Every
    I/O method accepts ``cancel=`` (a :class:`CancelToken`) to abort from another thread.

    >>> with TempoClient.from_env() as client:  # doctest: +SKIP
    ...     trace = client.query.get_trace("5b8efff798038103d269b633813fc60c")
    """

    def __init__(
        self,
        config: TempoConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        if config is None:
            config = TempoConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._transport = HttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._query: TempoQueryClient | None = None

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> TempoClient:
        """Build a client from ``GROW_GRAFANA_TEMPO_*`` (then ``TEMPO_URL``,
        ``TEMPO_TENANT_ID``); keyword overrides win."""
        return cls(TempoConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`close`."""
        return self._resources

    @property
    def query(self) -> TempoQueryClient:
        """The query API sharing this client's transport and tenant."""
        if self._query is None:
            self._query = TempoQueryClient(self)
        return self._query

    def request(self, req: Request, *, cancel: CancelToken | None = None) -> Response:
        """Send a raw request through the client's transport (retries, hooks, redaction)."""
        return self._transport.request(req, cancel=cancel)

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Readiness: ``GET /ready`` (no tenant needed). ``ready`` → healthy ``ok``; HTTP 503
        while Tempo starts → ``unhealthy`` with ``details["reason"]``; network errors →
        ``unreachable``. Never raises for an unhealthy or unreachable service."""
        try:
            resp = self._transport.request(_with_headers(self.config, _HEALTH), cancel=cancel)
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp, self.config.known_secrets())

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Tenant-aware round trip ``GET /api/v2/search/tags?scope=intrinsic``; returns the
        latency in milliseconds. A multi-tenant Tempo without a tenant →
        :class:`AuthenticationError`."""
        started = time.monotonic()
        self.query._send(_PING, cancel)
        return (time.monotonic() - started) * 1000.0

    def build_info(self, *, cancel: CancelToken | None = None) -> BuildInfo:
        """``GET /api/status/buildinfo`` (see :meth:`TempoQueryClient.build_info`)."""
        return self.query.build_info(cancel=cancel)

    def echo(self, *, cancel: CancelToken | None = None) -> str:
        """``GET /api/echo`` → ``"echo"``."""
        return self.query.echo(cancel=cancel)

    def ready(self, *, cancel: CancelToken | None = None) -> bool:
        """``GET /ready`` → ``True`` when ready, ``False`` while starting; raises on network
        errors."""
        return self.query.ready(cancel=cancel)

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """Server version (``"3.1.1"``) from ``/api/status/buildinfo``, falling back to the text
        of ``/status/version``; :class:`UnsupportedError` when neither reports one."""
        version = ""
        with contextlib.suppress(UnsupportedError):
            version = self.build_info(cancel=cancel).version
        if not version:
            resp = self.query._send(_STATUS_VERSION, cancel)
            version = _version_from_text(resp)
        return version.removeprefix("v")

    def close(self) -> None:
        """Stop background resources and close connections (idempotent)."""
        self._resources.stop_all()
        self._transport.close()

    def __enter__(self) -> TempoClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"TempoClient(config={self.config!r})"


class AsyncTempoClient:
    """Asynchronous Grafana Tempo client; use ``async with`` or call :meth:`aclose`.

    Same methods as :class:`TempoClient`, awaited; ``client.query`` is an
    :class:`AsyncTempoQueryClient`. Cancel a call by cancelling its task: the SDK raises
    :class:`CancelledError`, which is also an :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: TempoConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if config is None:
            config = TempoConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._transport = AsyncHttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._query: AsyncTempoQueryClient | None = None

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> AsyncTempoClient:
        """Build a client from the environment (see :meth:`TempoClient.from_env`)."""
        return cls(TempoConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`aclose`."""
        return self._resources

    @property
    def query(self) -> AsyncTempoQueryClient:
        """The async query API sharing this client's transport and tenant."""
        if self._query is None:
            self._query = AsyncTempoQueryClient(self)
        return self._query

    async def request(self, req: Request) -> Response:
        """Send a raw request through the client's transport."""
        return await self._transport.request(req)

    async def health(self) -> HealthStatus:
        """See :meth:`TempoClient.health`."""
        try:
            resp = await self._transport.request(_with_headers(self.config, _HEALTH))
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp, self.config.known_secrets())

    async def ping(self) -> float:
        """See :meth:`TempoClient.ping`."""
        started = time.monotonic()
        await self.query._send(_PING)
        return (time.monotonic() - started) * 1000.0

    async def build_info(self) -> BuildInfo:
        """See :meth:`TempoClient.build_info`."""
        return await self.query.build_info()

    async def echo(self) -> str:
        """See :meth:`TempoClient.echo`."""
        return await self.query.echo()

    async def ready(self) -> bool:
        """See :meth:`TempoClient.ready`."""
        return await self.query.ready()

    async def server_version(self) -> str:
        """See :meth:`TempoClient.server_version`."""
        version = ""
        with contextlib.suppress(UnsupportedError):
            version = (await self.build_info()).version
        if not version:
            version = _version_from_text(await self.query._send(_STATUS_VERSION))
        return version.removeprefix("v")

    async def aclose(self) -> None:
        """Stop background resources and close connections (idempotent)."""
        self._resources.stop_all()
        await self._transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncTempoClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncTempoClient(config={self.config!r})"
