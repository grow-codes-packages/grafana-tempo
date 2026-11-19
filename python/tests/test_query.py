"""TempoQueryClient / AsyncTempoQueryClient against recorded Tempo 3.1.1 responses."""

from __future__ import annotations

import datetime as dt
from typing import Any

import pytest

import grow_grafana_tempo as sdk
from _fixtures import BASE, Router, async_client, load, raw_path, response, sync_client
from _support import make_config

SEED = "00f74bc1b058a1a8b85f9ae0231e4001"
START = dt.datetime.fromtimestamp(1795034947, dt.timezone.utc)
END = dt.datetime.fromtimestamp(1795035066.2, dt.timezone.utc)


def q(router: Router, **kw: Any) -> sdk.TempoQueryClient:
    return sync_client(router, **kw).query


# --------------------------------------------------------------------------- get_trace


def test_get_trace_v2() -> None:
    router = Router().fixture("getTrace.v2-ok")
    trace = q(router).get_trace(SEED.upper().lstrip("0"), start=START, end=END)
    assert trace is not None and trace.trace_id == SEED
    assert len(trace.spans) == 4
    assert router.params() == {"start": "1795034947", "end": "1795035067"}
    assert router.last().url.path == f"/api/v2/traces/{SEED}"


def test_get_trace_naive_datetime_and_only_start() -> None:
    router = Router().fixture("getTrace.v2-ok")
    q(router).get_trace(SEED, start=dt.datetime(2026, 11, 18, 12, 0, 0))
    assert router.params() == {"start": "1795003200"}


def test_get_trace_miss_and_v1_fallback() -> None:
    router = Router().fixture("getTrace.v2-not-found", f"/api/v2/traces/{SEED}")
    assert q(router).get_trace(SEED) is None
    # v2 route missing → v1
    router = Router().fixture("getTrace.v1-ok", f"/api/traces/{SEED}")
    trace = q(router).get_trace(SEED)
    assert trace is not None and len(trace.spans) == 4
    assert [r.url.path for r in router.requests] == [
        f"/api/v2/traces/{SEED}",
        f"/api/traces/{SEED}",
    ]
    # v1 miss (404, empty body) → None
    assert q(Router()).get_trace(SEED) is None


def test_get_trace_errors() -> None:
    for bad in ("nothex", "0" * 32, "1" * 33, ""):
        with pytest.raises(sdk.ValidationError):
            q(Router()).get_trace(bad)
    router = Router().fixture("getTrace.mt-no-tenant-401", f"/api/v2/traces/{SEED}")
    with pytest.raises(sdk.AuthenticationError) as info:
        q(router).get_trace(SEED)
    assert "tenant required" in str(info.value)
    router = Router().fixture("getTrace.v2-range-end-before-start", f"/api/v2/traces/{SEED}")
    with pytest.raises(sdk.ValidationError) as verr:
        q(router).get_trace(SEED, start=END, end=START)
    assert "start must be before end" in str(verr.value)
    with pytest.raises(sdk.ValidationError):
        q(Router()).get_trace(SEED, start="yesterday")  # type: ignore[arg-type]


def test_get_trace_tenant_header() -> None:
    router = Router().fixture("getTrace.mt-tenant-a")
    trace = q(router, tenant_id="tenant-a").get_trace(
        load("getTrace.mt-tenant-a")["request"]["path"].rsplit("/", 1)[1]
    )
    assert trace is not None
    assert router.last().headers["X-Scope-OrgID"] == "tenant-a"


# --------------------------------------------------------------------------- search


def test_search_builder_query() -> None:
    fixture = load("search.builder-duration-select")
    router = Router().fixture("search.builder-duration-select")
    query = sdk.TraceQL.span({"resource.service.name": "grow-sdk-verify-179503498127460-api"})
    query = query.and_("duration", ">", "200ms").select("span.http.response.status_code")
    result = q(router).search(query, start=START, end=END, limit=20, spss=10)
    assert router.params() == fixture["request"]["query"]
    assert len(result.traces) == 1
    hit = result.traces[0]
    assert hit.trace_id == SEED
    assert hit.duration_micros == 450_000
    assert hit.root_trace_name == "GET /orders"
    spans = hit.span_sets[0].spans
    assert len(spans) == 2 and spans[0].span_id == "a000000000000001"
    assert spans[0].duration_micros == 450_000 and spans[0].name is None
    assert "http.response.status_code" in spans[0].attributes
    assert isinstance(result.metrics.get("inspectedBytes", 0), (int, float))


