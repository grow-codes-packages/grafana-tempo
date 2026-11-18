"""Tempo query API: :class:`TempoQueryClient` / :class:`AsyncTempoQueryClient`.

Both implement the SPEC §5 ``TraceQueryClient`` (``get_trace``, ``search_traces``,
``list_services``, ``list_operations``, ``get_dependencies``, ``wait_for_trace``) plus Tempo's own
``search`` (TraceQL), ``search_tags``, ``search_tag_values``, ``traceql_metrics``, ``echo``,
``ready`` and ``build_info``.

Tempo returns a trace by id immediately after ingest, but search, tag names/values and TraceQL
metrics only see new data about 30 seconds later.
"""

from __future__ import annotations

import dataclasses
import datetime as _dt
import math
from collections.abc import Mapping, Sequence
from types import TracebackType
from typing import TYPE_CHECKING, Any, Literal, get_args
from urllib.parse import quote

from ._core import (
    AuthenticationError,
    CancelToken,
    NotFoundError,
    Request,
    Response,
    ServiceError,
    UnsupportedError,
    ValidationError,
    to_seconds,
)
from ._tracing import (
    AsyncBaseTraceQueryClient,
    BaseTraceQueryClient,
    DependencyLink,
    Trace,
    TraceSearchQuery,
    TraceSummary,
    normalize_trace_id,
)
from .config import TempoConfig
from .otlp_json import from_key_values, nanos_to_micros, spans_from_resource_spans
from .traceql import TraceQL, check_traceql, traceql_text

if TYPE_CHECKING:
    import httpx

    from .client import AsyncTempoClient, TempoClient

__all__ = [
    "TAG_SCOPES",
    "AsyncTempoQueryClient",
    "BuildInfo",
    "MetricExemplar",
    "MetricSample",
    "MetricSeries",
    "ServiceStats",
    "TagScope",
    "TagValue",
    "TempoQueryClient",
    "TempoSearchResult",
    "TempoSearchSpan",
    "TempoSearchTrace",
    "TempoSpanSet",
    "TraceQLMetricsResult",
    "go_duration",
    "map_tempo_error",
    "search_traces_traceql",
]

TagScope = Literal["resource", "span", "intrinsic", "event", "link", "instrumentation"]
#: Attribute scopes accepted by ``search_tags(scope=…)``.
TAG_SCOPES: tuple[str, ...] = get_args(TagScope)

_TimeLike = _dt.datetime


# --------------------------------------------------------------------------- models


@dataclasses.dataclass(frozen=True)
class BuildInfo:
    """Build information from ``/api/status/buildinfo`` (empty strings for unset fields)."""

    version: str = ""
    revision: str = ""
    branch: str = ""
    build_user: str = ""
    build_date: str = ""
    go_version: str = ""


@dataclasses.dataclass(frozen=True)
class TagValue:
    """One tag value. ``value`` is always text; ``type`` tells how to read it (``string``,
    ``int``, ``keyword`` …)."""

    type: str
    value: str


@dataclasses.dataclass(frozen=True)
class TempoSearchSpan:
    """One span of a search spanset. ``name`` is set only when the query selects it."""

    span_id: str
    start_micros: int
    duration_micros: int
    attributes: Mapping[str, Any] = dataclasses.field(default_factory=dict)
    name: str | None = None


@dataclasses.dataclass(frozen=True)
class TempoSpanSet:
    """Spans of one trace that matched; ``matched`` may exceed ``len(spans)`` (see ``spss``)."""

    spans: Sequence[TempoSearchSpan] = ()
    matched: int = 0


@dataclasses.dataclass(frozen=True)
class ServiceStats:
    """Spans and errors of one service in a search hit."""

    span_count: int = 0
    error_count: int = 0


