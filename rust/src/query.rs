//! `TempoQueryClient`: the Tempo HTTP query API (trace by id, TraceQL search, tag names and
//! values, TraceQL metrics, probes). It implements the tracing category's
//! [`TraceQueryClient`].

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::str::FromStr;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::client::TempoClient;
use crate::core::{Error, ErrorCode, Result};
use crate::otlp_json::{from_key_values, int_of, nanos_to_micros, trace_from_body};
use crate::traceql::{check_traceql, traceql_duration, traceql_field, TraceQL, TraceQLValue};
use crate::tracing::{
    normalize_trace_id, wait_for_trace, Attributes, DependencyLink, DependencyOptions, Trace,
    TraceQueryClient, TraceSearchQuery, TraceSummary, WaitForTraceOptions,
};

/// Bounds a trace-by-id lookup. Tempo treats the range as a hint for its backend blocks:
/// recent traces are returned even outside it.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct GetTraceOptions {
    /// Sent as Unix seconds (rounded down).
    pub start: Option<SystemTime>,
    /// Sent as Unix seconds (rounded up).
    pub end: Option<SystemTime>,
}

impl GetTraceOptions {
    /// A range from `start` to `end`.
    pub fn range(start: SystemTime, end: SystemTime) -> Self {
        GetTraceOptions {
            start: Some(start),
            end: Some(end),
        }
    }
}

/// A TraceQL search (`GET /api/search`). Unset values mean the server default.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchRequest {
    /// The query, e.g. `{ resource.service.name = "api" }` or [`TraceQL`] output. Empty searches
    /// recent traces without a filter.
    pub traceql: String,
    /// Search start (Unix seconds, rounded down).
    pub start: Option<SystemTime>,
    /// Search end (Unix seconds, rounded up).
    pub end: Option<SystemTime>,
    /// Maximum traces (server default 20; must be positive).
    pub limit: Option<u32>,
    /// Maximum spans per span set (`spss`, server default 3; must be positive).
    pub spss: Option<u32>,
}

impl SearchRequest {
    /// A search for `traceql`.
    pub fn new(traceql: impl Into<String>) -> Self {
        SearchRequest {
            traceql: traceql.into(),
            ..SearchRequest::default()
        }
    }
    /// A search for a built [`TraceQL`] query (`VALIDATION` when it is invalid).
    pub fn from_traceql(query: &TraceQL) -> Result<Self> {
        Ok(SearchRequest::new(query.build()?))
    }
    /// Sets the time range.
    pub fn range(mut self, start: SystemTime, end: SystemTime) -> Self {
        self.start = Some(start);
        self.end = Some(end);
        self
    }
    /// Sets `limit`.
    pub fn limit(mut self, limit: u32) -> Self {
        self.limit = Some(limit);
        self
    }
    /// Sets `spss`.
    pub fn spss(mut self, spss: u32) -> Self {
        self.spss = Some(spss);
        self
    }
}

/// Result of [`TempoQueryClient::search`].
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TempoSearchResult {
    /// Traces found (most recent first in practice).
    pub traces: Vec<TempoSearchTrace>,
    /// Tempo's work report.
    pub metrics: SearchMetrics,
}

/// Tempo's work report for a search.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchMetrics {
    /// Traces inspected.
    pub inspected_traces: i64,
    /// Bytes inspected.
    pub inspected_bytes: i64,
    /// Jobs completed.
    pub completed_jobs: i64,
    /// Jobs in total.
    pub total_jobs: i64,
}

/// One trace found by a search. `duration_micros` has millisecond precision (Tempo reports
/// whole milliseconds).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TempoSearchTrace {
    /// 32 lower-case hex characters (Tempo trims leading zeros; they are restored).
    pub trace_id: String,
    /// Service of the root span (`"<root span not yet received>"` while it is missing).
    pub root_service_name: Option<String>,
    /// Name of the root span.
    pub root_trace_name: Option<String>,
    /// Trace start, microseconds since the epoch.
    pub start_micros: u64,
    /// Trace duration in microseconds.
    pub duration_micros: u64,
    /// Matching span sets.
    pub span_sets: Vec<TempoSpanSet>,
    /// Span and error counts per service.
    pub service_stats: BTreeMap<String, ServiceStats>,
}

