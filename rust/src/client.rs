// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `TempoClient`: entry point of the SDK (SPEC §2.4) for the Tempo query API.

use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::BoxFuture;

use crate::config::{TempoConfig, TENANT_HEADER};
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, ErrorCode, HealthStatus, HttpTransport, Response, Result};
use crate::query::{BuildInfo, TempoQueryClient};

/// Longest vendor text kept in health details.
const MAX_REASON: usize = 300;

/// Async client for Grafana Tempo. Cheap to clone (clones share one connection pool).
///
/// [`health`](Self::health), [`ping`](Self::ping), [`server_version`](Self::server_version) and
/// [`close`](Self::close) form the shared client surface; the query API (traces, search, tags,
/// TraceQL metrics) is reached through [`query`](Self::query).
#[derive(Debug, Clone)]
pub struct TempoClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    config: TempoConfig,
    transport: HttpTransport,
    on_close: CloseGroup,
}

/// Rewrites the 401 a multi-tenant Tempo returns without `X-Scope-OrgID` ("no org id").
pub(crate) fn map_tempo_error(err: Error, operation: &str) -> Error {
    if err.code() == ErrorCode::Authentication && err.message().contains("no org id") {
        return err.with_message(format!(
            "{operation}: tenant required ({TENANT_HEADER}) (HTTP 401)"
        ));
    }
    err
}

/// Extracts `3.1.1` from `tempo, version 3.1.1 (branch: …)`.
fn parse_status_version(text: &str) -> Option<String> {
    let mut rest = text;
    while let Some(i) = rest.find("version") {
        let after = &rest[i + "version".len()..];
        let trimmed = after.trim_start();
        if trimmed.len() < after.len() {
            let v = trimmed.strip_prefix('v').unwrap_or(trimmed);
            let end = v
                .find(|c: char| c.is_whitespace() || c == '(')
                .unwrap_or(v.len());
            let version = &v[..end];
            if version.starts_with(|c: char| c.is_ascii_digit()) {
                return Some(version.to_owned());
            }
        }
        rest = after;
    }
    None
}

impl TempoClient {
    /// Creates a client from a configuration.
    pub fn new(config: TempoConfig) -> Result<Self> {
        config.validate()?;
        let transport = HttpTransport::new(config.common())?;
        if let Some(token) = config.token() {
            transport.add_known_secret(token);
        }
        Ok(TempoClient {
            inner: Arc::new(Inner {
                config,
                transport,
                on_close: CloseGroup::new(),
            }),
        })
    }

