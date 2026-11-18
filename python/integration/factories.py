"""Factories the integration suite and the conformance runner use (owned by this repository).

Tempo serves a trace by id immediately after ingest, but tag values (``list_services``) only
about 30 seconds later, so the tracing target wraps the query client and lets
``list_services`` wait until the configured service shows up (the conformance runner calls it
once, without polling).
"""

from __future__ import annotations

import time
from collections.abc import Mapping
from typing import Any

import grow_grafana_tempo as sdk
from grow_grafana_tempo.testing import TracingTarget

URL_VAR = "GROW_GRAFANA_TEMPO_URL"

#: Seconds integration tests allow for search, tags and metrics to see new data.
SEARCH_VISIBILITY_S = 90.0

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = {"version"}

#: Capabilities for the tracing scenarios. No "dependencies": Tempo has no dependency API.
TRACING_CAPABILITIES: set[str] = {"query", "services"}


def core_client(env: Mapping[str, str]) -> sdk.TempoClient:
    """A client configured from the test environment (URL and tenant from /bootstrap/env)."""
    return sdk.TempoClient.from_env(env)


def async_core_client(env: Mapping[str, str]) -> sdk.AsyncTempoClient:
    return sdk.AsyncTempoClient.from_env(env)


def tracing_handle(env: Mapping[str, str], service_name: str, **fields: Any) -> sdk.TracingHandle:
    """``init_tracing`` exporting to the compose Tempo (OTLP/HTTP unless overridden)."""
    fields.setdefault("set_global", False)
    fields.setdefault("batch", sdk.SpanBatchOptions(scheduled_delay_s=0.2))
    return sdk.init_tracing(service_name=service_name, env=env, **fields)


class VisibleQuery:
    """Query client whose ``list_services`` waits until ``service_name`` is listed."""

    def __init__(self, query: sdk.TempoQueryClient, service_name: str | None) -> None:
        self._query = query
        self._service_name = service_name

    def __getattr__(self, name: str) -> Any:
        return getattr(self._query, name)

    def list_services(self) -> list[str]:
        deadline = time.monotonic() + SEARCH_VISIBILITY_S
        while True:
            services = self._query.list_services()
            if self._service_name is None or self._service_name in services:
                return services
            if time.monotonic() > deadline:
                return services
            time.sleep(2)


def tracing_target(env: Mapping[str, str], service_name: str | None) -> TracingTarget:
    """A ``TracingTarget(handle, query)`` for one conformance scenario."""
    name = service_name or "grow-conformance-python"
    handle = tracing_handle(env, name)
    query = VisibleQuery(sdk.TempoQueryClient.from_env(env), service_name)
    return TracingTarget(handle, query)
