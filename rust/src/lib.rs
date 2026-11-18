// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-grafana-tempo
//!
//! Rust SDK for [Grafana Tempo](https://grow-codes-packages.github.io/grafana-tempo/) by Grow Codes.
//!
//! OpenTelemetry tracing that exports to Grafana Tempo over OTLP ([`init_tracing`]) and a
//! client for the Tempo HTTP query API ([`TempoClient`], [`TempoQueryClient`]: trace by id,
//! TraceQL search, tag names and values, TraceQL metrics), plus a safe [`TraceQL`] builder.
//!
//! ```no_run
//! # async fn demo() -> grow_grafana_tempo::Result<()> {
//! use grow_grafana_tempo::tracing::{TraceQueryClient, TracingConfig};
//! use grow_grafana_tempo::{init_tracing, SearchRequest, TempoClient, TraceQL};
//!
//! // Export spans (OTLP/HTTP to http://127.0.0.1:4318 unless the environment says otherwise).
//! let handle = init_tracing(TracingConfig::new("checkout"))?;
//!
//! // Query Tempo (GROW_GRAFANA_TEMPO_URL / TEMPO_URL, GROW_GRAFANA_TEMPO_TENANT_ID, ...).
//! let client = TempoClient::from_env()?;
//! let trace = client.query().get_trace("5b8efff798038103d269b633813fc60c").await?;
//! let q = TraceQL::span([("resource.service.name", "api")])
//!     .and("duration", ">", "200ms")
//!     .build()?;
//! let found = client.query().search(&SearchRequest::new(q).limit(5)).await?;
//! println!("{trace:?} {}", found.traces.len());
//! client.close().await;
//! handle.shutdown().await?;
//! # Ok(())
//! # }
//! ```
//!
//! On a multi-tenant Tempo every query and export needs a tenant
//! ([`TempoConfigBuilder::tenant_id`], [`with_tenant`]), sent as `X-Scope-OrgID`. Search, tags
//! and metrics see new data about 30 s after ingest; trace by id sees it at once.
//!
//! Grafana Tempo is a trademark of Grafana Labs. This crate is not affiliated with or endorsed
//! by Grafana Labs.
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;
pub mod otlp_json;
mod query;
mod setup;
mod traceql;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::{validate_tenant_id, DEFAULT_URL, TENANT_HEADER};

pub use crate::query::{
    search_traces_traceql, BuildInfo, GetTraceOptions, MetricExemplar, MetricSample, MetricSeries,
    MetricsRange, SearchMetrics, SearchRequest, SearchTagValuesOptions, SearchTagsOptions,
    ServiceStats, TagScope, TagValue, TempoQueryClient, TempoSearchResult, TempoSearchSpan,
    TempoSearchTrace, TempoSpanSet, TraceQLMetricsResult, TAG_SCOPES,
};

pub use crate::setup::{
    init_tracing, otlp_http_traces_url, parse_key_value_list, tempo_exporter, tempo_tracing_config,
    with_tenant, DEFAULT_OTLP_GRPC_ENDPOINT, DEFAULT_OTLP_HTTP_ENDPOINT, DEFAULT_TRACING_PROTOCOL,
};

pub use crate::traceql::{
    check_traceql, escape_traceql_string, traceql_duration, traceql_field, TraceQL, TraceQLValue,
    SPANSET_OPERATORS, TRACEQL_OPERATORS, TRACEQL_SCOPES,
};

pub use crate::client::TempoClient;

pub use crate::config::TempoConfig;

pub use crate::config::TempoConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type TempoError = Error;
