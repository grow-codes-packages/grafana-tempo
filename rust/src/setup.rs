//! Tracing setup for Tempo: OpenTelemetry over OTLP (`init_tracing`) with the Tempo defaults,
//! the tenant header and the exporter environment variables.

use std::collections::{BTreeMap, HashMap};
use std::time::Duration;

use opentelemetry_otlp::{SpanExporter, WithExportConfig, WithHttpConfig, WithTonicConfig};

use crate::config::{validate_tenant_id, TENANT_HEADER, VENDOR_TENANT_VARS};
use crate::core::{EnvReader, Error, Result, TlsConfig};
use crate::tracing::{
    init_tracing_with, Compression, Protocol, SamplerKind, TracingConfig, TracingHandle,
};

/// Default OTLP/HTTP endpoint (Tempo's OTLP HTTP receiver).
pub const DEFAULT_OTLP_HTTP_ENDPOINT: &str = "http://127.0.0.1:4318";
/// Default OTLP/gRPC endpoint (Tempo's OTLP gRPC receiver).
pub const DEFAULT_OTLP_GRPC_ENDPOINT: &str = "http://127.0.0.1:4317";
/// Export protocol when none is configured.
pub const DEFAULT_TRACING_PROTOCOL: Protocol = Protocol::HttpProtobuf;

/// Starts OpenTelemetry tracing that exports to Tempo over OTLP.
///
/// Settings are resolved by [`tempo_tracing_config`] over the process environment (explicit >
/// `GROW_GRAFANA_TEMPO_OTLP_*` > `OTEL_EXPORTER_OTLP_TRACES_*` > `OTEL_EXPORTER_OTLP_*` >
/// default), then the template [`init_tracing_with`] builds the provider with
/// [`tempo_exporter`]. Must run inside a tokio runtime; call
/// [`TracingHandle::shutdown`] before the process exits.
///
/// A multi-tenant Tempo answers an export without a tenant with HTTP 503 / gRPC `Unavailable`,
/// which the exporter retries until it drops the spans: set the tenant ([`with_tenant`] or
/// `GROW_GRAFANA_TEMPO_TENANT_ID`) for such servers.
///
/// ```no_run
/// # async fn demo() -> grow_grafana_tempo::Result<()> {
/// use grow_grafana_tempo::{init_tracing, tracing::TracingConfig};
///
/// let handle = init_tracing(TracingConfig::new("checkout"))?;
/// grow_grafana_tempo::tracing::traced("charge", async { Ok::<_, std::io::Error>(()) }).await.ok();
/// handle.shutdown().await?;
/// # Ok(()) }
/// ```
pub fn init_tracing(config: TracingConfig) -> Result<TracingHandle> {
    let resolved = tempo_tracing_config(config, &EnvReader::new())?;
    init_tracing_with(resolved, tempo_exporter)
}

/// A copy of `config` that sends `tenant` as `X-Scope-OrgID` (gRPC metadata `x-scope-orgid`)
/// with every export, replacing any tenant header. An empty tenant removes the header.
pub fn with_tenant(mut config: TracingConfig, tenant: &str) -> TracingConfig {
    config
        .headers
        .retain(|k, _| !k.eq_ignore_ascii_case(TENANT_HEADER));
    let tenant = tenant.trim();
    if !tenant.is_empty() {
        config
            .headers
            .insert(TENANT_HEADER.to_owned(), tenant.to_owned());
    }
    config
}

/// The OTLP/HTTP traces URL: a bare `scheme://host:port` gets `/v1/traces` appended; an
/// endpoint with a path is used as is.
pub fn otlp_http_traces_url(endpoint: &str) -> String {
    match url::Url::parse(endpoint) {
        Ok(u) if matches!(u.path(), "" | "/") => {
            format!("{}/v1/traces", endpoint.trim_end_matches('/'))
        }
        _ => endpoint.to_owned(),
    }
}

fn append_traces_path(endpoint: &str) -> String {
    format!("{}/v1/traces", endpoint.trim_end_matches('/'))
}

