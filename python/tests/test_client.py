"""TempoClient / AsyncTempoClient: health, ping, version, probes, tenant + token headers, close."""

from __future__ import annotations

import asyncio

import httpx
import pytest

import grow_grafana_tempo as sdk
from _fixtures import BASE, Router, async_client, fixture_response, response, sync_client
from _support import closed_port_url, make_config

TOKEN = "proxy-token-789"


def test_health_ready_and_probes() -> None:
    router = Router().fixture("ready.ok").fixture("echo.ok").fixture("buildInfo.ok")
    with sync_client(router) as c:
        assert c.health() == sdk.HealthStatus(True, "ok", {"ready": "ready"})
        assert c.ready() is True
        assert c.echo() == "echo"
        info = c.build_info()
        assert info == sdk.BuildInfo("3.1.1", "9db7df79d", "HEAD", "", "", "go1.27.3")
        assert c.server_version() == "3.1.1"


def test_health_variants() -> None:
    router = Router().add(
        "/ready",
        response(503, "Ingester not ready: waiting for 15s after being ready\n"),
        response(200, "starting"),
    )
    c = sync_client(router)
    status = c.health()
    assert not status.healthy and status.status == "unhealthy"
    assert status.details["httpStatus"] == "503"
    assert status.details["reason"].startswith("Ingester not ready")
    assert c.health() == sdk.HealthStatus(False, "unhealthy", {"reason": "starting"})
    assert c.ready() is False
    assert len([r for r in router.requests if r.url.path == "/ready"]) == 3  # never retried


def test_ready_503_is_false_and_network_error_raises() -> None:
    router = Router().add("/ready", response(503, "not ready"))
    assert sync_client(router).ready() is False
    c = sdk.TempoClient(make_config(closed_port_url()))
    assert c.health().status == "unreachable"
    with pytest.raises(sdk.UnavailableError):
        c.ready()


def test_health_reraises_config_and_cancel() -> None:
    c = sdk.TempoClient(make_config(BASE), http_transport=Router().transport())
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.health()


def test_ping_sends_tenant_and_token() -> None:
    router = Router().fixture("searchTags.v2-scope-intrinsic", "/api/v2/search/tags")
    with sync_client(router, tenant_id="tenant-a", token=TOKEN) as c:
        assert c.ping() >= 0
        req = router.last("/api/v2/search/tags")
        assert req.url.params["scope"] == "intrinsic"
        assert req.headers["X-Scope-OrgID"] == "tenant-a"
        assert req.headers["Authorization"] == f"Bearer {TOKEN}"
        c.health()
        assert router.last("/ready").headers["X-Scope-OrgID"] == "tenant-a"
        assert TOKEN not in repr(c)


def test_ping_without_tenant_on_multi_tenant_tempo() -> None:
    router = Router().fixture("searchTags.mt-no-tenant-401")
    with pytest.raises(sdk.AuthenticationError) as info:
        sync_client(router).ping()
    assert "tenant required (X-Scope-OrgID)" in str(info.value)
    assert info.value.http_status == 401
    assert "X-Scope-OrgID" not in router.last().headers


def test_proxy_401_stays_authentication_error() -> None:
    router = Router().add("/api/v2/search/tags", response(401, "bad credentials " + TOKEN))
    with pytest.raises(sdk.AuthenticationError) as info:
        sync_client(router, token=TOKEN).ping()
    assert TOKEN not in str(info.value)
    assert "tenant required" not in str(info.value)


def test_server_version_fallbacks() -> None:
    # buildinfo route missing → /status/version text
    router = Router().fixture("serverVersion.status-version-text")
    assert sync_client(router).server_version() == "3.1.1"
    # empty version in buildinfo → text fallback, leading v stripped
    router = Router().add("/api/status/buildinfo", response(200, {"version": ""}))
    router.add("/status/version", response(200, "tempo, version v2.9.0 (branch: main)"))
    assert sync_client(router).server_version() == "2.9.0"
    # neither → UnsupportedError
    with pytest.raises(sdk.UnsupportedError):
        sync_client(Router()).server_version()
    router = Router().add("/status/version", response(200, "no version here"))
    with pytest.raises(sdk.UnsupportedError):
        sync_client(router).server_version()
    # other errors propagate
    router = Router().add("/api/status/buildinfo", response(401, "no org id"))
    with pytest.raises(sdk.AuthenticationError):
        sync_client(router).server_version()


