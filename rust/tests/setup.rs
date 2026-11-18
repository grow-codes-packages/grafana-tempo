//! Tracing setup for Tempo: config resolution from the environment, the OTLP exporter and an
//! end-to-end export to a mock OTLP/HTTP receiver.

use std::path::PathBuf;
use std::time::Duration;

use grow_grafana_tempo as sdk;
use opentelemetry::trace::{Span as _, Tracer as _};
use sdk::core::{EnvReader, ErrorCode, TlsConfig};
use sdk::tracing::{
    init_tracing_with, Compression, Protocol, SamplerKind, TraceBatchConfig, TracingConfig,
};
use sdk::{
    init_tracing, otlp_http_traces_url, parse_key_value_list, tempo_exporter, tempo_tracing_config,
    with_tenant, DEFAULT_OTLP_GRPC_ENDPOINT, DEFAULT_OTLP_HTTP_ENDPOINT,
};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn env<const N: usize>(vars: [(&str, &str); N]) -> EnvReader {
    EnvReader::from_map(vars)
}

fn resolve<const N: usize>(cfg: TracingConfig, vars: [(&str, &str); N]) -> TracingConfig {
    tempo_tracing_config(cfg, &env(vars)).unwrap()
}

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

#[test]
fn defaults() {
    let c = resolve(TracingConfig::new("svc"), []);
    assert_eq!(c.protocol, Protocol::HttpProtobuf);
    assert_eq!(c.endpoint.as_deref(), Some(DEFAULT_OTLP_HTTP_ENDPOINT));
    assert!(c.headers.is_empty());
    let c = resolve(TracingConfig::new("svc").with_protocol(Protocol::Grpc), []);
    assert_eq!(c.endpoint.as_deref(), Some(DEFAULT_OTLP_GRPC_ENDPOINT));
    let c = resolve(
        TracingConfig::new("svc").with_protocol(Protocol::Native),
        [],
    );
    assert_eq!(c.protocol, Protocol::HttpProtobuf);
}