    /// Creates a client from the process environment (see [`TempoConfig::from_env`]).
    pub fn from_env() -> Result<Self> {
        Self::new(TempoConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &TempoConfig {
        &self.inner.config
    }

    /// The HTTP transport (for service modules).
    pub fn transport(&self) -> &HttpTransport {
        &self.inner.transport
    }

    /// The query client (trace by id, TraceQL search, tags, metrics); it implements
    /// [`TraceQueryClient`](crate::tracing::TraceQueryClient).
    pub fn query(&self) -> TempoQueryClient {
        TempoQueryClient::new(self.clone())
    }

    /// Registers work that [`TempoClient::close`] runs (flush, stop watchers, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    /// `GET` with the tenant header and bearer token; maps Tempo's "no org id" 401.
    pub(crate) async fn get(
        &self,
        operation: &str,
        path: &str,
        params: Vec<(String, String)>,
        ok_statuses: &[u16],
    ) -> Result<Response> {
        let mut req = self.transport().get(path).operation(operation);
        for (k, v) in params {
            req = req.query(k, v);
        }
        for s in ok_statuses {
            req = req.ok_status(*s);
        }
        if let Some(tenant) = self.config().tenant_id() {
            req = req.header(TENANT_HEADER, tenant);
        }
        if let Some(token) = self.config().token() {
            req = req.bearer(token);
        }
        req.send().await.map_err(|e| map_tempo_error(e, operation))
    }

    /// Readiness check with `GET /ready` (no tenant or credentials needed). A ready Tempo gives
    /// `healthy: true`, status `ok`; any other answer gives status `unhealthy` with the
    /// `httpStatus` and `reason` details; a network failure gives status `unreachable`. Only
    /// `CANCELLED` and `CONFIG` are returned as errors.
    pub async fn health(&self) -> Result<HealthStatus> {
        let mut req = self
            .transport()
            .get("/ready")
            .operation("health")
            .idempotent(false);
        for status in 400..600 {
            req = req.ok_status(status);
        }
        let resp = match req.send().await {
            Ok(resp) => resp,
            Err(err) => return HealthStatus::from_error(err),
        };
        let body = String::from_utf8_lossy(resp.bytes());
        let body = crate::core::redact::truncate(&self.transport().redact(body.trim()), MAX_REASON);
        Ok(if resp.status() == 200 && body == "ready" {
            HealthStatus::healthy("ok").with_detail("ready", body)
        } else {
            HealthStatus::unhealthy("unhealthy")
                .with_detail("httpStatus", resp.status().to_string())
                .with_detail("reason", body)
        })
    }

    /// Tenant-checked round trip (`GET /api/v2/search/tags?scope=intrinsic`); returns the
    /// latency. A multi-tenant Tempo without a tenant gives `AUTHENTICATION` ("tenant
    /// required"); a proxy rejecting the token gives `AUTHENTICATION` / `PERMISSION_DENIED`.
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        self.get(
            "ping",
            "/api/v2/search/tags",
            vec![("scope".to_owned(), "intrinsic".to_owned())],
            &[],
        )
        .await?;
        Ok(started.elapsed().max(Duration::from_nanos(1)))
    }

    /// The Tempo version (`3.1.1`) from `/api/status/buildinfo`, falling back to the text of
    /// `/status/version`; `UNSUPPORTED` when neither route exists.
    pub async fn server_version(&self) -> Result<String> {
        match self.build_info().await {
            Ok(info) if !info.version.is_empty() => {
                return Ok(info.version.trim_start_matches('v').to_owned())
            }
            Ok(_) => {}
            Err(e) if e.code() == ErrorCode::NotFound => {}
            Err(e) => return Err(e),
        }
        let resp = self
            .get("serverVersion", "/status/version", Vec::new(), &[])
            .await
            .map_err(|e| match e.code() {
                ErrorCode::NotFound => {
                    Error::unsupported("serverVersion: the server exposes no version")
                }
                _ => e,
            })?;
        let text = String::from_utf8_lossy(resp.bytes());
        parse_status_version(&text).ok_or_else(|| {
            Error::unavailable("serverVersion: unrecognised version text")
                .with_status(resp.status())
        })
    }

    /// See [`TempoQueryClient::build_info`].
    pub async fn build_info(&self) -> Result<BuildInfo> {
        self.query().build_info().await
    }

    /// See [`TempoQueryClient::echo`].
    pub async fn echo(&self) -> Result<String> {
        self.query().echo().await
    }

    /// See [`TempoQueryClient::ready`].
    pub async fn ready(&self) -> Result<bool> {
        self.query().ready().await
    }

    /// Runs registered close work, then closes the transport; later calls fail with
    /// `CANCELLED`. Idempotent. The client owns no tracer provider: shut down a
    /// [`TracingHandle`](crate::tracing::TracingHandle) separately.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        self.inner.transport.close();
    }

    /// Whether [`TempoClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.transport.is_closed()
    }
}

#[cfg(test)]
mod tests {
    use super::parse_status_version;

    #[test]
    fn status_version_text() {
        assert_eq!(
            parse_status_version("tempo, version 3.1.1 (branch: HEAD, revision: x)").as_deref(),
            Some("3.1.1")
        );
        assert_eq!(
            parse_status_version("version v2.0.0").as_deref(),
            Some("2.0.0")
        );
        assert_eq!(parse_status_version("versionless version x"), None);
        assert_eq!(parse_status_version("nothing"), None);
    }
}