/// Matching spans of one trace; `matched` may exceed `spans.len()` (see `spss`).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TempoSpanSet {
    /// At most `spss` spans.
    pub spans: Vec<TempoSearchSpan>,
    /// Full match count.
    pub matched: u64,
}

/// One span of a search span set. `name` is set only when the query selects it
/// (`| select(name)`); `attributes` hold `service.name` plus the selected attributes.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TempoSearchSpan {
    /// 16 lower-case hex characters.
    pub span_id: String,
    /// Span name when selected.
    pub name: Option<String>,
    /// Start, microseconds since the epoch.
    pub start_micros: u64,
    /// Duration in microseconds.
    pub duration_micros: u64,
    /// Selected attributes.
    pub attributes: Attributes,
}

/// Spans and errors of one service in a search hit.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServiceStats {
    /// Spans of the service.
    pub span_count: u64,
    /// Error spans of the service.
    pub error_count: u64,
}

/// TraceQL attribute scope for [`TempoQueryClient::search_tags`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TagScope {
    /// Resource attributes.
    Resource,
    /// Span attributes.
    Span,
    /// Intrinsics (`name`, `status`, `duration`, …).
    Intrinsic,
    /// Event attributes.
    Event,
    /// Link attributes.
    Link,
    /// Instrumentation scope attributes.
    Instrumentation,
}

/// Every tag scope.
pub const TAG_SCOPES: &[TagScope] = &[
    TagScope::Resource,
    TagScope::Span,
    TagScope::Intrinsic,
    TagScope::Event,
    TagScope::Link,
    TagScope::Instrumentation,
];

impl TagScope {
    /// Wire value (`resource`, `span`, …).
    pub fn as_str(self) -> &'static str {
        match self {
            TagScope::Resource => "resource",
            TagScope::Span => "span",
            TagScope::Intrinsic => "intrinsic",
            TagScope::Event => "event",
            TagScope::Link => "link",
            TagScope::Instrumentation => "instrumentation",
        }
    }
}

impl fmt::Display for TagScope {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for TagScope {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self> {
        TAG_SCOPES
            .iter()
            .copied()
            .find(|t| t.as_str() == s.trim())
            .ok_or_else(|| Error::validation(format!("traces.searchTags: unknown scope {s:?}")))
    }
}

/// Narrows [`TempoQueryClient::search_tags`]. Unset values mean "all".
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchTagsOptions {
    /// One scope only.
    pub scope: Option<TagScope>,
    /// TraceQL: only tags present on matching spans (checked client side).
    pub query: Option<String>,
    /// Inspected data start.
    pub start: Option<SystemTime>,
    /// Inspected data end.
    pub end: Option<SystemTime>,
}

impl SearchTagsOptions {
    /// Tags of one scope.
    pub fn scope(scope: TagScope) -> Self {
        SearchTagsOptions {
            scope: Some(scope),
            ..SearchTagsOptions::default()
        }
    }
    /// Sets the TraceQL filter.
    pub fn query(mut self, query: impl Into<String>) -> Self {
        self.query = Some(query.into());
        self
    }
    /// Sets the time range.
    pub fn range(mut self, start: SystemTime, end: SystemTime) -> Self {
        self.start = Some(start);
        self.end = Some(end);
        self
    }
}

/// One tag value. `value` is always text; `type` (`string`, `int`, `keyword`, …) tells how to
/// read it.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct TagValue {
    /// Value type.
    #[serde(rename = "type")]
    pub kind: String,
    /// Value text.
    pub value: String,
}

impl TagValue {
    /// A tag value.
    pub fn new(kind: impl Into<String>, value: impl Into<String>) -> Self {
        TagValue {
            kind: kind.into(),
            value: value.into(),
        }
    }
}

/// Narrows [`TempoQueryClient::search_tag_values`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchTagValuesOptions {
    /// TraceQL: only values on matching spans. Tempo ignores an invalid query, so it is checked
    /// client side (`VALIDATION`).
    pub query: Option<String>,
    /// Inspected data start.
    pub start: Option<SystemTime>,
    /// Inspected data end.
    pub end: Option<SystemTime>,
}