def test_search_variants() -> None:
    router = Router().fixture("search.select-name-status")
    result = q(router).search("{ status = error } | select(name, status)")
    assert any(s.name for t in result.traces for ss in t.span_sets for s in ss.spans)
    router = Router().fixture("search.no-range")
    q(router).search()
    assert router.params() == {}
    router = Router().fixture("search.no-range")
    q(router).search("   ")
    assert router.params() == {}
    router = Router().fixture("search.spss-1")
    result = q(router).search("{ }", spss=1)
    assert all(len(ss.spans) <= 1 for t in result.traces for ss in t.span_sets)
    assert any(ss.matched > 1 for t in result.traces for ss in t.span_sets)
    router = Router().add("/api/search", response(200, ""))
    assert q(router).search().traces == ()


def test_search_mapping_edge_cases() -> None:
    body = {
        "traces": [
            {
                "traceID": "ABC",
                "spanSets": [{"spans": [{"spanID": "1", "name": "n"}, "junk"]}, "junk"],
                "serviceStats": {"svc": {"spanCount": 2}, "bad": "junk"},
            },
            "junk",
        ],
        "metrics": {"a": "12", "b": "1.5", "c": "x", "d": True, "e": 3, "f": "inf"},
    }
    result = q(Router().add("/api/search", response(200, body))).search()
    hit = result.traces[0]
    assert hit.trace_id == "0" * 29 + "abc"
    assert hit.duration_micros == 0 and hit.root_service_name is None
    assert hit.span_sets[0].spans[0].span_id == "0" * 15 + "1"
    assert hit.service_stats == {"svc": sdk.ServiceStats(2, 0)}
    assert result.metrics == {"a": 12, "b": 1.5, "c": 0, "d": 1, "e": 3, "f": 0}


def test_search_errors() -> None:
    router = Router().fixture("search.invalid-traceql-400")
    with pytest.raises(sdk.ValidationError) as info:
        q(router).search("{ bad")
    assert "unknown identifier" in str(info.value) and info.value.http_status == 400
    with pytest.raises(sdk.AuthenticationError):
        q(Router().fixture("search.mt-no-tenant-401")).search()
    for kw in ({"limit": 0}, {"spss": -1}, {"limit": True}, {"limit": "5"}):
        with pytest.raises(sdk.ValidationError):
            q(Router()).search(**kw)  # type: ignore[arg-type]
    with pytest.raises(sdk.UnsupportedError):
        q(Router()).search()  # no route → 404 → unsupported


def test_search_traces() -> None:
    router = Router().fixture("searchTraces.mapped-traceql")
    query = sdk.TraceSearchQuery(
        service="grow-sdk-verify-179503498127460-api",
        operation="GET /orders",
        tags={"grow.run": "179503498127460"},
        min_duration_micros=400_000,
        max_duration_micros=500_000,
        start=START,
        end=END,
    )
    summaries = q(router).search_traces(query)
    assert router.params()["q"] == (
        '{ resource.service.name = "grow-sdk-verify-179503498127460-api" && name = "GET /orders"'
        ' && .grow.run = "179503498127460" && trace:duration >= 400000us'
        " && trace:duration <= 500000us }"
    )
    assert router.params()["limit"] == "20"
    assert len(summaries) == 1
    s = summaries[0]
    assert s.trace_id == SEED and s.root_name == "GET /orders"
    assert s.duration_micros == 450_000 and s.span_count >= 3
    q(Router().add("/api/search", response(200, {"traces": []}))).search_traces()
    for bad in (-1, float("inf"), "1"):
        with pytest.raises(sdk.ValidationError):
            sdk.search_traces_traceql(sdk.TraceSearchQuery(min_duration_micros=bad))  # type: ignore[arg-type]


# --------------------------------------------------------------------------- tags


def test_search_tags() -> None:
    router = Router().fixture("searchTags.v2-all-scopes")
    tags = q(router).search_tags(start=START, end=END)
    assert "span" in tags and "intrinsic" in tags and "grow weird.key" in tags["span"]
    assert router.params() == {"start": "1795034947", "end": "1795035067"}
    router = Router().fixture("searchTags.v2-filtered-q")
    q(router).search_tags(scope="span", query=sdk.TraceQL.span({"resource.service.name": "x"}))
    assert router.params()["scope"] == "span"
    assert router.params()["q"] == '{ resource.service.name = "x" }'
    with pytest.raises(sdk.ValidationError):
        q(Router()).search_tags(scope="nope")  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError):
        q(Router()).search_tags(query="{ bad")
    with pytest.raises(sdk.ValidationError):
        q(Router().fixture("searchTags.v2-scope-invalid-400", "/api/v2/search/tags")).search_tags()