@dataclasses.dataclass(frozen=True)
class TempoSearchTrace:
    """One trace found by ``search``. ``trace_id`` is padded back to 32 hex characters;
    ``duration_micros`` has millisecond precision (Tempo reports whole milliseconds)."""

    trace_id: str
    start_micros: int
    duration_micros: int
    span_sets: Sequence[TempoSpanSet] = ()
    service_stats: Mapping[str, ServiceStats] = dataclasses.field(default_factory=dict)
    root_service_name: str | None = None
    root_trace_name: str | None = None


@dataclasses.dataclass(frozen=True)
class TempoSearchResult:
    """Result of ``search``: traces plus query statistics (``inspectedBytes`` …) as numbers."""

    traces: Sequence[TempoSearchTrace] = ()
    metrics: Mapping[str, int | float] = dataclasses.field(default_factory=dict)


@dataclasses.dataclass(frozen=True)
class MetricSample:
    """One sample of a TraceQL metrics series (Tempo omits zero values; they read as 0)."""

    timestamp_ms: int
    value: float


@dataclasses.dataclass(frozen=True)
class MetricExemplar:
    """An exemplar span behind a series (labels include ``trace:id``)."""

    labels: Mapping[str, Any]
    value: float
    timestamp_ms: int


@dataclasses.dataclass(frozen=True)
class MetricSeries:
    """One series: labels (``__name__``, ``by()`` attributes, quantile ``p``), samples,
    exemplars."""

    labels: Mapping[str, Any] = dataclasses.field(default_factory=dict)
    samples: Sequence[MetricSample] = ()
    exemplars: Sequence[MetricExemplar] = ()


@dataclasses.dataclass(frozen=True)
class TraceQLMetricsResult:
    """Result of ``traceql_metrics``; ``step_ms`` is the step Tempo used, when reported."""

    series: Sequence[MetricSeries] = ()
    step_ms: int | None = None


# --------------------------------------------------------------------------- helpers


def map_tempo_error(
    err: ServiceError, operation: str, unsupported_404: bool = False
) -> ServiceError:
    """Tempo-specific mapping on top of the SPEC status mapping.

    A 401 ``no org id`` (multi-tenant Tempo without ``X-Scope-OrgID``) becomes an
    :class:`AuthenticationError` that names the missing tenant header; with ``unsupported_404`` a
    404 on a feature route becomes :class:`UnsupportedError`.
    """
    if isinstance(err, AuthenticationError) and "no org id" in err.message.lower():
        return AuthenticationError(
            f"{operation}: tenant required (X-Scope-OrgID) (HTTP 401)",
            http_status=401,
            request_id=err.request_id,
            cause=err,
        )
    if unsupported_404 and isinstance(err, NotFoundError):
        return UnsupportedError(
            f"{operation}: endpoint not available on this Tempo (HTTP 404)",
            http_status=404,
            request_id=err.request_id,
            cause=err,
        )
    return err


def go_duration(value: float | _dt.timedelta, name: str) -> str:
    """Format a positive duration (seconds or ``timedelta``) as a Go duration (``5s``,
    ``1500ms``); :class:`ValidationError` otherwise."""
    if isinstance(value, bool):
        raise ValidationError(f"{name} must be a duration")
    seconds = to_seconds(value)
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValidationError(f"{name} must be positive")
    millis = max(1, round(seconds * 1000))
    return f"{millis // 1000}s" if millis % 1000 == 0 else f"{millis}ms"


def _epoch(value: Any, name: str, *, ceil: bool) -> int:
    if not isinstance(value, _dt.datetime):
        raise ValidationError(f"{name} must be a datetime")
    if value.tzinfo is None:
        value = value.replace(tzinfo=_dt.timezone.utc)
    seconds = value.timestamp()
    return int(math.ceil(seconds) if ceil else math.floor(seconds))


def _range(start: _TimeLike | None, end: _TimeLike | None) -> dict[str, Any]:
    params: dict[str, Any] = {}
    if start is not None:
        params["start"] = _epoch(start, "start", ceil=False)
    if end is not None:
        params["end"] = _epoch(end, "end", ceil=True)
    return params


