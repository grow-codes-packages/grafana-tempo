"""Every public Tempo function against the real service (write → read back).

A session fixture exports known traces over OTLP/HTTP, OTLP/gRPC and to the multi-tenant
instance; trace-by-id assertions run at once, search/tags/metrics tests poll (Tempo shows new
data there about 30 seconds after ingest).
"""

from __future__ import annotations

import asyncio
import dataclasses
import datetime as dt
import os
import secrets
import time
from collections.abc import Callable
from typing import Any, TypeVar

import pytest
from factories import SEARCH_VISIBILITY_S, tracing_handle
from opentelemetry import trace as otel_trace
from opentelemetry.trace import SpanKind, Status, StatusCode

import grow_grafana_tempo as sdk

T = TypeVar("T")
MS = 1_000_000


@dataclasses.dataclass(frozen=True)
class Ingested:
    run: str
    service: str
    trace_id: str
    helper_trace_id: str
    grpc_trace_id: str
    grpc_service: str
    mt_trace_id: str
    start: dt.datetime
    end: dt.datetime


def _export_orders(handle: sdk.TracingHandle, run: str) -> str:
    """Root (450 ms) with a DB child (300 ms, event) and a failing child (50 ms)."""
    tracer = handle.tracer("grow-sdk-it", "1.0.0")
    now = time.time_ns()
    root = tracer.start_span(
        "GET /orders",
        kind=SpanKind.SERVER,
        start_time=now,
        attributes={
            "http.request.method": "GET",
            "http.response.status_code": 200,
            "grow.run": run,
            "grow weird.key": "x y",
            "grow.note": 'a "quoted" \\ value ü',
        },
    )
    ctx = otel_trace.set_span_in_context(root)
    db = tracer.start_span(
        "SELECT orders",
        context=ctx,
        kind=SpanKind.CLIENT,
        start_time=now + 5 * MS,
        attributes={"db.system.name": "postgresql", "grow.run": run},
    )
    db.add_event("rows", {"count": 3}, timestamp=now + 10 * MS)
    db.end(end_time=now + 305 * MS)
    charge = tracer.start_span(
        "charge",
        context=ctx,
        start_time=now + 310 * MS,
        attributes={"http.response.status_code": 503, "grow.run": run},
    )
    charge.set_status(Status(StatusCode.ERROR, "boom"))
    charge.end(end_time=now + 360 * MS)
    root.end(end_time=now + 450 * MS)
    return format(root.get_span_context().trace_id, "032x")


def _export_with_helpers(handle: sdk.TracingHandle) -> str:
    tracer = handle.tracer("grow-sdk-it-helpers")
    seen: dict[str, Any] = {}

    def work(span: otel_trace.Span) -> None:
        seen["trace_id"] = sdk.current_trace_id()
        seen["fields"] = sdk.trace_fields()
        sdk.add_event("checkpoint", {"step": 1})
        carrier: dict[str, str] = {}
        sdk.inject(carrier)
        seen["traceparent"] = carrier.get("traceparent", "")

    sdk.with_span("helpers", work, attributes={"grow.helper": True}, tracer=tracer)
    assert seen["fields"]["trace_id"] == seen["trace_id"]
    return str(seen["trace_id"])