def test_query_property_close_and_from_env() -> None:
    router = Router()
    c = sync_client(router)
    assert c.query is c.query
    assert c.query.client is c
    assert c.resources is not None
    c.query.close()  # not owned: the client stays open
    assert not c._transport.closed
    c.close()
    c.close()
    env = {"GROW_GRAFANA_TEMPO_URL": "http://tempo:3200", "TEMPO_TENANT_ID": "t1"}
    with sdk.TempoClient.from_env(env) as c2:
        assert c2.config.url == "http://tempo:3200"
        assert c2.config.tenant_id == "t1"
    raw = sync_client(Router().fixture("echo.ok"))
    assert raw.request(sdk.Request("GET", "/api/echo")).text == "echo\n"


def test_default_config_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GROW_GRAFANA_TEMPO_URL", "http://from-env:3200")
    with sdk.TempoClient() as c:
        assert c.config.url == "http://from-env:3200"

    async def run() -> None:
        async with sdk.AsyncTempoClient() as a:
            assert a.config.url == "http://from-env:3200"

    asyncio.run(run())


# --------------------------------------------------------------------------- async


async def test_async_client_surface() -> None:
    router = (
        Router()
        .fixture("ready.ok")
        .fixture("echo.ok")
        .fixture("buildInfo.ok")
        .fixture("searchTags.v2-scope-intrinsic", "/api/v2/search/tags")
    )
    async with async_client(router, tenant_id="tenant-a") as c:
        assert (await c.health()).healthy
        assert await c.ready() is True
        assert await c.echo() == "echo"
        assert (await c.build_info()).version == "3.1.1"
        assert await c.server_version() == "3.1.1"
        assert await c.ping() >= 0
        assert router.last("/api/v2/search/tags").headers["X-Scope-OrgID"] == "tenant-a"
        assert c.query is c.query and c.query.client is c
        assert c.resources is not None
        assert (await c.request(sdk.Request("GET", "/api/echo"))).status == 200
        assert "AsyncTempoClient(" in repr(c)
    await c.aclose()


async def test_async_health_and_version_fallback() -> None:
    router = Router().add("/ready", response(503, "starting"))
    router.fixture("serverVersion.status-version-text")
    c = async_client(router)
    status = await c.health()
    assert status.status == "unhealthy" and status.details["httpStatus"] == "503"
    assert await c.server_version() == "3.1.1"
    unreachable = sdk.AsyncTempoClient(make_config(closed_port_url()))
    assert (await unreachable.health()).status == "unreachable"
    await unreachable.aclose()
    with pytest.raises(sdk.AuthenticationError):
        await async_client(Router().fixture("searchTags.mt-no-tenant-401")).ping()
    await c.close()


async def test_async_from_env() -> None:
    c = sdk.AsyncTempoClient.from_env({"TEMPO_URL": "http://t:3200"})
    assert c.config.url == "http://t:3200"
    await c.aclose()


def test_fixture_response_helper_text() -> None:
    resp = fixture_response("getTrace.v1-not-found")
    assert isinstance(resp, httpx.Response) and resp.status_code == 404


def test_resources_stopped_on_close() -> None:
    stopped: list[str] = []

    class Watcher:
        def __init__(self, name: str, fail: bool = False) -> None:
            self.name, self.fail = name, fail

        def stop(self) -> None:
            stopped.append(self.name)
            if self.fail:
                raise RuntimeError("ignored")

    c = sync_client(Router())
    a, b = Watcher("a", fail=True), Watcher("b")
    c.resources.register(a)
    c.resources.register(b)
    c.resources.unregister(b)
    c.close()
    assert stopped == ["a"] and c.resources.closed
    c.resources.register(Watcher("late"))
    assert stopped == ["a", "late"]


def test_poll_until_helpers() -> None:
    calls = iter([None, sdk.UnavailableError("not yet"), [], [1]])

    def attempt() -> list[int] | None:
        value = next(calls)
        if isinstance(value, Exception):
            raise value
        return value

    assert sdk.poll_until(attempt, timeout_s=1, interval_s=0, sleep=lambda _s: None) == [1]
    with pytest.raises(sdk.TimeoutError):
        sdk.poll_until(lambda: None, timeout_s=0.01, interval_s=0.005)
    with pytest.raises(sdk.ValidationError):
        sdk.poll_until(lambda: (_ for _ in ()).throw(sdk.ValidationError("no")), timeout_s=1)

    async def run() -> None:
        values = iter([sdk.TimeoutError("slow"), 0, 5])

        async def aattempt() -> int | None:
            value = next(values)
            if isinstance(value, Exception):
                raise value
            return value

        assert await sdk.apoll_until(aattempt, timeout_s=1, interval_s=0, predicate=bool) == 5
        with pytest.raises(sdk.TimeoutError):
            await sdk.apoll_until(_none, timeout_s=0.01, interval_s=0.005)
        with pytest.raises(sdk.ValidationError):
            await sdk.apoll_until(_bad, timeout_s=1)

    asyncio.run(run())


async def _none() -> None:
    return None


async def _bad() -> None:
    raise sdk.ValidationError("no")