def _positive_int(value: Any, name: str) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValidationError(f"{name} must be a positive integer")
    return value


def _int(value: Any) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _number(value: Any) -> int | float:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, (int, float)):
        return value
    try:
        return int(str(value))
    except ValueError:
        try:
            number = float(str(value))
        except ValueError:
            return 0
        return number if math.isfinite(number) else 0


def _float(value: Any) -> float:
    number = _number(value) if value is not None else 0
    return float(number)


def _pad(value: Any, width: int) -> str:
    return str(value or "").lower().rjust(width, "0")


def _json(resp: Response) -> Mapping[str, Any]:
    if not resp.content.strip():
        return {}
    data = resp.json()
    return data if isinstance(data, Mapping) else {}


def _trace_from(trace_id: str, resource_spans: Any) -> Trace | None:
    spans = spans_from_resource_spans(resource_spans if isinstance(resource_spans, list) else [])
    return Trace(trace_id, spans) if spans else None


def _search_span(raw: Mapping[str, Any]) -> TempoSearchSpan:
    name = raw.get("name")
    return TempoSearchSpan(
        span_id=_pad(raw.get("spanID"), 16),
        start_micros=nanos_to_micros(raw.get("startTimeUnixNano")),
        duration_micros=nanos_to_micros(raw.get("durationNanos")),
        attributes=from_key_values(raw.get("attributes")),
        name=name if isinstance(name, str) else None,
    )


def _search_trace(raw: Mapping[str, Any]) -> TempoSearchTrace:
    sets = raw.get("spanSets")
    if sets is None:
        sets = [raw["spanSet"]] if isinstance(raw.get("spanSet"), Mapping) else []
    stats = {
        str(svc): ServiceStats(_int(s.get("spanCount", 0)), _int(s.get("errorCount", 0)))
        for svc, s in (raw.get("serviceStats") or {}).items()
        if isinstance(s, Mapping)
    }
    root_service = raw.get("rootServiceName")
    root_name = raw.get("rootTraceName")
    return TempoSearchTrace(
        trace_id=_pad(raw.get("traceID"), 32),
        start_micros=nanos_to_micros(raw.get("startTimeUnixNano")),
        duration_micros=_int(raw.get("durationMs", 0)) * 1000,
        span_sets=tuple(
            TempoSpanSet(
                tuple(_search_span(sp) for sp in s.get("spans") or [] if isinstance(sp, Mapping)),
                _int(s.get("matched", 0)),
            )
            for s in sets
            if isinstance(s, Mapping)
        ),
        service_stats=stats,
        root_service_name=root_service if isinstance(root_service, str) else None,
        root_trace_name=root_name if isinstance(root_name, str) else None,
    )


def _search_result(data: Mapping[str, Any]) -> TempoSearchResult:
    traces = tuple(_search_trace(t) for t in data.get("traces") or [] if isinstance(t, Mapping))
    metrics = {str(k): _number(v) for k, v in (data.get("metrics") or {}).items()}
    return TempoSearchResult(traces, metrics)


def _summary(trace: TempoSearchTrace) -> TraceSummary:
    return TraceSummary(
        trace_id=normalize_trace_id(trace.trace_id),
        start_micros=trace.start_micros,
        duration_micros=trace.duration_micros,
        span_count=sum(s.span_count for s in trace.service_stats.values()),
        root_service_name=trace.root_service_name,
        root_name=trace.root_trace_name,
    )


def _micros(value: Any, name: str) -> str:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
        raise ValidationError(f"search_traces: {name} must be a non-negative number")
    if not math.isfinite(value):
        raise ValidationError(f"search_traces: {name} must be finite")
    return f"{round(value)}us"