@pytest.fixture(scope="session")
def ingested(env: dict[str, str]) -> Ingested:
    run = f"{os.environ.get('GROW_TEST_RUN_ID', 'local')}-{secrets.token_hex(4)}"
    service = f"grow-py-it-{run}"
    started = dt.datetime.now(dt.timezone.utc)
    with tracing_handle(env, service) as handle:
        trace_id = _export_orders(handle, run)
        helper_trace_id = _export_with_helpers(handle)
        handle.force_flush(30)
    grpc_service = f"{service}-grpc"
    grpc_handle = tracing_handle(
        env,
        grpc_service,
        protocol="grpc",
        endpoint=env["GROW_GRAFANA_TEMPO_OTLP_GRPC_ENDPOINT"],
    )
    with grpc_handle:
        grpc_trace_id = _export_orders(grpc_handle, run)
        grpc_handle.force_flush(30)
    mt_handle = tracing_handle(
        env,
        f"{service}-mt",
        endpoint=env["GROW_GRAFANA_TEMPO_MT_OTLP_ENDPOINT"],
        tenant_id=env["GROW_GRAFANA_TEMPO_TENANT_ID"],
    )
    with mt_handle:
        mt_trace_id = _export_orders(mt_handle, run)
        mt_handle.force_flush(30)
    return Ingested(
        run=run,
        service=service,
        trace_id=trace_id,
        helper_trace_id=helper_trace_id,
        grpc_trace_id=grpc_trace_id,
        grpc_service=grpc_service,
        mt_trace_id=mt_trace_id,
        start=started - dt.timedelta(minutes=5),
        end=dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5),
    )


def eventually(fn: Callable[[], T], ready: Callable[[T], bool]) -> T:
    """Poll ``fn`` until ``ready`` (search, tags and metrics lag ~30 s behind ingest)."""
    return sdk.poll_until(
        fn, timeout_s=SEARCH_VISIBILITY_S, interval_s=2, predicate=ready, operation="eventually"
    )


@pytest.fixture
def query(client: sdk.TempoClient) -> sdk.TempoQueryClient:
    return client.query


def mt_client(env: dict[str, str], tenant: str | None) -> sdk.TempoClient:
    base = {k: v for k, v in env.items() if k != "GROW_GRAFANA_TEMPO_TENANT_ID"}
    return sdk.TempoClient.from_env(base, url=env["GROW_GRAFANA_TEMPO_MT_URL"], tenant_id=tenant)


# --------------------------------------------------------------------------- immediate


