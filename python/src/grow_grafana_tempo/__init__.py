"""Grow Codes SDK for Grafana Tempo.

>>> from grow_grafana_tempo import TempoClient, TraceQL, init_tracing
>>> client = TempoClient.from_env()  # doctest: +SKIP
>>> client.query.search(TraceQL.span({"resource.service.name": "api"}))  # doctest: +SKIP
"""

from . import _core
from ._core import *  # noqa: F403
from ._version import __version__
from .client import AsyncTempoClient, TempoClient
from .config import DEFAULT_URL, TENANT_HEADER, TempoConfig, validate_tenant_id
from .exporter import (
    DEFAULT_OTLP_GRPC_ENDPOINT,
    DEFAULT_OTLP_HTTP_ENDPOINT,
    create_tempo_exporter,
    init_tracing,
    otlp_http_traces_url,
    parse_key_value_list,
    tempo_tracing_config,
)
from .otlp_json import (
    from_any_value,
    from_key_values,
    nanos_to_micros,
    otlp_id_to_hex,
    spans_from_resource_spans,
)
from .query import (
    TAG_SCOPES,
    AsyncTempoQueryClient,
    BuildInfo,
    MetricExemplar,
    MetricSample,
    MetricSeries,
    ServiceStats,
    TagScope,
    TagValue,
    TempoQueryClient,
    TempoSearchResult,
    TempoSearchSpan,
    TempoSearchTrace,
    TempoSpanSet,
    TraceQLMetricsResult,
    go_duration,
    map_tempo_error,
    search_traces_traceql,
)
from .traceql import (
    SPANSET_OPERATORS,
    TRACEQL_OPERATORS,
    TRACEQL_SCOPES,
    TraceQL,
    TraceQLOperator,
    TraceQLValue,
    check_traceql,
    traceql_text,
)

__all__ = [
    "__version__",
    # client + config
    "TempoClient",
    "AsyncTempoClient",
    "TempoConfig",
    "DEFAULT_URL",
    "TENANT_HEADER",
    "validate_tenant_id",
    # query
    "TempoQueryClient",
    "AsyncTempoQueryClient",
    "BuildInfo",
    "TagScope",
    "TAG_SCOPES",
    "TagValue",
    "TempoSearchResult",
    "TempoSearchTrace",
    "TempoSpanSet",
    "TempoSearchSpan",
    "ServiceStats",
    "TraceQLMetricsResult",
    "MetricSeries",
    "MetricSample",
    "MetricExemplar",
    "go_duration",
    "map_tempo_error",
    "search_traces_traceql",
    # TraceQL
    "TraceQL",
    "TraceQLOperator",
    "TraceQLValue",
    "TRACEQL_OPERATORS",
    "TRACEQL_SCOPES",
    "SPANSET_OPERATORS",
    "check_traceql",
    "traceql_text",
    # tracing setup
    "init_tracing",
    "tempo_tracing_config",
    "create_tempo_exporter",
    "otlp_http_traces_url",
    "parse_key_value_list",
    "DEFAULT_OTLP_HTTP_ENDPOINT",
    "DEFAULT_OTLP_GRPC_ENDPOINT",
    # OTLP/JSON helpers
    "from_any_value",
    "from_key_values",
    "nanos_to_micros",
    "otlp_id_to_hex",
    "spans_from_resource_spans",
]
__all__ += _core.__all__

from . import _tracing  # noqa: E402
from ._tracing import *  # noqa: E402, F403

__all__ += _tracing.__all__