def search_traces_traceql(query: TraceSearchQuery) -> TraceQL:
    """The TraceQL ``search_traces`` sends: one spanset with ``resource.service.name``,
    ``name``, unscoped ``.tag`` filters and ``trace:duration`` bounds in microseconds."""
    q = TraceQL.span()
    if query.service is not None:
        q = q.and_("resource.service.name", "=", query.service)
    if query.operation is not None:
        q = q.and_("name", "=", query.operation)
    for key, value in query.tags.items():
        q = q.and_(f".{key}", "=", value)
    if query.min_duration_micros is not None:
        q = q.and_(
            "trace:duration", ">=", _micros(query.min_duration_micros, "min_duration_micros")
        )
    if query.max_duration_micros is not None:
        q = q.and_(
            "trace:duration", "<=", _micros(query.max_duration_micros, "max_duration_micros")
        )
    return q


def _tag_values(data: Mapping[str, Any]) -> list[TagValue]:
    out: list[TagValue] = []
    for item in data.get("tagValues") or []:
        if isinstance(item, str):
            out.append(TagValue("string", item))
        elif isinstance(item, Mapping):
            out.append(TagValue(str(item.get("type") or "string"), str(item.get("value") or "")))
    return out


def _sorted_values(values: Sequence[TagValue]) -> list[str]:
    return sorted({v.value for v in values})


def _labels(items: Any) -> dict[str, Any]:
    return from_key_values(items)


def _metrics_result(data: Mapping[str, Any]) -> TraceQLMetricsResult:
    series = []
    for raw in data.get("series") or []:
        if not isinstance(raw, Mapping):
            continue
        series.append(
            MetricSeries(
                labels=_labels(raw.get("labels")),
                samples=tuple(
                    MetricSample(_int(p.get("timestampMs", 0)), _float(p.get("value")))
                    for p in raw.get("samples") or []
                    if isinstance(p, Mapping)
                ),
                exemplars=tuple(
                    MetricExemplar(
                        _labels(e.get("labels")),
                        _float(e.get("value")),
                        _int(e.get("timestampMs", 0)),
                    )
                    for e in raw.get("exemplars") or []
                    if isinstance(e, Mapping)
                ),
            )
        )
    step = data.get("step")
    step_ms = round(_float(step) / 1e6) if step is not None else None
    return TraceQLMetricsResult(tuple(series), step_ms)


def _build_info(data: Mapping[str, Any]) -> BuildInfo:
    def text(key: str) -> str:
        value = data.get(key)
        return value if isinstance(value, str) else ""

    return BuildInfo(
        version=text("version"),
        revision=text("revision"),
        branch=text("branch"),
        build_user=text("buildUser"),
        build_date=text("buildDate"),
        go_version=text("goVersion"),
    )


# ---- request plans (shared by the sync and async clients)


def _get_trace_plan(
    trace_id: str, start: _TimeLike | None, end: _TimeLike | None
) -> tuple[str, str, dict[str, Any]]:
    tid = normalize_trace_id(trace_id)
    return tid, f"get_trace {tid}", _range(start, end)


def _search_params(
    traceql: str | TraceQL | None,
    start: _TimeLike | None,
    end: _TimeLike | None,
    limit: int | None,
    spss: int | None,
) -> dict[str, Any]:
    q = traceql_text(traceql if traceql is not None else "{}").strip() or "{}"
    return {
        "q": q,
        **_range(start, end),
        "limit": _positive_int(limit, "search: limit"),
        "spss": _positive_int(spss, "search: spss"),
    }


def _tags_params(
    scope: str | None,
    query: str | TraceQL | None,
    start: _TimeLike | None,
    end: _TimeLike | None,
) -> dict[str, Any]:
    if scope is not None and scope not in TAG_SCOPES:
        raise ValidationError(f"search_tags: invalid scope {scope!r}")
    params: dict[str, Any] = {"scope": scope, **_range(start, end)}
    if query is not None:
        params["q"] = check_traceql(traceql_text(query), "search_tags")
    return params