def test_get_trace_round_trip(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    trace = query.wait_for_trace(ingested.trace_id, timeout_s=30, min_spans=3)
    assert trace.trace_id == ingested.trace_id
    names = [s.name for s in trace.spans]
    assert names == ["GET /orders", "SELECT orders", "charge"]
    root, db, charge = trace.spans
    assert root.kind == "server"
    assert root.parent_span_id is None
    assert root.service_name == ingested.service
    assert root.duration_micros == 450_000
    assert root.attributes["http.response.status_code"] == 200
    assert root.attributes["grow weird.key"] == "x y"
    assert root.attributes["grow.note"] == 'a "quoted" \\ value ü'
    assert db.parent_span_id == root.span_id
    assert db.kind == "client"
    assert db.events[0].name == "rows"
    assert db.events[0].attributes == {"count": 3}
    assert charge.status == sdk.SpanStatus("error", "boom")
    tree = trace.tree()
    assert [n.span.name for n in tree[0].children] == ["SELECT orders", "charge"]


def test_get_trace_variants(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    ranged = query.get_trace(ingested.trace_id, start=ingested.start, end=ingested.end)
    assert ranged is not None
    assert len(ranged.spans) == 3
    assert query.get_trace(secrets.token_hex(16)) is None
    with pytest.raises(sdk.ValidationError):
        query.get_trace("not-a-trace-id")
    helper = query.wait_for_trace(ingested.helper_trace_id, timeout_s=30)
    span = helper.spans[0]
    assert span.name == "helpers"
    assert span.attributes["grow.helper"] is True
    assert [e.name for e in span.events] == ["checkpoint"]


def test_grpc_export_round_trip(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    trace = query.wait_for_trace(ingested.grpc_trace_id, timeout_s=30, min_spans=3)
    assert {s.service_name for s in trace.spans} == {ingested.grpc_service}


def test_seed_trace(query: sdk.TempoQueryClient, env: dict[str, str]) -> None:
    trace = query.get_trace(env["GROW_GRAFANA_TEMPO_SEED_TRACE_ID"])
    assert trace is not None
    assert len(trace.spans) == 3
    assert set(trace.services()) == {
        env["GROW_GRAFANA_TEMPO_SEED_SERVICE"],
        env["GROW_GRAFANA_TEMPO_SEED_CHILD_SERVICE"],
    }


def test_multi_tenant(env: dict[str, str], ingested: Ingested) -> None:
    tenant = env["GROW_GRAFANA_TEMPO_TENANT_ID"]
    other = env["GROW_GRAFANA_TEMPO_OTHER_TENANT_ID"]
    with mt_client(env, tenant) as c:
        trace = c.query.wait_for_trace(ingested.mt_trace_id, timeout_s=30, min_spans=3)
        assert len(trace.spans) == 3
    with mt_client(env, other) as c:
        assert c.query.get_trace(ingested.mt_trace_id) is None
    with mt_client(env, f"{other}|{tenant}") as c:
        assert c.query.get_trace(ingested.mt_trace_id) is not None
    with mt_client(env, None) as c, pytest.raises(sdk.AuthenticationError):
        c.query.get_trace(ingested.mt_trace_id)


def test_probes(client: sdk.TempoClient, env: dict[str, str]) -> None:
    assert client.echo() == "echo"
    assert client.ready() is True
    info = client.build_info()
    assert info.version == env["GROW_GRAFANA_TEMPO_SERVER_VERSION"]
    assert info.go_version
    assert client.query.build_info() == info
    with pytest.raises(sdk.UnsupportedError):
        client.query.get_dependencies()


def test_invalid_traceql_is_validation_error(query: sdk.TempoQueryClient) -> None:
    with pytest.raises(sdk.ValidationError) as info:
        query.search("{ bad")
    assert info.value.http_status == 400
    with pytest.raises(sdk.ValidationError):
        query.traceql_metrics('{ resource.service.name = "x" }')


# --------------------------------------------------------------------------- searchable


def test_search_round_trip(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    traceql = (
        sdk.TraceQL.span({"resource.service.name": ingested.service})
        .and_("duration", ">", "200ms")
        .select("span.http.response.status_code", "name")
    )
    result = eventually(
        lambda: query.search(traceql, start=ingested.start, end=ingested.end, limit=20, spss=10),
        lambda r: any(t.trace_id == ingested.trace_id for t in r.traces),
    )
    hit = next(t for t in result.traces if t.trace_id == ingested.trace_id)
    assert hit.root_service_name == ingested.service
    assert hit.root_trace_name == "GET /orders"
    assert hit.duration_micros == 450_000
    spans = hit.span_sets[0].spans
    assert {s.name for s in spans} == {"GET /orders", "SELECT orders"}  # > 200 ms only
    assert all("http.response.status_code" in s.attributes or s.name for s in spans)
    escaped = query.search(
        sdk.TraceQL.span({"span.grow.note": 'a "quoted" \\ value ü', "span.grow weird.key": "x y"}),
        start=ingested.start,
        end=ingested.end,
    )
    assert any(t.trace_id == ingested.trace_id for t in escaped.traces)
    limited = query.search(sdk.TraceQL.span({"span.grow.run": ingested.run}), spss=1, limit=1)
    assert len(limited.traces) == 1
    assert len(limited.traces[0].span_sets[0].spans) == 1


def test_search_traces_round_trip(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    wanted = sdk.TraceSearchQuery(
        service=ingested.service,
        operation="GET /orders",
        tags={"grow.run": ingested.run},
        min_duration_micros=400_000,
        max_duration_micros=500_000,
        start=ingested.start,
        end=ingested.end,
    )
    found = eventually(lambda: query.search_traces(wanted), lambda r: len(r) > 0)
    summary = next(s for s in found if s.trace_id == ingested.trace_id)
    assert summary.root_name == "GET /orders"
    assert summary.span_count == 3
    assert summary.duration_micros == 450_000
    excluded = dataclasses.replace(wanted, min_duration_micros=600_000, max_duration_micros=None)
    assert query.search_traces(excluded) == []


def test_tags_and_values_round_trip(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    by_run = sdk.TraceQL.span({"span.grow.run": ingested.run})
    tags = eventually(
        lambda: query.search_tags(scope="span", query=by_run),
        lambda t: "grow.run" in t.get("span", []),
    )
    assert "grow weird.key" in tags["span"]
    all_scopes = query.search_tags(start=ingested.start, end=ingested.end)
    assert {"resource", "span", "intrinsic"} <= set(all_scopes)
    values = eventually(
        lambda: query.search_tag_values("span.grow.run", query=by_run),
        lambda v: sdk.TagValue("string", ingested.run) in v,
    )
    assert values
    codes = query.search_tag_values("span.http.response.status_code", query=by_run)
    assert {v.value for v in codes} >= {"200", "503"}
    assert {v.type for v in codes} == {"int"}
    weird = query.search_tag_values("span.grow weird.key", query=by_run)
    assert sdk.TagValue("string", "x y") in weird


def test_list_services_and_operations(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    services = eventually(query.list_services, lambda s: ingested.service in s)
    assert services == sorted(services)
    # Tempo 3.1.1's filtered tag values occasionally omit span names for good (CI saw one of the
    # four missing for the whole wait; Go/Rust/Java relax the same way): the root operation must
    # be there and nothing foreign.
    expected = {"GET /orders", "SELECT orders", "charge", "helpers"}
    ops = eventually(
        lambda: query.list_operations(ingested.service),
        lambda o: "GET /orders" in o and set(o) <= expected,
    )
    assert ops == sorted(ops)


def test_traceql_metrics_round_trip(query: sdk.TempoQueryClient, ingested: Ingested) -> None:
    q = sdk.TraceQL.span({"resource.service.name": ingested.service})
    metrics = eventually(
        lambda: query.traceql_metrics(f"{q} | count_over_time()", since_s=900, step_s=5),
        lambda r: any(s.value > 0 for series in r.series for s in series.samples),
    )
    assert sum(s.value for series in metrics.series for s in series.samples) >= 3
    assert metrics.step_ms == 5000
    quantiles = query.traceql_metrics(
        f"{q} | quantile_over_time(duration, .9)",
        start=ingested.start,
        end=dt.datetime.now(dt.timezone.utc),
        exemplars=5,
    )
    assert quantiles.series
    assert quantiles.series[0].labels.get("p") == 0.9


# --------------------------------------------------------------------------- async


def test_async_query_round_trip(env: dict[str, str], ingested: Ingested) -> None:
    async def run() -> None:
        async with sdk.AsyncTempoClient.from_env(env) as c:
            q = c.query
            trace = await q.wait_for_trace(ingested.trace_id, timeout_s=30, min_spans=3)
            assert len(trace.spans) == 3
            assert await q.get_trace(secrets.token_hex(16)) is None
            by_service = sdk.TraceQL.span({"resource.service.name": ingested.service})
            result = await q.search(by_service, start=ingested.start, end=ingested.end)
            assert any(t.trace_id == ingested.trace_id for t in result.traces)
            summaries = await q.search_traces(sdk.TraceSearchQuery(service=ingested.service))
            assert any(s.trace_id == ingested.trace_id for s in summaries)
            assert "span" in await q.search_tags(scope="span", query=by_service)
            assert await q.search_tag_values("name", query=by_service)
            assert ingested.service in await q.list_services()
            assert "charge" in await q.list_operations(ingested.service)
            metrics = await q.traceql_metrics(f"{by_service} | rate()", since_s=900)
            assert metrics.series
            assert await c.echo() == "echo"
            assert await c.ready() is True
            assert (await c.build_info()).version
            with pytest.raises(sdk.UnsupportedError):
                await q.get_dependencies()

    asyncio.run(run())