def test_search_tag_values() -> None:
    router = Router().fixture("searchTagValues.v2-int-typed")
    values = q(router).search_tag_values(
        "span.http.response.status_code",
        query='{ resource.service.name = "grow-sdk-verify-179503498127460-api" }',
        start=START,
        end=END,
    )
    assert values == [sdk.TagValue("int", "200"), sdk.TagValue("int", "503")]
    router = Router().fixture("searchTagValues.v2-quoted-tag-path")
    path = load("searchTagValues.v2-quoted-tag-path")["request"]["path"]
    q(router).search_tag_values("span.grow weird.key")
    assert (
        raw_path(router.last()) == path == ("/api/v2/search/tag/span.%22grow%20weird.key%22/values")
    )
    router = Router().fixture("searchTagValues.v1-service-name", "/api/v2/search/tag/span.x/values")
    plain = q(router).search_tag_values("span.x")
    assert plain[0] == sdk.TagValue("string", "grow-sdk-seed-api")
    router = Router().add("/api/v2/search/tag/name/values", response(200, {"tagValues": [{}, 3]}))
    assert q(router).search_tag_values("name") == [sdk.TagValue("string", "")]
    with pytest.raises(sdk.ValidationError):
        q(Router()).search_tag_values("service.name")
    with pytest.raises(sdk.ValidationError):
        q(Router()).search_tag_values("name", query="bad")


def test_list_services_and_operations() -> None:
    router = Router().fixture("searchTagValues.v2-service-name")
    services = q(router).list_services(start=START)
    assert services == sorted(set(services)) and services
    router = Router().fixture("searchTagValues.v2-name-filtered")
    ops = q(router).list_operations("grow-sdk-verify-179503498127460-api")
    assert ops == sorted(ops) and "GET /orders" in ops
    assert router.params()["q"] == (
        '{ resource.service.name = "grow-sdk-verify-179503498127460-api" }'
    )
    with pytest.raises(sdk.ValidationError):
        q(Router()).list_operations("  ")


def test_get_dependencies_and_wait() -> None:
    with pytest.raises(sdk.UnsupportedError):
        q(Router()).get_dependencies()
    router = Router().fixture("getTrace.v2-ok")
    trace = q(router).wait_for_trace(SEED, timeout_s=2, interval_s=0.01, min_spans=4)
    assert len(trace.spans) == 4
    with pytest.raises(sdk.TimeoutError):
        q(Router().fixture("getTrace.v2-not-found", f"/api/v2/traces/{SEED}")).wait_for_trace(
            SEED, timeout_s=0.05, interval_s=0.01
        )


# --------------------------------------------------------------------------- metrics


def test_traceql_metrics() -> None:
    fixture = load("traceQLMetrics.quantile-exemplars")
    router = Router().fixture("traceQLMetrics.quantile-exemplars")
    end = dt.datetime.fromtimestamp(1795035043, dt.timezone.utc)
    result = q(router).traceql_metrics(
        fixture["request"]["query"]["q"], start=START, end=end, step_s=5, exemplars=5
    )
    assert router.params() == fixture["request"]["query"]
    series = result.series[0]
    assert series.labels == {"p": 0.9}
    assert all(isinstance(s.value, float) for s in series.samples)
    assert any(s.value == 0 for s in series.samples)
    assert series.exemplars and "trace:id" in series.exemplars[0].labels
    assert result.step_ms == 5000


