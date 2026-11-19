// Created by sdk-kit 0.1.0; owned by this repository.
//! Helpers for the integration tests: bootstrap credentials, the conformance directory and
//! Tempo's search visibility delay.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use grow_grafana_tempo as sdk;
use sdk::core::{poll_until, PollOptions};
use sdk::tracing::{
    DependencyLink, DependencyOptions, Trace, TraceQueryClient, TraceSearchQuery, TraceSummary,
    WaitForTraceOptions,
};
use sdk::{Result, TempoQueryClient};

/// How long tag/search data may take to appear after ingest (Tempo cuts its live-store head
/// block about 30 s after ingest).
pub const SEARCH_VISIBILITY: Duration = Duration::from_secs(90);

/// Loads `KEY=value` lines from `$GROW_BOOTSTRAP_ENV` (default `/bootstrap/env`) into the
/// process environment; variables that are already set win. A missing file is not an error.
pub fn load_bootstrap_env() {
    let path = std::env::var("GROW_BOOTSTRAP_ENV").unwrap_or_else(|_| "/bootstrap/env".to_owned());
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            let key = key.trim().trim_start_matches("export ").trim();
            let value = value.trim().trim_matches('"');
            if !key.is_empty() && std::env::var_os(key).is_none() {
                std::env::set_var(key, value);
            }
        }
    }
}

/// A bootstrap variable (panics when missing).
pub fn env(name: &str) -> String {
    std::env::var(name)
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| panic!("{name} missing from /bootstrap/env"))
}

/// `$GROW_CONFORMANCE_DIR`, else `<repo>/conformance/scenarios`.
pub fn conformance_dir() -> PathBuf {
    sdk::testing::scenarios_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/.."))
}

/// A query client whose `list_services` waits until `service` is listed: Tempo serves a trace
/// by id at once but lists its service only about 30 s later (the conformance scenario calls
/// `listServices` once).
pub struct VisibleQuery {
    /// Wrapped Tempo query client.
    pub inner: TempoQueryClient,
    /// Service that must become visible.
    pub service: String,
}

impl VisibleQuery {
    /// Wraps `inner` for `service`.
    pub fn new(inner: TempoQueryClient, service: impl Into<String>) -> Arc<Self> {
        Arc::new(VisibleQuery {
            inner,
            service: service.into(),
        })
    }
}

#[async_trait]
impl TraceQueryClient for VisibleQuery {
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.inner.get_trace(trace_id).await
    }
    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        self.inner.search_traces(query).await
    }
    async fn list_services(&self) -> Result<Vec<String>> {
        let opts = PollOptions {
            timeout: SEARCH_VISIBILITY,
            interval: Duration::from_secs(1),
        };
        poll_until("listServices", opts, || async {
            let services = self.inner.list_services().await?;
            Ok(services.contains(&self.service).then_some(services))
        })
        .await
    }
    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.inner.list_operations(service).await
    }
    async fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        self.inner.get_dependencies(opts).await
    }
    async fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        self.inner.wait_for_trace(trace_id, opts).await
    }
}
