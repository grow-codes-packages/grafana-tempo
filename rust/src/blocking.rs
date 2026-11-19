// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! Blocking wrapper (feature `blocking`). Each client owns a current-thread tokio runtime.
//!
//! Do not use it from inside an async runtime (calls would panic); use the async client there.

use std::collections::BTreeMap;
use std::future::Future;
use std::time::Duration;

use crate::core::{Error, HealthStatus, Result};
use crate::tracing::{
    DependencyLink, DependencyOptions, Trace, TraceQueryClient, TraceSearchQuery, TraceSummary,
    WaitForTraceOptions,
};
use crate::{
    BuildInfo, GetTraceOptions, MetricsRange, SearchRequest, SearchTagValuesOptions,
    SearchTagsOptions, TagValue, TempoClient as AsyncClient, TempoConfig,
    TempoQueryClient as AsyncQuery, TempoSearchResult, TraceQLMetricsResult,
};

/// Blocking client for Grafana Tempo with the same methods as the async client.
#[derive(Debug)]
pub struct TempoClient {
    inner: AsyncClient,
    runtime: tokio::runtime::Runtime,
}

impl TempoClient {
    /// Creates a client from a configuration.
    pub fn new(config: TempoConfig) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| Error::config("cannot start the tokio runtime").with_source(e))?;
        let inner = {
            let _guard = runtime.enter();
            AsyncClient::new(config)?
        };
        Ok(TempoClient { inner, runtime })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(TempoConfig::from_env()?)
    }

    /// The wrapped async client.
    pub fn async_client(&self) -> &AsyncClient {
        &self.inner
    }

    /// Runs a future of the async API to completion on this client's runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    /// The blocking query client.
    pub fn query(&self) -> TempoQueryClient<'_> {
        TempoQueryClient {
            client: self,
            inner: self.inner.query(),
        }
    }

    /// See [`AsyncClient::health`].
    pub fn health(&self) -> Result<HealthStatus> {
        self.block_on(self.inner.health())
    }

    /// See [`AsyncClient::ping`].
    pub fn ping(&self) -> Result<Duration> {
        self.block_on(self.inner.ping())
    }

    /// See [`AsyncClient::server_version`].
    pub fn server_version(&self) -> Result<String> {
        self.block_on(self.inner.server_version())
    }

    /// See [`AsyncClient::build_info`].
    pub fn build_info(&self) -> Result<BuildInfo> {
        self.block_on(self.inner.build_info())
    }

    /// See [`AsyncClient::echo`].
    pub fn echo(&self) -> Result<String> {
        self.block_on(self.inner.echo())
    }

    /// See [`AsyncClient::ready`].
    pub fn ready(&self) -> Result<bool> {
        self.block_on(self.inner.ready())
    }

    /// See [`AsyncClient::close`]. Idempotent.
    pub fn close(&self) {
        self.block_on(self.inner.close());
    }
}

impl Drop for TempoClient {
    fn drop(&mut self) {
        self.inner.transport().close();
    }
}

/// Blocking view of [`crate::TempoQueryClient`]; borrowed from [`TempoClient::query`].
#[derive(Debug)]
pub struct TempoQueryClient<'a> {
    client: &'a TempoClient,
    inner: AsyncQuery,
}

impl TempoQueryClient<'_> {
    /// See [`AsyncQuery::get_trace_with`] (no range).
    pub fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.client.block_on(self.inner.get_trace(trace_id))
    }
    /// See [`AsyncQuery::get_trace_with`].
    pub fn get_trace_with(&self, trace_id: &str, opts: GetTraceOptions) -> Result<Option<Trace>> {
        self.client
            .block_on(self.inner.get_trace_with(trace_id, opts))
    }
    /// See [`AsyncQuery::search`].
    pub fn search(&self, req: &SearchRequest) -> Result<TempoSearchResult> {
        self.client.block_on(self.inner.search(req))
    }
    /// See [`TraceQueryClient::search_traces`].
    pub fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        self.client.block_on(self.inner.search_traces(query))
    }
    /// See [`AsyncQuery::search_tags`].
    pub fn search_tags(&self, opts: &SearchTagsOptions) -> Result<BTreeMap<String, Vec<String>>> {
        self.client.block_on(self.inner.search_tags(opts))
    }
    /// See [`AsyncQuery::search_tag_values`].
    pub fn search_tag_values(
        &self,
        tag: &str,
        opts: &SearchTagValuesOptions,
    ) -> Result<Vec<TagValue>> {
        self.client
            .block_on(self.inner.search_tag_values(tag, opts))
    }
    /// See [`TraceQueryClient::list_services`].
    pub fn list_services(&self) -> Result<Vec<String>> {
        self.client.block_on(self.inner.list_services())
    }
    /// See [`TraceQueryClient::list_operations`].
    pub fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.client.block_on(self.inner.list_operations(service))
    }
    /// Always `UNSUPPORTED` (see [`TraceQueryClient::get_dependencies`]).
    pub fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        self.client.block_on(self.inner.get_dependencies(opts))
    }
    /// See [`TraceQueryClient::wait_for_trace`].
    pub fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        self.client
            .block_on(self.inner.wait_for_trace(trace_id, opts))
    }
    /// See [`AsyncQuery::traceql_metrics`].
    pub fn traceql_metrics(
        &self,
        query: &str,
        range: MetricsRange,
    ) -> Result<TraceQLMetricsResult> {
        self.client
            .block_on(self.inner.traceql_metrics(query, range))
    }
    /// See [`AsyncQuery::echo`].
    pub fn echo(&self) -> Result<String> {
        self.client.block_on(self.inner.echo())
    }
    /// See [`AsyncQuery::ready`].
    pub fn ready(&self) -> Result<bool> {
        self.client.block_on(self.inner.ready())
    }
    /// See [`AsyncQuery::build_info`].
    pub fn build_info(&self) -> Result<BuildInfo> {
        self.client.block_on(self.inner.build_info())
    }
}