#[test]
fn endpoint_and_protocol_precedence() {
    let all = [
        ("GROW_GRAFANA_TEMPO_OTLP_ENDPOINT", "http://grow:4318"),
        (
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            "http://traces:4318/custom",
        ),
        ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://base:4318"),
    ];
    let c = resolve(TracingConfig::new("svc"), all);
    assert_eq!(c.endpoint.as_deref(), Some("http://grow:4318"));
    let c = resolve(TracingConfig::new("svc"), [all[1], all[2]]);
    assert_eq!(c.endpoint.as_deref(), Some("http://traces:4318/custom"));
    let c = resolve(TracingConfig::new("svc"), [all[2]]);
    assert_eq!(c.endpoint.as_deref(), Some("http://base:4318/v1/traces"));
    let c = resolve(
        TracingConfig::new("svc"),
        [all[2], ("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc")],
    );
    assert_eq!(c.protocol, Protocol::Grpc);
    assert_eq!(c.endpoint.as_deref(), Some("http://base:4318"));
    let c = resolve(
        TracingConfig::new("svc").with_endpoint("http://explicit:4318"),
        all,
    );
    assert_eq!(c.endpoint.as_deref(), Some("http://explicit:4318"));

    let c = resolve(
        TracingConfig::new("svc"),
        [
            ("GROW_GRAFANA_TEMPO_OTLP_PROTOCOL", "native"),
            ("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "grpc"),
        ],
    );
    assert_eq!(c.protocol, Protocol::HttpProtobuf);
    let c = resolve(
        TracingConfig::new("svc").with_protocol(Protocol::Grpc),
        [("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")],
    );
    assert_eq!(
        c.protocol,
        Protocol::Grpc,
        "an explicit non-default protocol wins"
    );
    let err = tempo_tracing_config(
        TracingConfig::new("svc"),
        &env([("OTEL_EXPORTER_OTLP_PROTOCOL", "carrier-pigeon")]),
    )
    .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    assert!(err.message().contains("OTEL_EXPORTER_OTLP_PROTOCOL"));
    assert!(!err.message().contains("carrier-pigeon"));
}

#[test]
fn headers_and_tenant() {
    let c = resolve(
        TracingConfig::new("svc").with_header("authorization", "Bearer explicit"),
        [
            (
                "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
                "Authorization=Bearer%20env, x-team = core ,",
            ),
            ("OTEL_EXPORTER_OTLP_HEADERS", "x-ignored=1"),
            ("TEMPO_TENANT_ID", "tenant-a"),
        ],
    );
    assert_eq!(c.headers["authorization"], "Bearer explicit");
    assert!(!c.headers.contains_key("Authorization"));
    assert_eq!(c.headers["x-team"], "core");
    assert!(!c.headers.contains_key("x-ignored"));
    assert_eq!(c.headers["X-Scope-OrgID"], "tenant-a");

    // GROW var beats the vendor ones; an explicit tenant header wins over both.
    let c = resolve(
        TracingConfig::new("svc"),
        [
            ("GROW_GRAFANA_TEMPO_TENANT_ID", "grow"),
            ("TEMPO_TENANT_ID", "vendor"),
            ("GROW_TEMPO_TENANT_ID", "alias"),
        ],
    );
    assert_eq!(c.headers["X-Scope-OrgID"], "grow");
    let c = resolve(
        TracingConfig::new("svc").with_header("x-scope-orgid", "mine"),
        [("TEMPO_TENANT_ID", "vendor")],
    );
    assert_eq!(c.headers["x-scope-orgid"], "mine");
    assert_eq!(c.headers.len(), 1);
    let c = resolve(
        TracingConfig::new("svc"),
        [("GROW_TEMPO_TENANT_ID", "alias")],
    );
    assert_eq!(c.headers["X-Scope-OrgID"], "alias");

    for vars in [
        [("TEMPO_TENANT_ID", "bad/tenant")],
        [("OTEL_EXPORTER_OTLP_HEADERS", "novalue")],
        [("OTEL_EXPORTER_OTLP_HEADERS", "=v")],
        [("OTEL_EXPORTER_OTLP_HEADERS", "k=%zz")],
    ] {
        let err = tempo_tracing_config(TracingConfig::new("svc"), &env(vars)).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{vars:?}");
    }

    let t = with_tenant(
        TracingConfig::new("svc").with_header("X-SCOPE-ORGID", "old"),
        " new ",
    );
    assert_eq!(t.headers.len(), 1);
    assert_eq!(t.headers["X-Scope-OrgID"], "new");
    assert!(with_tenant(t, "").headers.is_empty());
}

#[test]
fn service_resource_sampler_compression() {
    let c = resolve(
        TracingConfig::new(""),
        [
            ("OTEL_SERVICE_NAME", " from-env "),
            (
                "OTEL_RESOURCE_ATTRIBUTES",
                "service.name=from-res,team=core,tier=web",
            ),
            ("OTEL_TRACES_SAMPLER", "traceidratio"),
            ("OTEL_TRACES_SAMPLER_ARG", "0.25"),
            ("GROW_GRAFANA_TEMPO_OTLP_COMPRESSION", "none"),
        ],
    );
    assert_eq!(c.service_name, "from-env");
    assert_eq!(c.resource_attributes["team"], "core");
    assert!(!c.resource_attributes.contains_key("service.name"));
    assert_eq!(c.sampler, SamplerKind::TraceIdRatio);
    assert_eq!(c.sampler_ratio, 0.25);
    assert_eq!(c.compression, Compression::None);

    let c = resolve(
        TracingConfig::new("").with_resource_attribute("tier", "explicit"),
        [
            ("OTEL_RESOURCE_ATTRIBUTES", "service.name=from-res,tier=web"),
            ("OTEL_EXPORTER_OTLP_COMPRESSION", "GZIP"),
        ],
    );
    assert_eq!(c.service_name, "from-res");
    assert_eq!(c.resource_attributes["tier"], "explicit");
    assert_eq!(c.compression, Compression::Gzip);

    let c = resolve(
        TracingConfig::new("explicit").with_sampler(SamplerKind::AlwaysOff, 0.5),
        [
            ("OTEL_SERVICE_NAME", "env"),
            ("OTEL_TRACES_SAMPLER", "always_on"),
            ("OTEL_TRACES_SAMPLER_ARG", "0.1"),
        ],
    );
    assert_eq!(c.service_name, "explicit");
    assert_eq!(c.sampler, SamplerKind::AlwaysOff);
    assert_eq!(c.sampler_ratio, 0.5);

    for vars in [
        [("OTEL_TRACES_SAMPLER", "sometimes")],
        [("OTEL_TRACES_SAMPLER_ARG", "half")],
        [("OTEL_TRACES_SAMPLER_ARG", "2")],
        [("OTEL_EXPORTER_OTLP_COMPRESSION", "zstd")],
        [("OTEL_RESOURCE_ATTRIBUTES", "broken")],
        [("OTEL_SERVICE_NAME", "  ")],
    ] {
        let err = tempo_tracing_config(TracingConfig::new(""), &env(vars)).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{vars:?}");
    }
}

#[test]
fn helpers() {
    assert_eq!(
        otlp_http_traces_url("http://tempo:4318"),
        "http://tempo:4318/v1/traces"
    );
    assert_eq!(
        otlp_http_traces_url("http://tempo:4318/"),
        "http://tempo:4318/v1/traces"
    );
    assert_eq!(
        otlp_http_traces_url("http://gw/otlp/v1/traces"),
        "http://gw/otlp/v1/traces"
    );
    assert_eq!(otlp_http_traces_url("not a url"), "not a url");
    let m = parse_key_value_list("a=1, b%20c = x%2Cy ,,", "V").unwrap();
    assert_eq!(m["a"], "1");
    assert_eq!(m["b c"], "x,y");
    let err = parse_key_value_list("a=%e2%28", "MY_VAR").unwrap_err();
    assert!(err.message().contains("MY_VAR"));
    assert!(parse_key_value_list("a=%4", "V").is_err());
}

#[tokio::test]
async fn exporters() {
    let tls = TlsConfig {
        ca_cert_path: Some(fixture("ca.pem")),
        client_cert_path: Some(fixture("client.pem")),
        client_key_path: Some(fixture("client.key")),
        ..TlsConfig::default()
    };
    let http = TracingConfig::new("svc");
    tempo_exporter(&http).unwrap();
    tempo_exporter(&http.clone().with_tls(tls.clone())).unwrap();
    tempo_exporter(
        &http
            .clone()
            .with_endpoint("https://tempo:4318")
            .with_compression(Compression::None)
            .with_tls(TlsConfig {
                ca_cert_pem: Some(std::fs::read_to_string(fixture("ca.pem")).unwrap()),
                insecure_skip_verify: true,
                ..TlsConfig::default()
            }),
    )
    .unwrap();
    let grpc = TracingConfig::new("svc")
        .with_protocol(Protocol::Grpc)
        .with_header("X-Scope-OrgID", "tenant-a");
    tempo_exporter(&grpc).unwrap();
    tempo_exporter(&grpc.clone().with_endpoint("https://tempo:4317")).unwrap();
    tempo_exporter(
        &grpc
            .clone()
            .with_tls(tls.clone())
            .with_compression(Compression::None),
    )
    .unwrap();

    let bad = |cfg: TracingConfig| {
        let err = tempo_exporter(&cfg).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{cfg:?}");
    };
    bad(grpc.clone().with_tls(TlsConfig {
        insecure_skip_verify: true,
        ..TlsConfig::default()
    }));
    bad(grpc.clone().with_header("bad header", "x"));
    bad(grpc.clone().with_header("x-ok", "bad\nvalue"));
    bad(http.clone().with_tls(TlsConfig {
        ca_cert_path: Some("/nonexistent/ca.pem".into()),
        ..TlsConfig::default()
    }));
    bad(http.clone().with_tls(TlsConfig {
        client_cert_path: Some(fixture("client.pem")),
        ..TlsConfig::default()
    }));
    bad(http.clone().with_tls(TlsConfig {
        client_cert_path: Some(fixture("client.pem")),
        client_key_path: Some("/nonexistent/key".into()),
        ..TlsConfig::default()
    }));
    bad(http.clone().with_tls(TlsConfig {
        client_cert_path: Some(fixture("ca.pem")),
        client_key_path: Some(fixture("ca.pem")),
        ..TlsConfig::default()
    }));
    bad(grpc.clone().with_tls(TlsConfig {
        client_cert_path: Some("/nonexistent/c".into()),
        client_key_path: Some("/nonexistent/k".into()),
        ..TlsConfig::default()
    }));
    bad(grpc.with_tls(TlsConfig {
        ca_cert_path: Some("/nonexistent/ca".into()),
        ..TlsConfig::default()
    }));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn exports_with_the_tenant_header() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/traces"))
        .and(header("x-scope-orgid", "tenant-a"))
        .and(header("content-type", "application/x-protobuf"))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(serde_json::json!({"partialSuccess": {}})),
        )
        .expect(1..)
        .mount(&server)
        .await;
    let batch = TraceBatchConfig {
        scheduled_delay: Duration::from_millis(50),
        ..TraceBatchConfig::default()
    };
    let cfg = tempo_tracing_config(
        TracingConfig::new("setup-test")
            .with_set_global(false)
            .with_batch(batch)
            .with_compression(Compression::None),
        &env([
            ("GROW_GRAFANA_TEMPO_OTLP_ENDPOINT", server.uri().as_str()),
            ("GROW_GRAFANA_TEMPO_TENANT_ID", "tenant-a"),
        ]),
    )
    .unwrap();
    let handle = init_tracing_with(cfg, tempo_exporter).unwrap();
    let mut span = handle.tracer("setup", None).start("exported");
    span.end();
    handle.force_flush(None).await.unwrap();
    handle.shutdown().await.unwrap();
    server.verify().await;

    // init_tracing reads the process environment; explicit values make it deterministic.
    let handle = init_tracing(with_tenant(
        TracingConfig::new("setup-env")
            .with_set_global(false)
            .with_endpoint(server.uri()),
        "tenant-a",
    ))
    .unwrap();
    handle.shutdown().await.unwrap();
    let err = init_tracing(
        TracingConfig::new("x")
            .with_set_global(false)
            .with_endpoint("ftp://x"),
    )
    .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
}
