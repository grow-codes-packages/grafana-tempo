"""Core round trips against the real service plus the core and tracing conformance scenarios."""

from __future__ import annotations

import asyncio
import os
from typing import Any

import pytest
from factories import (
    CORE_CAPABILITIES,
    TRACING_CAPABILITIES,
    async_core_client,
    core_client,
    tracing_target,
)

import grow_grafana_tempo as sdk
from grow_grafana_tempo.testing import ConformanceRunner, assert_scenario, pytest_params


def test_health(client: sdk.TempoClient) -> None:
    status = client.health()
    assert status.healthy, status
    assert status.details == {"ready": "ready"}


def test_ping(client: sdk.TempoClient) -> None:
    assert client.ping() >= 0


def test_server_version(client: sdk.TempoClient, env: dict[str, str]) -> None:
    assert client.server_version() == env["GROW_GRAFANA_TEMPO_SERVER_VERSION"]


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() >= 0
            assert await c.server_version() == env["GROW_GRAFANA_TEMPO_SERVER_VERSION"]

    asyncio.run(run())


def test_missing_tenant_is_rejected(env: dict[str, str]) -> None:
    """Tempo has no auth; on a multi-tenant Tempo a missing tenant is the 401 case."""
    mt = {k: v for k, v in env.items() if k != "GROW_GRAFANA_TEMPO_TENANT_ID"}
    with sdk.TempoClient.from_env(mt, url=env["GROW_GRAFANA_TEMPO_MT_URL"]) as c:
        assert c.health().healthy  # /ready needs no tenant
        with pytest.raises(sdk.AuthenticationError) as info:
            c.ping()
        assert "X-Scope-OrgID" in str(info.value)
    with sdk.TempoClient.from_env(env, url=env["GROW_GRAFANA_TEMPO_MT_URL"]) as c:
        assert c.ping() >= 0


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)


@pytest.mark.parametrize("scenario", pytest_params(categories=["tracing"]))
def test_tracing_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    run_id = os.environ.get("GROW_TEST_RUN_ID", "local")
    runner = ConformanceRunner(
        {"tracing": lambda ctx: tracing_target(env, ctx.service_name)},
        capabilities=TRACING_CAPABILITIES,
        service_name=f"grow-conformance-python-{run_id}-{os.getpid()}",
    )
    assert_scenario(runner, scenario)