def _tag_values_plan(
    tag: str,
    query: str | TraceQL | None,
    start: _TimeLike | None,
    end: _TimeLike | None,
) -> tuple[str, str, dict[str, Any]]:
    field = TraceQL.field(tag)
    params: dict[str, Any] = _range(start, end)
    if query is not None:
        params["q"] = check_traceql(traceql_text(query), "search_tag_values")
    path = f"/api/v2/search/tag/{quote(field, safe='')}/values"
    return path, f"search_tag_values {field}", params


def _operations_query(service: str) -> TraceQL:
    if not isinstance(service, str) or not service.strip():
        raise ValidationError("list_operations: service is required")
    return TraceQL.span({"resource.service.name": service})


def _metrics_params(
    query: str | TraceQL,
    start: _TimeLike | None,
    end: _TimeLike | None,
    since_s: float | _dt.timedelta | None,
    step_s: float | _dt.timedelta | None,
    exemplars: int | None,
) -> dict[str, Any]:
    q = traceql_text(query).strip()
    if not q:
        raise ValidationError("traceql_metrics: query is required")
    params: dict[str, Any] = {
        "q": q,
        **_range(start, end),
        "exemplars": _positive_int(exemplars, "traceql_metrics: exemplars"),
    }
    if since_s is not None:
        params["since"] = go_duration(since_s, "traceql_metrics: since_s")
    if step_s is not None:
        params["step"] = go_duration(step_s, "traceql_metrics: step_s")
    return params


def _ready_from(resp: Response) -> bool:
    return resp.ok and resp.text.strip() == "ready"


def _unsupported_dependencies() -> UnsupportedError:
    return UnsupportedError(
        "get_dependencies: Tempo has no dependency API (service graphs need the "
        "metrics-generator and a Prometheus backend)"
    )


_READY = Request("GET", "/ready", operation="ready", raise_for_status=False, max_retries=0)


# --------------------------------------------------------------------------- sync