def test_traceql_metrics_variants() -> None:
    router = Router().fixture("traceQLMetrics.rate-since")
    q(router).traceql_metrics("{ } | rate()", since_s=dt.timedelta(minutes=15), step_s=1.5)
    assert router.params()["since"] == "900s" and router.params()["step"] == "1500ms"
    body = {
        "series": [
            {"samples": [{"timestampMs": "x", "value": "2"}, "junk"], "exemplars": [{}]},
            "junk",
        ]
    }
    router = Router().add("/api/metrics/query_range", response(200, body))
    result = q(router).traceql_metrics(sdk.TraceQL.span())
    assert result.series[0].samples == (sdk.MetricSample(0, 2.0),)
    assert result.series[0].exemplars == (sdk.MetricExemplar({}, 0.0, 0),)
    assert result.step_ms is None
    with pytest.raises(sdk.ValidationError):
        q(Router()).traceql_metrics("  ")
    for kw in ({"since_s": 0}, {"step_s": float("nan")}, {"since_s": True}, {"exemplars": 0}):
        with pytest.raises(sdk.ValidationError):
            q(Router()).traceql_metrics("{ } | rate()", **kw)  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError) as info:
        q(Router().fixture("traceQLMetrics.no-pipeline-400")).traceql_metrics("{ }")
    assert "not a metrics query" in str(info.value)
    with pytest.raises(sdk.UnsupportedError):
        q(Router()).traceql_metrics("{ } | rate()")


def test_go_duration_and_error_mapping() -> None:
    assert sdk.go_duration(0.0001, "x") == "1ms"
    assert sdk.go_duration(2, "x") == "2s"
    err = sdk.NotFoundError("op: not found (HTTP 404)", http_status=404)
    assert sdk.map_tempo_error(err, "op") is err
    assert isinstance(sdk.map_tempo_error(err, "op", True), sdk.UnsupportedError)


def test_owned_query_client_and_from_env() -> None:
    router = Router().fixture("echo.ok").fixture("ready.ok").fixture("buildInfo.ok")
    with sdk.TempoQueryClient(make_config(BASE), http_transport=router.transport()) as query:
        assert query.echo() == "echo"
        assert query.ready() is True
        assert query.build_info().go_version == "go1.27.3"
    assert query.client._transport.closed
    owned = sdk.TempoQueryClient.from_env({"TEMPO_URL": "http://t:3200"})
    assert owned.client.config.url == "http://t:3200"
    owned.close()
    assert isinstance(owned, sdk.TraceQueryClient)


# --------------------------------------------------------------------------- async


async def test_async_query_surface() -> None:
    router = (
        Router()
        .fixture("getTrace.v2-ok")
        .fixture("search.builder-duration-select")
        .fixture("searchTags.v2-all-scopes")
        .fixture("searchTagValues.v2-service-name")
        .fixture("searchTagValues.v2-name-filtered")
        .fixture("traceQLMetrics.count-over-time")
        .fixture("echo.ok")
        .fixture("ready.ok")
        .fixture("buildInfo.ok")
    )
    c = async_client(router, tenant_id="tenant-a")
    query = c.query
    trace = await query.get_trace(SEED, start=START, end=END)
    assert trace is not None and len(trace.spans) == 4
    assert (await query.wait_for_trace(SEED, timeout_s=1, interval_s=0.01)).trace_id == SEED
    assert len((await query.search("{ }", limit=20, spss=10)).traces) == 1
    assert len(await query.search_traces(sdk.TraceSearchQuery(service="x"))) == 1
    assert await query.search_traces() is not None
    assert "span" in await query.search_tags(scope="span", query="{ }")
    assert await query.search_tag_values("resource.service.name")
    assert await query.list_services()
    router.fixture("searchTagValues.v2-name-filtered", "/api/v2/search/tag/name/values")
    assert "GET /orders" in await query.list_operations("svc")
    metrics = await query.traceql_metrics("{ } | count_over_time()", since_s=60)
    assert metrics.series
    assert await query.echo() == "echo"
    assert await query.ready() is True
    assert (await query.build_info()).version == "3.1.1"
    with pytest.raises(sdk.UnsupportedError):
        await query.get_dependencies()
    assert router.last().headers["X-Scope-OrgID"] == "tenant-a"
    assert isinstance(query, sdk.AsyncTraceQueryClient)
    await query.close()  # not owned
    await c.aclose()


async def test_async_get_trace_fallback_and_errors() -> None:
    router = Router().fixture("getTrace.v1-ok", f"/api/traces/{SEED}")
    c = async_client(router)
    assert (await c.query.get_trace(SEED)) is not None
    assert await async_client(Router()).query.get_trace(SEED) is None
    with pytest.raises(sdk.AuthenticationError):
        await async_client(Router().fixture("search.mt-no-tenant-401")).query.search()
    async with sdk.AsyncTempoQueryClient(
        make_config(BASE), http_transport=Router().transport()
    ) as owned:
        assert owned.client.config.url == BASE
    owned2 = sdk.AsyncTempoQueryClient.from_env({"TEMPO_URL": "http://t:3200"})
    await owned2.aclose()