impl SearchTagValuesOptions {
    /// Values on spans matching `query`.
    pub fn query(query: impl Into<String>) -> Self {
        SearchTagValuesOptions {
            query: Some(query.into()),
            ..SearchTagValuesOptions::default()
        }
    }
    /// Sets the time range.
    pub fn range(mut self, start: SystemTime, end: SystemTime) -> Self {
        self.start = Some(start);
        self.end = Some(end);
        self
    }
}

/// Range of a TraceQL metrics query: `start`/`end` or `since` (the last `since` until now).
/// Unset values mean the server default.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct MetricsRange {
    /// Range start.
    pub start: Option<SystemTime>,
    /// Range end.
    pub end: Option<SystemTime>,
    /// Relative range such as 15 minutes (ignored when `start` is set).
    pub since: Option<Duration>,
    /// Sample interval.
    pub step: Option<Duration>,
    /// Maximum exemplars per series (must be positive).
    pub exemplars: Option<u32>,
}

impl MetricsRange {
    /// A range from `start` to `end`.
    pub fn range(start: SystemTime, end: SystemTime) -> Self {
        MetricsRange {
            start: Some(start),
            end: Some(end),
            ..MetricsRange::default()
        }
    }
    /// The last `since` until now.
    pub fn since(since: Duration) -> Self {
        MetricsRange {
            since: Some(since),
            ..MetricsRange::default()
        }
    }
    /// Sets the step.
    pub fn step(mut self, step: Duration) -> Self {
        self.step = Some(step);
        self
    }
    /// Sets the exemplar limit.
    pub fn exemplars(mut self, exemplars: u32) -> Self {
        self.exemplars = Some(exemplars);
        self
    }
}

/// Result of [`TempoQueryClient::traceql_metrics`].
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TraceQLMetricsResult {
    /// Series.
    pub series: Vec<MetricSeries>,
    /// Step the server used, when reported.
    pub step: Option<Duration>,
}

/// One series: typed labels (`__name__`, `by()` attributes, quantile `p`), samples, exemplars.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MetricSeries {
    /// Labels.
    pub labels: Attributes,
    /// Samples.
    pub samples: Vec<MetricSample>,
    /// Exemplars.
    pub exemplars: Vec<MetricExemplar>,
}

/// One sample (Tempo omits zero values; they read as 0).
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MetricSample {
    /// Milliseconds since the epoch.
    pub timestamp_ms: i64,
    /// Value.
    pub value: f64,
}

/// An exemplar span behind a series (labels include `trace:id`).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MetricExemplar {
    /// Labels.
    pub labels: Attributes,
    /// Value.
    pub value: f64,
    /// Milliseconds since the epoch.
    pub timestamp_ms: i64,
}

/// Build information from `/api/status/buildinfo` (empty strings for unset fields).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BuildInfo {
    /// Version (`3.1.1`).
    pub version: String,
    /// Git revision.
    pub revision: String,
    /// Git branch.
    pub branch: String,
    /// Build user.
    pub build_user: String,
    /// Build date.
    pub build_date: String,
    /// Go version.
    pub go_version: String,
}

/// Client for the Tempo HTTP query API. Get it from [`TempoClient::query`]; it shares the
/// client's transport, tenant, token and lifetime and is cheap to clone.
#[derive(Debug, Clone)]
pub struct TempoQueryClient {
    client: TempoClient,
}

fn epoch_secs(t: SystemTime, round_up: bool) -> u64 {
    let d = t.duration_since(UNIX_EPOCH).unwrap_or_default();
    d.as_secs() + u64::from(round_up && d.subsec_nanos() > 0)
}

/// Encodes start/end as Unix seconds (end rounded up); `VALIDATION` when end is before start.
fn range_params(
    op: &str,
    start: Option<SystemTime>,
    end: Option<SystemTime>,
) -> Result<Vec<(String, String)>> {
    if let (Some(s), Some(e)) = (start, end) {
        if e < s {
            return Err(Error::validation(format!(
                "{op}: end must not be before start"
            )));
        }
    }
    let mut params = Vec::new();
    if let Some(s) = start {
        params.push(("start".to_owned(), epoch_secs(s, false).to_string()));
    }
    if let Some(e) = end {
        params.push(("end".to_owned(), epoch_secs(e, true).to_string()));
    }
    Ok(params)
}

