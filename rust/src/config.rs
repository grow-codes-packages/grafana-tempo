// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `TempoConfig`: common settings (SPEC §2.2) plus the Grafana Tempo tenant and the optional
//! bearer token for an authenticating proxy.

use std::collections::HashMap;
use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig,
};

/// Query API endpoint used when neither the builder nor the environment sets one (port 3200).
pub const DEFAULT_URL: &str = "http://127.0.0.1:3200";

/// Header that selects the tenant on a multi-tenant Tempo (query API and OTLP/HTTP ingest;
/// OTLP/gRPC sends it as the metadata key `x-scope-orgid`). A single-tenant Tempo ignores it.
pub const TENANT_HEADER: &str = "X-Scope-OrgID";

/// Longest tenant id Tempo accepts (per tenant of a federated `a|b` id).
const MAX_TENANT_ID_LENGTH: usize = 150;

/// Vendor variables checked (in order) after `GROW_GRAFANA_TEMPO_<NAME>`, per name.
const VENDOR_ENV: &[(&str, &[&str])] = &[
    ("URL", &["TEMPO_URL", "GROW_TEMPO_URL"]),
    ("TENANT_ID", &["TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"]),
    ("TOKEN", &["GROW_TEMPO_TOKEN"]),
    ("CONNECT_TIMEOUT", &["GROW_TEMPO_CONNECT_TIMEOUT"]),
    ("REQUEST_TIMEOUT", &["GROW_TEMPO_REQUEST_TIMEOUT"]),
    ("TOTAL_TIMEOUT", &["GROW_TEMPO_TOTAL_TIMEOUT"]),
    ("MAX_RETRIES", &["GROW_TEMPO_MAX_RETRIES"]),
    ("CA_CERT", &["GROW_TEMPO_CA_CERT"]),
    ("CLIENT_CERT", &["GROW_TEMPO_CLIENT_CERT"]),
    ("CLIENT_KEY", &["GROW_TEMPO_CLIENT_KEY"]),
    ("INSECURE_SKIP_VERIFY", &["GROW_TEMPO_INSECURE_SKIP_VERIFY"]),
    ("PROXY", &["GROW_TEMPO_PROXY"]),
];

/// Vendor variables for the tenant id, in precedence order (after `GROW_GRAFANA_TEMPO_TENANT_ID`).
pub(crate) const VENDOR_TENANT_VARS: &[&str] = &["TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"];

fn vendor_vars(name: &str) -> &'static [&'static str] {
    VENDOR_ENV
        .iter()
        .find(|(n, _)| *n == name)
        .map(|(_, v)| *v)
        .unwrap_or(&[])
}

/// Checks Tempo's tenant id rules: each tenant is 1–150 characters of letters, digits and
/// `!-_.*'()` (not `.` or `..`); `|` joins tenants for a federated query (`"tenant-b|tenant-a"`).
/// `CONFIG` otherwise; the message never echoes the value.
pub fn validate_tenant_id(tenant_id: &str) -> Result<()> {
    for tenant in tenant_id.split('|') {
        if tenant.is_empty()
            || tenant.chars().count() > MAX_TENANT_ID_LENGTH
            || tenant == "."
            || tenant == ".."
        {
            return Err(Error::config(
                "tenantId must be 1-150 characters per tenant",
            ));
        }
        if !tenant
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "!-_.*'()".contains(c))
        {
            return Err(Error::config(
                "tenantId may contain only letters, digits and !-_.*'()",
            ));
        }
    }
    Ok(())
}

/// Immutable client configuration for the Tempo query API; build it with
/// [`TempoConfig::builder`] or [`TempoConfig::from_env`]. `Debug` output redacts every
/// credential.
///
/// Tempo has no authentication of its own. [`token`](TempoConfigBuilder::token) (sent as
/// `Authorization: Bearer`) and extra headers exist for an authenticating reverse proxy in
/// front of Tempo; both are redacted everywhere.
///
/// Environment ([`TempoConfig::from_env`]), precedence explicit > `GROW_GRAFANA_TEMPO_<NAME>` >
/// vendor > default: `URL` (vendor `TEMPO_URL`, `GROW_TEMPO_URL`), `TENANT_ID`
/// (`TEMPO_TENANT_ID`, `GROW_TEMPO_TENANT_ID`), `TOKEN` (`GROW_TEMPO_TOKEN`),
/// `CONNECT_TIMEOUT`, `REQUEST_TIMEOUT`, `TOTAL_TIMEOUT`, `MAX_RETRIES`, `CA_CERT`,
/// `CLIENT_CERT`, `CLIENT_KEY`, `INSECURE_SKIP_VERIFY`, `PROXY` (vendor aliases
/// `GROW_TEMPO_<NAME>`).
#[derive(Clone)]
pub struct TempoConfig {
    common: CommonConfig,
    token: Option<Secret>,
    tenant_id: Option<String>,
}