/// Parses the OTel list format `k1=v1,k2=v2` (percent-decoded) used by
/// `OTEL_EXPORTER_OTLP_HEADERS` and `OTEL_RESOURCE_ATTRIBUTES`; `CONFIG` naming `variable`
/// (never the value) when malformed.
pub fn parse_key_value_list(text: &str, variable: &str) -> Result<BTreeMap<String, String>> {
    let invalid = || Error::config(format!("{variable} is not a valid key=value list"));
    let mut out = BTreeMap::new();
    for item in text.split(',') {
        let item = item.trim();
        if item.is_empty() {
            continue;
        }
        let (k, v) = item.split_once('=').ok_or_else(invalid)?;
        let k = k.trim();
        if k.is_empty() {
            return Err(invalid());
        }
        let key = percent_decode(k).ok_or_else(invalid)?;
        let value = percent_decode(v.trim()).ok_or_else(invalid)?;
        out.insert(key, value);
    }
    Ok(out)
}

fn percent_decode(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn has_header(headers: &BTreeMap<String, String>, name: &str) -> bool {
    headers.keys().any(|k| k.eq_ignore_ascii_case(name))
}

/// Resolves the Tempo tracing settings from `config` and the environment `env`.
///
/// Unset values come from the environment, per field:
///
/// - `protocol`: `GROW_GRAFANA_TEMPO_OTLP_PROTOCOL` > `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` >
///   `OTEL_EXPORTER_OTLP_PROTOCOL` > `http/protobuf` (`native` also means OTLP/HTTP: Tempo has
///   no other protocol). Rust's `TracingConfig` has no "unset" protocol, so the environment
///   applies while `config.protocol` is `http/protobuf` (the [`TracingConfig::new`] default).
/// - `endpoint`: `GROW_GRAFANA_TEMPO_OTLP_ENDPOINT` > `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
///   (verbatim) > `OTEL_EXPORTER_OTLP_ENDPOINT` (`/v1/traces` appended for HTTP) > default
///   (`http://127.0.0.1:4318`, or `:4317` for `grpc`).
/// - `headers`: `OTEL_EXPORTER_OTLP_TRACES_HEADERS` > `OTEL_EXPORTER_OTLP_HEADERS` for keys not
///   set explicitly, plus `X-Scope-OrgID` from `GROW_GRAFANA_TEMPO_TENANT_ID` >
///   `TEMPO_TENANT_ID` > `GROW_TEMPO_TENANT_ID` unless a tenant header is set.
/// - `compression`: `GROW_GRAFANA_TEMPO_OTLP_COMPRESSION` >
///   `OTEL_EXPORTER_OTLP_TRACES_COMPRESSION` > `OTEL_EXPORTER_OTLP_COMPRESSION` (`gzip` /
///   `none`; applies while `config.compression` is the default gzip).
/// - `service_name`: `OTEL_SERVICE_NAME` > `service.name` in `OTEL_RESOURCE_ATTRIBUTES` (when
///   empty); the other `OTEL_RESOURCE_ATTRIBUTES` fill unset resource attributes.
/// - sampler: `OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG` (while the sampler / ratio are
///   the defaults).
///
/// Invalid values give `CONFIG` naming the variable, never its value. The result is validated.
pub fn tempo_tracing_config(config: TracingConfig, env: &EnvReader) -> Result<TracingConfig> {
    let vendor = env.clone().with_prefix("");
    let mut c = config;

    if c.protocol == Protocol::HttpProtobuf {
        if let Some((var, v)) = env.lookup(
            "OTLP_PROTOCOL",
            &[
                "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
                "OTEL_EXPORTER_OTLP_PROTOCOL",
            ],
        ) {
            c.protocol = v.parse().map_err(|_| {
                Error::config(format!("{var} must be grpc, http/protobuf or native"))
            })?;
        }
    }
    if c.protocol == Protocol::Native {
        c.protocol = DEFAULT_TRACING_PROTOCOL;
    }

    if c.endpoint.is_none() {
        if let Some(v) = env.string("OTLP_ENDPOINT", &["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"]) {
            c.endpoint = Some(v.trim().to_owned());
        } else if let Some(v) = vendor.string("OTEL_EXPORTER_OTLP_ENDPOINT", &[]) {
            let v = v.trim();
            c.endpoint = Some(if c.protocol == Protocol::Grpc {
                v.to_owned()
            } else {
                append_traces_path(v)
            });
        } else {
            c.endpoint = Some(
                if c.protocol == Protocol::Grpc {
                    DEFAULT_OTLP_GRPC_ENDPOINT
                } else {
                    DEFAULT_OTLP_HTTP_ENDPOINT
                }
                .to_owned(),
            );
        }
    }

    if let Some((var, raw)) = vendor.lookup(
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
        &["OTEL_EXPORTER_OTLP_HEADERS"],
    ) {
        for (k, v) in parse_key_value_list(&raw, &var)? {
            if !has_header(&c.headers, &k) {
                c.headers.insert(k, v);
            }
        }
    }
    if !has_header(&c.headers, TENANT_HEADER) {
        if let Some(t) = env.string("TENANT_ID", VENDOR_TENANT_VARS) {
            c = with_tenant(c, &t);
        }
    }

    if c.compression == Compression::Gzip {
        if let Some((var, v)) = env.lookup(
            "OTLP_COMPRESSION",
            &[
                "OTEL_EXPORTER_OTLP_TRACES_COMPRESSION",
                "OTEL_EXPORTER_OTLP_COMPRESSION",
            ],
        ) {
            c.compression = match v.trim().to_ascii_lowercase().as_str() {
                "gzip" => Compression::Gzip,
                "none" | "" => Compression::None,
                _ => return Err(Error::config(format!("{var} must be gzip or none"))),
            };
        }
    }

    let mut resource_service_name = None;
    if let Some((var, raw)) = vendor.lookup("OTEL_RESOURCE_ATTRIBUTES", &[]) {
        for (k, v) in parse_key_value_list(&raw, &var)? {
            if k == "service.name" {
                resource_service_name = Some(v);
            } else {
                c.resource_attributes.entry(k).or_insert_with(|| v.into());
            }
        }
    }
    if c.service_name.trim().is_empty() {
        if let Some(name) = vendor.string("OTEL_SERVICE_NAME", &[]) {
            c.service_name = name.trim().to_owned();
        } else if let Some(name) = resource_service_name {
            c.service_name = name;
        }
    }

    if c.sampler == SamplerKind::default() {
        if let Some((var, v)) = vendor.lookup("OTEL_TRACES_SAMPLER", &[]) {
            c.sampler = v
                .parse()
                .map_err(|_| Error::config(format!("{var} is not a supported sampler")))?;
        }
    }
    if c.sampler_ratio == 1.0 {
        if let Some((var, v)) = vendor.lookup("OTEL_TRACES_SAMPLER_ARG", &[]) {
            c.sampler_ratio = v
                .trim()
                .parse()
                .map_err(|_| Error::config(format!("{var} is not a valid number")))?;
        }
    }

    c.validate()?;
    for (k, v) in &c.headers {
        if k.eq_ignore_ascii_case(TENANT_HEADER) {
            validate_tenant_id(v)?;
        }
    }
    Ok(c)
}

fn read_file(path: &std::path::Path, what: &str) -> Result<Vec<u8>> {
    std::fs::read(path).map_err(|e| {
        Error::config(format!(
            "tracing {what} file {} cannot be read",
            path.display()
        ))
        .with_source(e)
    })
}

fn tls_ca(tls: &TlsConfig) -> Result<Option<Vec<u8>>> {
    match (&tls.ca_cert_pem, &tls.ca_cert_path) {
        (Some(pem), _) => Ok(Some(pem.clone().into_bytes())),
        (None, Some(path)) => read_file(path, "CA certificate").map(Some),
        (None, None) => Ok(None),
    }
}

fn tls_identity(tls: &TlsConfig) -> Result<Option<(Vec<u8>, Vec<u8>)>> {
    match (&tls.client_cert_path, &tls.client_key_path) {
        (Some(cert), Some(key)) => Ok(Some((
            read_file(cert, "client certificate")?,
            read_file(key, "client key")?,
        ))),
        (None, None) => Ok(None),
        _ => Err(Error::config(
            "tracing client certificate and client key must be given together",
        )),
    }
}

fn http_client(tls: &TlsConfig, timeout: Duration) -> Result<reqwest::Client> {
    let mut b = reqwest::Client::builder().timeout(timeout);
    if let Some(ca) = tls_ca(tls)? {
        let cert = reqwest::Certificate::from_pem(&ca)
            .map_err(|_| Error::config("tracing CA certificate is not valid PEM"))?;
        b = b.add_root_certificate(cert);
    }
    if let Some((cert, key)) = tls_identity(tls)? {
        let identity = reqwest::Identity::from_pem(&[cert, key].concat())
            .map_err(|_| Error::config("tracing client certificate or key is not valid PEM"))?;
        b = b.identity(identity);
    }
    if tls.insecure_skip_verify {
        b = b.danger_accept_invalid_certs(true);
    }
    b.build()
        .map_err(|e| Error::config("cannot build the OTLP HTTP client").with_source(e))
}

fn grpc_tls(
    tls: &TlsConfig,
) -> Result<opentelemetry_otlp::tonic_types::transport::ClientTlsConfig> {
    use opentelemetry_otlp::tonic_types::transport::{Certificate, ClientTlsConfig, Identity};
    if tls.insecure_skip_verify {
        return Err(Error::config(
            "tracing insecureSkipVerify is not supported for OTLP/gRPC",
        ));
    }
    let mut cfg = ClientTlsConfig::new().with_native_roots();
    if let Some(ca) = tls_ca(tls)? {
        cfg = cfg.ca_certificate(Certificate::from_pem(ca));
    }
    if let Some((cert, key)) = tls_identity(tls)? {
        cfg = cfg.identity(Identity::from_pem(cert, key));
    }
    Ok(cfg)
}

/// The official OpenTelemetry OTLP exporter for a resolved config (the Tempo exporter factory
/// for [`init_tracing_with`]).
///
/// - `http/protobuf` and `native`: OTLP/HTTP protobuf to [`otlp_http_traces_url`] of the
///   endpoint (default `http://127.0.0.1:4318`), headers as HTTP headers, TLS options (CA,
///   client certificate, `insecure_skip_verify`) through the HTTP client.
/// - `grpc`: OTLP/gRPC to the endpoint (default `http://127.0.0.1:4317`), headers as gRPC
///   metadata (`x-scope-orgid`); `https` endpoints or TLS options use TLS with the system roots
///   (`insecure_skip_verify` is `CONFIG`: tonic cannot skip verification).
///
/// Compression (gzip) and the export timeout apply to both.
pub fn tempo_exporter(config: &TracingConfig) -> Result<SpanExporter> {
    let timeout = config.batch.export_timeout;
    let gzip = config.compression == Compression::Gzip;
    let built = if config.protocol == Protocol::Grpc {
        use opentelemetry_otlp::tonic_types::metadata::MetadataMap;
        let endpoint = config
            .endpoint
            .clone()
            .unwrap_or_else(|| DEFAULT_OTLP_GRPC_ENDPOINT.to_owned());
        let mut headers = http::HeaderMap::new();
        for (k, v) in &config.headers {
            let name = http::header::HeaderName::from_bytes(k.as_bytes())
                .map_err(|_| Error::config(format!("invalid tracing header name {k:?}")))?;
            let value = http::header::HeaderValue::from_str(v)
                .map_err(|_| Error::config(format!("invalid value for tracing header {k:?}")))?;
            headers.insert(name, value);
        }
        let mut b = SpanExporter::builder()
            .with_tonic()
            .with_timeout(timeout)
            .with_metadata(MetadataMap::from_headers(headers))
            .with_endpoint(endpoint.clone());
        if endpoint.starts_with("https://") || config.tls.is_some() {
            b = b.with_tls_config(grpc_tls(&config.tls.clone().unwrap_or_default())?);
        }
        if gzip {
            b = b.with_compression(opentelemetry_otlp::Compression::Gzip);
        }
        b.build()
    } else {
        let endpoint = config
            .endpoint
            .as_deref()
            .unwrap_or(DEFAULT_OTLP_HTTP_ENDPOINT);
        let mut b = SpanExporter::builder()
            .with_http()
            .with_timeout(timeout)
            .with_headers(
                config
                    .headers
                    .clone()
                    .into_iter()
                    .collect::<HashMap<_, _>>(),
            )
            .with_endpoint(otlp_http_traces_url(endpoint));
        if let Some(tls) = &config.tls {
            b = b.with_http_client(http_client(tls, timeout)?);
        }
        if gzip {
            b = b.with_compression(opentelemetry_otlp::Compression::Gzip);
        }
        b.build()
    };
    built.map_err(|e| Error::config(format!("cannot build the OTLP exporter: {e}")))
}