fn positive(op: &str, name: &str, v: Option<u32>) -> Result<Option<String>> {
    match v {
        Some(0) => Err(Error::validation(format!(
            "{op}: {name} must be a positive integer"
        ))),
        other => Ok(other.map(|n| n.to_string())),
    }
}

fn positive_duration(op: &str, name: &str, v: Option<Duration>) -> Result<Option<String>> {
    match v {
        Some(d) if d.is_zero() => Err(Error::validation(format!("{op}: {name} must be positive"))),
        other => Ok(other.map(traceql_duration)),
    }
}

/// Percent-encodes `s` as one URL path segment (unreserved characters stay).
fn encode_segment(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Lower-cases a hex id and restores trimmed leading zeros.
fn pad_hex(id: &str, size: usize) -> String {
    format!("{:0>size$}", id.to_ascii_lowercase())
}

fn opt_string(v: Option<&Value>) -> Option<String> {
    v.and_then(Value::as_str).map(str::to_owned)
}

fn u64_of(v: Option<&Value>) -> u64 {
    u64::try_from(int_of(v)).unwrap_or(0)
}

fn f64_of(v: Option<&Value>) -> f64 {
    match v {
        Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(0.0),
        _ => 0.0,
    }
}

fn list(v: Option<&Value>) -> &[Value] {
    v.and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

fn search_span(s: &Value) -> TempoSearchSpan {
    TempoSearchSpan {
        span_id: pad_hex(
            s.get("spanID").and_then(Value::as_str).unwrap_or_default(),
            16,
        ),
        name: opt_string(s.get("name")),
        start_micros: nanos_to_micros(s.get("startTimeUnixNano")),
        duration_micros: nanos_to_micros(s.get("durationNanos")),
        attributes: from_key_values(s.get("attributes")),
    }
}

fn search_trace(t: &Value) -> TempoSearchTrace {
    let mut sets: Vec<&Value> = list(t.get("spanSets")).iter().collect();
    if sets.is_empty() {
        if let Some(set) = t.get("spanSet").filter(|v| v.is_object()) {
            sets.push(set);
        }
    }
    let service_stats = t
        .get("serviceStats")
        .and_then(Value::as_object)
        .map(|m| {
            m.iter()
                .map(|(svc, s)| {
                    (
                        svc.clone(),
                        ServiceStats {
                            span_count: u64_of(s.get("spanCount")),
                            error_count: u64_of(s.get("errorCount")),
                        },
                    )
                })
                .collect()
        })
        .unwrap_or_default();
    TempoSearchTrace {
        trace_id: pad_hex(
            t.get("traceID").and_then(Value::as_str).unwrap_or_default(),
            32,
        ),
        root_service_name: opt_string(t.get("rootServiceName")),
        root_trace_name: opt_string(t.get("rootTraceName")),
        start_micros: nanos_to_micros(t.get("startTimeUnixNano")),
        duration_micros: u64_of(t.get("durationMs")) * 1000,
        span_sets: sets
            .into_iter()
            .map(|s| TempoSpanSet {
                spans: list(s.get("spans")).iter().map(search_span).collect(),
                matched: u64_of(s.get("matched")),
            })
            .collect(),
        service_stats,
    }
}

fn search_result(body: &Value) -> TempoSearchResult {
    let m = body.get("metrics");
    let metric = |k: &str| int_of(m.and_then(|m| m.get(k)));
    TempoSearchResult {
        traces: list(body.get("traces")).iter().map(search_trace).collect(),
        metrics: SearchMetrics {
            inspected_traces: metric("inspectedTraces"),
            inspected_bytes: metric("inspectedBytes"),
            completed_jobs: metric("completedJobs"),
            total_jobs: metric("totalJobs"),
        },
    }
}

fn metrics_result(body: &Value) -> TraceQLMetricsResult {
    let series = list(body.get("series"))
        .iter()
        .map(|s| MetricSeries {
            labels: from_key_values(s.get("labels")),
            samples: list(s.get("samples"))
                .iter()
                .map(|p| MetricSample {
                    timestamp_ms: int_of(p.get("timestampMs")),
                    value: f64_of(p.get("value")),
                })
                .collect(),
            exemplars: list(s.get("exemplars"))
                .iter()
                .map(|e| MetricExemplar {
                    labels: from_key_values(e.get("labels")),
                    value: f64_of(e.get("value")),
                    timestamp_ms: int_of(e.get("timestampMs")),
                })
                .collect(),
        })
        .collect();
    let step = body
        .get("step")
        .map(|v| Duration::from_nanos(u64::try_from(int_of(Some(v))).unwrap_or(0)));
    TraceQLMetricsResult { series, step }
}

fn tag_values(body: &Value) -> Vec<TagValue> {
    list(body.get("tagValues"))
        .iter()
        .filter_map(|v| match v {
            Value::String(s) => Some(TagValue::new("string", s.clone())),
            Value::Object(o) => Some(TagValue::new(
                o.get("type")
                    .and_then(Value::as_str)
                    .filter(|t| !t.is_empty())
                    .unwrap_or("string"),
                o.get("value").and_then(Value::as_str).unwrap_or_default(),
            )),
            _ => None,
        })
        .collect()
}

fn sorted_values(values: Vec<TagValue>) -> Vec<String> {
    values
        .into_iter()
        .map(|v| v.value)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

/// The TraceQL [`TempoQueryClient::search_traces`] sends: one span-set filter with
/// `resource.service.name`, `name`, unscoped `.tag` filters (key order) and `trace:duration`
/// bounds in microseconds. An empty query gives `{}`.
pub fn search_traces_traceql(query: &TraceSearchQuery) -> Result<String> {
    let mut q = TraceQL::new();
    if let Some(service) = &query.service {
        q = q.and("resource.service.name", "=", service);
    }
    if let Some(op) = &query.operation {
        q = q.and("name", "=", op);
    }
    for (k, v) in &query.tags {
        q = q.and(&format!(".{k}"), "=", v);
    }
    if let Some(min) = query.min_duration {
        q = q.and(
            "trace:duration",
            ">=",
            TraceQLValue::raw(format!("{}us", min.as_micros())),
        );
    }
    if let Some(max) = query.max_duration {
        q = q.and(
            "trace:duration",
            "<=",
            TraceQLValue::raw(format!("{}us", max.as_micros())),
        );
    }
    q.build()
}

impl TempoQueryClient {
    pub(crate) fn new(client: TempoClient) -> Self {
        TempoQueryClient { client }
    }

    /// The client this query client belongs to.
    pub fn client(&self) -> &TempoClient {
        &self.client
    }

    async fn get_json(&self, op: &str, path: &str, params: Vec<(String, String)>) -> Result<Value> {
        let resp = self.client.get(op, path, params, &[]).await?;
        json_body(&resp)
    }

    /// The trace with the given hex id (upper case and short ids are accepted), `None` when
    /// Tempo has no such trace. Uses `/api/v2/traces/{id}` and falls back to `/api/traces/{id}`
    /// when the v2 route is missing (404).
    pub async fn get_trace_with(
        &self,
        trace_id: &str,
        opts: GetTraceOptions,
    ) -> Result<Option<Trace>> {
        const OP: &str = "traces.getTrace";
        let id = normalize_trace_id(trace_id)?;
        let params = range_params(OP, opts.start, opts.end)?;
        match self
            .get_json(OP, &format!("/api/v2/traces/{id}"), params.clone())
            .await
        {
            Ok(body) => return Ok(trace_from_body(&id, &body)),
            Err(e) if e.code() == ErrorCode::NotFound => {}
            Err(e) => return Err(e),
        }
        // v2 answers a miss with 200 {"trace":{}}: a 404 means the route is missing (older
        // Tempo or a gateway), so use v1, where 404 is a miss.
        let resp = self
            .client
            .get(OP, &format!("/api/traces/{id}"), params, &[404])
            .await?;
        if resp.status() == 404 {
            return Ok(None);
        }
        Ok(trace_from_body(&id, &json_body(&resp)?))
    }

    /// Runs a TraceQL search (`GET /api/search`). Results appear about 30 s after ingest
    /// (trace by id is immediate).
    pub async fn search(&self, req: &SearchRequest) -> Result<TempoSearchResult> {
        const OP: &str = "traces.search";
        let mut params = range_params(OP, req.start, req.end)?;
        if !req.traceql.trim().is_empty() {
            params.push(("q".to_owned(), req.traceql.clone()));
        }
        if let Some(l) = positive(OP, "limit", req.limit)? {
            params.push(("limit".to_owned(), l));
        }
        if let Some(s) = positive(OP, "spss", req.spss)? {
            params.push(("spss".to_owned(), s));
        }
        Ok(search_result(
            &self.get_json(OP, "/api/search", params).await?,
        ))
    }

    /// Attribute names per scope (`GET /api/v2/search/tags`), e.g.
    /// `{"resource": ["service.name"], "span": ["http.request.method"]}`. Scopes without data
    /// are absent.
    pub async fn search_tags(
        &self,
        opts: &SearchTagsOptions,
    ) -> Result<BTreeMap<String, Vec<String>>> {
        const OP: &str = "traces.searchTags";
        let mut params = range_params(OP, opts.start, opts.end)?;
        if let Some(scope) = opts.scope {
            params.push(("scope".to_owned(), scope.as_str().to_owned()));
        }
        if let Some(q) = opts.query.as_deref().filter(|q| !q.trim().is_empty()) {
            check_traceql(q, OP)?;
            params.push(("q".to_owned(), q.to_owned()));
        }
        let body = self.get_json(OP, "/api/v2/search/tags", params).await?;
        let mut out: BTreeMap<String, Vec<String>> = BTreeMap::new();
        for s in list(body.get("scopes")) {
            let name = s.get("name").and_then(Value::as_str).unwrap_or_default();
            out.entry(name.to_owned()).or_default().extend(
                list(s.get("tags"))
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_owned),
            );
        }
        Ok(out)
    }

    /// Values of a tag (`GET /api/v2/search/tag/{tag}/values`). `tag` is a TraceQL identifier:
    /// `resource.service.name`, `span.http.response.status_code`, an intrinsic (`name`,
    /// `status`) or a quoted name (`span."my key"`). Unscoped names are `VALIDATION`; an unknown
    /// tag gives no values.
    pub async fn search_tag_values(
        &self,
        tag: &str,
        opts: &SearchTagValuesOptions,
    ) -> Result<Vec<TagValue>> {
        const OP: &str = "traces.searchTagValues";
        let field = traceql_field(tag).map_err(|e| {
            Error::validation(format!(
                "{OP}: {}",
                e.message().trim_start_matches("traceql: ")
            ))
        })?;
        let mut params = range_params(OP, opts.start, opts.end)?;
        if let Some(q) = opts.query.as_deref().filter(|q| !q.trim().is_empty()) {
            check_traceql(q, OP)?;
            params.push(("q".to_owned(), q.to_owned()));
        }
        let path = format!("/api/v2/search/tag/{}/values", encode_segment(&field));
        Ok(tag_values(&self.get_json(OP, &path, params).await?))
    }

    /// Runs a TraceQL metrics query over a range (`GET /api/metrics/query_range`), e.g.
    /// `{ resource.service.name = "api" } | rate() by (span.http.route)`.
    pub async fn traceql_metrics(
        &self,
        query: &str,
        range: MetricsRange,
    ) -> Result<TraceQLMetricsResult> {
        const OP: &str = "traces.traceQLMetrics";
        if query.trim().is_empty() {
            return Err(Error::validation(format!("{OP}: query is required")));
        }
        let mut params = range_params(OP, range.start, range.end)?;
        params.push(("q".to_owned(), query.to_owned()));
        if range.start.is_none() {
            if let Some(s) = positive_duration(OP, "since", range.since)? {
                params.push(("since".to_owned(), s));
            }
        }
        if let Some(s) = positive_duration(OP, "step", range.step)? {
            params.push(("step".to_owned(), s));
        }
        if let Some(n) = positive(OP, "exemplars", range.exemplars)? {
            params.push(("exemplars".to_owned(), n));
        }
        Ok(metrics_result(
            &self
                .get_json(OP, "/api/metrics/query_range", params)
                .await?,
        ))
    }

    /// `GET /api/echo` → `"echo"`. Needs no tenant.
    pub async fn echo(&self) -> Result<String> {
        let resp = self
            .client
            .get("traces.echo", "/api/echo", Vec::new(), &[])
            .await?;
        Ok(resp.text()?.trim().to_owned())
    }

    /// Whether Tempo answers `GET /ready` with `ready` (`false` while it starts and answers
    /// 503). Unlike [`TempoClient::health`] it returns network and other HTTP errors.
    pub async fn ready(&self) -> Result<bool> {
        let resp = self
            .client
            .transport()
            .get("/ready")
            .operation("traces.ready")
            .idempotent(false)
            .ok_status(503)
            .send()
            .await?;
        Ok(resp.status() == 200 && resp.text().unwrap_or_default().trim() == "ready")
    }

    /// Build information (`GET /api/status/buildinfo`).
    pub async fn build_info(&self) -> Result<BuildInfo> {
        const OP: &str = "traces.buildInfo";
        let body = self
            .get_json(OP, "/api/status/buildinfo", Vec::new())
            .await?;
        let text = |k: &str| {
            body.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned()
        };
        Ok(BuildInfo {
            version: text("version"),
            revision: text("revision"),
            branch: text("branch"),
            build_user: text("buildUser"),
            build_date: text("buildDate"),
            go_version: text("goVersion"),
        })
    }
}

fn json_body(resp: &crate::core::Response) -> Result<Value> {
    if resp.bytes().iter().all(u8::is_ascii_whitespace) {
        return Ok(Value::Object(Default::default()));
    }
    resp.json::<Value>()
}

#[async_trait]
impl TraceQueryClient for TempoQueryClient {
    /// [`TempoQueryClient::get_trace_with`] without a range.
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.get_trace_with(trace_id, GetTraceOptions::default())
            .await
    }

    /// The query becomes one TraceQL span-set filter ([`search_traces_traceql`]) sent to
    /// [`TempoQueryClient::search`]. `duration_micros` has millisecond precision and
    /// `span_count` sums the per-service span counts.
    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        let traceql = search_traces_traceql(query)?;
        let req = SearchRequest {
            traceql,
            start: query.start,
            end: query.end,
            limit: (query.limit > 0).then(|| u32::try_from(query.limit).unwrap_or(u32::MAX)),
            spss: None,
        };
        let res = self.search(&req).await?;
        Ok(res
            .traces
            .into_iter()
            .map(|t| TraceSummary {
                span_count: t
                    .service_stats
                    .values()
                    .map(|s| s.span_count as usize)
                    .sum(),
                trace_id: t.trace_id,
                root_service_name: t.root_service_name,
                root_name: t.root_trace_name,
                start_micros: t.start_micros,
                duration_micros: t.duration_micros,
            })
            .collect())
    }

    /// Sorted values of `resource.service.name` in recent data.
    async fn list_services(&self) -> Result<Vec<String>> {
        let values = self
            .search_tag_values("resource.service.name", &SearchTagValuesOptions::default())
            .await?;
        Ok(sorted_values(values))
    }

    /// Sorted span names of one service.
    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        if service.trim().is_empty() {
            return Err(Error::validation(
                "traces.listOperations: service is required",
            ));
        }
        let filter = TraceQL::span([("resource.service.name", service)]).build()?;
        let values = self
            .search_tag_values("name", &SearchTagValuesOptions::query(filter))
            .await?;
        Ok(sorted_values(values))
    }

    /// Always `UNSUPPORTED`: Tempo has no dependency graph API (service graphs need the
    /// metrics-generator and a Prometheus backend).
    async fn get_dependencies(&self, _opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        Err(Error::unsupported(
            "traces.getDependencies: Grafana Tempo has no dependency graph API",
        ))
    }

    /// Polls [`TraceQueryClient::get_trace`] until the trace has `min_spans` spans (Tempo
    /// serves traces by id right after ingest).
    async fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        wait_for_trace(self, trace_id, opts).await
    }
}