impl fmt::Debug for TempoConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TempoConfig")
            .field("common", &self.common)
            .field("token", &self.token)
            .field("tenant_id", &self.tenant_id)
            .finish()
    }
}

impl TempoConfig {
    /// A builder with nothing set.
    pub fn builder() -> TempoConfigBuilder {
        TempoConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_GRAFANA_TEMPO_*` > vendor variables > defaults).
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// Query API base URL.
    pub fn url(&self) -> &url::Url {
        self.common.url()
    }

    /// Bearer token for an authenticating proxy, when configured.
    pub fn token(&self) -> Option<&Secret> {
        self.token.as_ref()
    }

    /// Tenant sent as `X-Scope-OrgID` on every query, when configured.
    pub fn tenant_id(&self) -> Option<&str> {
        self.tenant_id.as_deref()
    }

    /// Checks the service rules (the token must not be blank, the tenant must follow
    /// [`validate_tenant_id`]); common rules are checked when building.
    pub fn validate(&self) -> Result<()> {
        if let Some(token) = &self.token {
            if token.expose_secret().trim().is_empty() {
                return Err(Error::config("token must not be blank"));
            }
        }
        if let Some(tenant) = &self.tenant_id {
            validate_tenant_id(tenant)?;
        }
        Ok(())
    }
}

/// Builder for [`TempoConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct TempoConfigBuilder {
    common: CommonConfigBuilder,
    token: Option<Secret>,
    tenant_id: Option<String>,
}

impl TempoConfigBuilder {
    /// Query API base URL (default [`DEFAULT_URL`]).
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.common = self.common.url(url);
        self
    }
    /// Bearer token for an authenticating proxy in front of Tempo.
    pub fn token(mut self, token: impl Into<Secret>) -> Self {
        self.token = Some(token.into());
        self
    }
    /// Tenant sent as `X-Scope-OrgID` (required by a multi-tenant Tempo; `"a|b"` federates).
    pub fn tenant_id(mut self, tenant_id: impl Into<String>) -> Self {
        self.tenant_id = Some(tenant_id.into().trim().to_owned());
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header (for example credentials of an authenticating proxy).
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting.
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment.
    pub fn build(self) -> Result<TempoConfig> {
        let config = TempoConfig {
            common: self.common.build(DEFAULT_URL)?,
            token: self.token,
            tenant_id: self.tenant_id,
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<TempoConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds.
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<TempoConfig> {
        self.common = self
            .common
            .apply_env(&with_vendor_aliases(env), vendor_vars("URL"))?;
        if self.token.is_none() {
            self.token = env.secret("TOKEN", vendor_vars("TOKEN"));
        }
        if self.tenant_id.is_none() {
            self.tenant_id = env
                .string("TENANT_ID", VENDOR_TENANT_VARS)
                .map(|t| t.trim().to_owned());
        }
        self.build()
    }
}

/// A reader over the common `GROW_GRAFANA_TEMPO_<NAME>` variables in which each name is
/// resolved with its vendor aliases first (the generated `apply_env` reads only the prefixed
/// names). The URL keeps its own vendor list so the error names stay accurate.
fn with_vendor_aliases(env: &EnvReader) -> EnvReader {
    let mut vars = HashMap::new();
    for (name, vendors) in VENDOR_ENV {
        if matches!(*name, "URL" | "TOKEN" | "TENANT_ID") {
            if let Some((var, value)) = env.lookup(name, vendors) {
                vars.insert(var, value);
            }
            continue;
        }
        if let Some((_, value)) = env.lookup(name, vendors) {
            vars.insert(format!("{}{name}", env.prefix()), value);
        }
    }
    EnvReader::from_map(vars).with_prefix(env.prefix())
}