class TempoQueryClient(BaseTraceQueryClient):
    """Synchronous client for Tempo's HTTP query API (port 3200).

    Get one from ``client.query``, or build one from a config (it then owns its client and
    :meth:`close` closes it). The tenant (``X-Scope-OrgID``) comes from the config. Every method
    accepts ``cancel=`` (a :class:`CancelToken`).
    """

    def __init__(
        self,
        source: TempoClient | TempoConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        from .client import TempoClient  # noqa: PLC0415 - circular import

        if isinstance(source, TempoClient):
            self._client = source
            self._owned = False
        else:
            self._client = TempoClient(source, http_transport=http_transport)
            self._owned = True

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> TempoQueryClient:
        """An owned query client configured from ``GROW_GRAFANA_TEMPO_*`` / ``TEMPO_*``."""
        return cls(TempoConfig.from_env(env, **overrides))

    @property
    def client(self) -> TempoClient:
        """The underlying :class:`TempoClient`."""
        return self._client

    def _send(
        self,
        req: Request,
        cancel: CancelToken | None,
        *,
        unsupported_404: bool = False,
    ) -> Response:
        headers = {**self._client.config.request_headers(), **(req.headers or {})}
        try:
            return self._client.request(dataclasses.replace(req, headers=headers), cancel=cancel)
        except ServiceError as err:
            raise map_tempo_error(err, req.op, unsupported_404) from None

    def _get(
        self,
        path: str,
        operation: str,
        params: Mapping[str, Any] | None = None,
        cancel: CancelToken | None = None,
        *,
        unsupported_404: bool = False,
    ) -> Mapping[str, Any]:
        req = Request("GET", path, operation=operation, params=params)
        return _json(self._send(req, cancel, unsupported_404=unsupported_404))

    def get_trace(
        self,
        trace_id: str,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        cancel: CancelToken | None = None,
    ) -> Trace | None:
        """Fetch a trace by id: ``GET /api/v2/traces/{id}`` (v1 ``/api/traces/{id}`` when the
        v2 route is missing). ``None`` when the trace does not exist or belongs to another tenant.
        ``start``/``end`` narrow the backend blocks searched (recent data is returned anyway).
        :class:`ValidationError` for an invalid id."""
        tid, op, params = _get_trace_plan(trace_id, start, end)
        try:
            data = self._get(f"/api/v2/traces/{tid}", op, params, cancel)
        except NotFoundError:
            pass
        else:
            return _trace_from(tid, (data.get("trace") or {}).get("resourceSpans"))
        try:
            v1 = self._get(f"/api/traces/{tid}", op, params, cancel)
        except NotFoundError:
            return None
        return _trace_from(tid, v1.get("batches"))

    def search(
        self,
        traceql: str | TraceQL | None = None,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        limit: int | None = None,
        spss: int | None = None,
        cancel: CancelToken | None = None,
    ) -> TempoSearchResult:
        """TraceQL search: ``GET /api/search?q=…&start&end&limit&spss`` (default query ``{}``).
        Invalid TraceQL → :class:`ValidationError` (HTTP 400)."""
        params = _search_params(traceql, start, end, limit, spss)
        return _search_result(
            self._get("/api/search", "search", params, cancel, unsupported_404=True)
        )

    def search_traces(
        self, query: TraceSearchQuery | None = None, *, cancel: CancelToken | None = None
    ) -> list[TraceSummary]:
        """SPEC §5 search mapped onto one TraceQL spanset (see :func:`search_traces_traceql`);
        durations have millisecond precision."""
        query = query or TraceSearchQuery()
        result = self.search(
            search_traces_traceql(query),
            start=query.start,
            end=query.end,
            limit=query.limit,
            cancel=cancel,
        )
        return [_summary(t) for t in result.traces]

    def search_tags(
        self,
        *,
        scope: TagScope | None = None,
        query: str | TraceQL | None = None,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        cancel: CancelToken | None = None,
    ) -> dict[str, list[str]]:
        """Tag names per scope: ``GET /api/v2/search/tags`` → ``{"resource": [...], …}``.
        Without ``scope`` every scope that has data; ``query`` keeps tags of matching spans."""
        params = _tags_params(scope, query, start, end)
        data = self._get("/api/v2/search/tags", "search_tags", params, cancel, unsupported_404=True)
        return {
            str(s["name"]): [str(t) for t in s.get("tags") or []]
            for s in data.get("scopes") or []
            if isinstance(s, Mapping) and isinstance(s.get("name"), str)
        }

    def search_tag_values(
        self,
        tag: str,
        *,
        query: str | TraceQL | None = None,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        cancel: CancelToken | None = None,
    ) -> list[TagValue]:
        """Values of one tag: ``GET /api/v2/search/tag/{tag}/values``. ``tag`` is a TraceQL
        identifier (``resource.service.name``, ``span.http.response.status_code``, ``name``,
        ``status``, ``span.grow weird.key``); an unscoped name is a :class:`ValidationError`."""
        path, op, params = _tag_values_plan(tag, query, start, end)
        return _tag_values(self._get(path, op, params, cancel, unsupported_404=True))

    def list_services(
        self,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        cancel: CancelToken | None = None,
    ) -> list[str]:
        """Service names (``resource.service.name`` values) seen recently or in the window,
        sorted."""
        values = self.search_tag_values(
            "resource.service.name", start=start, end=end, cancel=cancel
        )
        return _sorted_values(values)

    def list_operations(
        self,
        service: str,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        cancel: CancelToken | None = None,
    ) -> list[str]:
        """Span names of one service (``name`` values filtered by the service), sorted."""
        values = self.search_tag_values(
            "name", query=_operations_query(service), start=start, end=end, cancel=cancel
        )
        return _sorted_values(values)

    def get_dependencies(
        self, *, start: _dt.datetime | None = None, end: _dt.datetime | None = None
    ) -> list[DependencyLink]:
        """Always :class:`UnsupportedError`: Tempo has no dependency API."""
        raise _unsupported_dependencies()

    def traceql_metrics(
        self,
        query: str | TraceQL,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        since_s: float | _dt.timedelta | None = None,
        step_s: float | _dt.timedelta | None = None,
        exemplars: int | None = None,
        cancel: CancelToken | None = None,
    ) -> TraceQLMetricsResult:
        """TraceQL metrics over a range: ``GET /api/metrics/query_range`` (e.g.
        ``{ resource.service.name = "api" } | rate()``). ``since_s`` is a window ending now
        instead of ``start``/``end``. Zero samples omitted by Tempo read as 0. A query without a
        metrics pipeline → :class:`ValidationError`."""
        params = _metrics_params(query, start, end, since_s, step_s, exemplars)
        data = self._get(
            "/api/metrics/query_range", "traceql_metrics", params, cancel, unsupported_404=True
        )
        return _metrics_result(data)

    def echo(self, *, cancel: CancelToken | None = None) -> str:
        """``GET /api/echo`` → ``"echo"`` (no tenant needed)."""
        resp = self._send(Request("GET", "/api/echo", operation="echo"), cancel)
        return resp.text.strip()

    def ready(self, *, cancel: CancelToken | None = None) -> bool:
        """``GET /ready``: ``True`` when Tempo answers ``ready``, ``False`` while it starts (HTTP
        503) or reports another state. Raises on network errors (unlike ``health()``)."""
        return _ready_from(self._send(_READY, cancel))

    def build_info(self, *, cancel: CancelToken | None = None) -> BuildInfo:
        """``GET /api/status/buildinfo`` → version, revision, branch, build user/date, Go."""
        data = self._get("/api/status/buildinfo", "build_info", None, cancel, unsupported_404=True)
        return _build_info(data)

    def close(self) -> None:
        """Close the underlying client when this query client owns it (idempotent)."""
        if self._owned:
            self._client.close()

    def __enter__(self) -> TempoQueryClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()


# --------------------------------------------------------------------------- async


class AsyncTempoQueryClient(AsyncBaseTraceQueryClient):
    """Asynchronous :class:`TempoQueryClient` (same methods, awaited). Cancel a call by
    cancelling its task."""

    def __init__(
        self,
        source: AsyncTempoClient | TempoConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        from .client import AsyncTempoClient  # noqa: PLC0415 - circular import

        if isinstance(source, AsyncTempoClient):
            self._client = source
            self._owned = False
        else:
            self._client = AsyncTempoClient(source, http_transport=http_transport)
            self._owned = True

    @classmethod
    def from_env(
        cls, env: Mapping[str, str] | None = None, **overrides: Any
    ) -> AsyncTempoQueryClient:
        """An owned async query client configured from the environment."""
        return cls(TempoConfig.from_env(env, **overrides))

    @property
    def client(self) -> AsyncTempoClient:
        """The underlying :class:`AsyncTempoClient`."""
        return self._client

    async def _send(self, req: Request, *, unsupported_404: bool = False) -> Response:
        headers = {**self._client.config.request_headers(), **(req.headers or {})}
        try:
            return await self._client.request(dataclasses.replace(req, headers=headers))
        except ServiceError as err:
            raise map_tempo_error(err, req.op, unsupported_404) from None

    async def _get(
        self,
        path: str,
        operation: str,
        params: Mapping[str, Any] | None = None,
        *,
        unsupported_404: bool = False,
    ) -> Mapping[str, Any]:
        req = Request("GET", path, operation=operation, params=params)
        return _json(await self._send(req, unsupported_404=unsupported_404))

    async def get_trace(
        self,
        trace_id: str,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
    ) -> Trace | None:
        """See :meth:`TempoQueryClient.get_trace`."""
        tid, op, params = _get_trace_plan(trace_id, start, end)
        try:
            data = await self._get(f"/api/v2/traces/{tid}", op, params)
        except NotFoundError:
            pass
        else:
            return _trace_from(tid, (data.get("trace") or {}).get("resourceSpans"))
        try:
            v1 = await self._get(f"/api/traces/{tid}", op, params)
        except NotFoundError:
            return None
        return _trace_from(tid, v1.get("batches"))

    async def search(
        self,
        traceql: str | TraceQL | None = None,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        limit: int | None = None,
        spss: int | None = None,
    ) -> TempoSearchResult:
        """See :meth:`TempoQueryClient.search`."""
        params = _search_params(traceql, start, end, limit, spss)
        return _search_result(
            await self._get("/api/search", "search", params, unsupported_404=True)
        )

    async def search_traces(self, query: TraceSearchQuery | None = None) -> list[TraceSummary]:
        """See :meth:`TempoQueryClient.search_traces`."""
        query = query or TraceSearchQuery()
        result = await self.search(
            search_traces_traceql(query), start=query.start, end=query.end, limit=query.limit
        )
        return [_summary(t) for t in result.traces]

    async def search_tags(
        self,
        *,
        scope: TagScope | None = None,
        query: str | TraceQL | None = None,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
    ) -> dict[str, list[str]]:
        """See :meth:`TempoQueryClient.search_tags`."""
        params = _tags_params(scope, query, start, end)
        data = await self._get("/api/v2/search/tags", "search_tags", params, unsupported_404=True)
        return {
            str(s["name"]): [str(t) for t in s.get("tags") or []]
            for s in data.get("scopes") or []
            if isinstance(s, Mapping) and isinstance(s.get("name"), str)
        }

    async def search_tag_values(
        self,
        tag: str,
        *,
        query: str | TraceQL | None = None,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
    ) -> list[TagValue]:
        """See :meth:`TempoQueryClient.search_tag_values`."""
        path, op, params = _tag_values_plan(tag, query, start, end)
        return _tag_values(await self._get(path, op, params, unsupported_404=True))

    async def list_services(
        self, *, start: _TimeLike | None = None, end: _TimeLike | None = None
    ) -> list[str]:
        """See :meth:`TempoQueryClient.list_services`."""
        values = await self.search_tag_values("resource.service.name", start=start, end=end)
        return _sorted_values(values)

    async def list_operations(
        self, service: str, *, start: _TimeLike | None = None, end: _TimeLike | None = None
    ) -> list[str]:
        """See :meth:`TempoQueryClient.list_operations`."""
        values = await self.search_tag_values(
            "name", query=_operations_query(service), start=start, end=end
        )
        return _sorted_values(values)

    async def get_dependencies(
        self, *, start: _dt.datetime | None = None, end: _dt.datetime | None = None
    ) -> list[DependencyLink]:
        """Always :class:`UnsupportedError`: Tempo has no dependency API."""
        raise _unsupported_dependencies()

    async def traceql_metrics(
        self,
        query: str | TraceQL,
        *,
        start: _TimeLike | None = None,
        end: _TimeLike | None = None,
        since_s: float | _dt.timedelta | None = None,
        step_s: float | _dt.timedelta | None = None,
        exemplars: int | None = None,
    ) -> TraceQLMetricsResult:
        """See :meth:`TempoQueryClient.traceql_metrics`."""
        params = _metrics_params(query, start, end, since_s, step_s, exemplars)
        data = await self._get(
            "/api/metrics/query_range", "traceql_metrics", params, unsupported_404=True
        )
        return _metrics_result(data)

    async def echo(self) -> str:
        """See :meth:`TempoQueryClient.echo`."""
        resp = await self._send(Request("GET", "/api/echo", operation="echo"))
        return resp.text.strip()

    async def ready(self) -> bool:
        """See :meth:`TempoQueryClient.ready`."""
        return _ready_from(await self._send(_READY))

    async def build_info(self) -> BuildInfo:
        """See :meth:`TempoQueryClient.build_info`."""
        data = await self._get("/api/status/buildinfo", "build_info", None, unsupported_404=True)
        return _build_info(data)

    async def aclose(self) -> None:
        """Close the underlying client when this query client owns it (idempotent)."""
        if self._owned:
            await self._client.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncTempoQueryClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()
