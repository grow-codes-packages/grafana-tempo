// Taken over from sdk-kit 0.1.0 (header removed): the skeleton tests targeted the demo
// service; these test the Tempo client surface against recorded Tempo responses.
//! `TempoClient` and `TempoConfig`: health, ping, server version, probes, close, tenant and
//! token handling, environment precedence.

mod support;

use std::time::Duration;

use grow_grafana_tempo as sdk;
use sdk::core::{EnvReader, ErrorCode};
use sdk::{validate_tenant_id, TempoClient, TempoConfig, TENANT_HEADER};
use support::{builder, client, closed_port, mount, response, tenant_client};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[tokio::test]
async fn health_ready_and_unhealthy() {
    let server = MockServer::start().await;
    mount(&server, "ready.ok").await;
    let c = client(&server);
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.status, "ok");
    assert_eq!(h.details["ready"], "ready");

    let server = MockServer::start().await;
    Mock::given(path("/ready"))
        .respond_with(ResponseTemplate::new(503).set_body_string("Ingester not ready: waiting\n"))
        .mount(&server)
        .await;
    let h = client(&server).health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unhealthy");
    assert_eq!(h.details["httpStatus"], "503");
    assert_eq!(h.details["reason"], "Ingester not ready: waiting");

    // 200 with an unexpected body is not ready.
    let server = MockServer::start().await;
    Mock::given(path("/ready"))
        .respond_with(ResponseTemplate::new(200).set_body_string("starting"))
        .mount(&server)
        .await;
    assert!(!client(&server).health().await.unwrap().healthy);

    let unreachable = TempoClient::new(
        builder(&format!("http://127.0.0.1:{}", closed_port()))
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = unreachable.health().await.unwrap();
    assert_eq!(h.status, "unreachable");

    let c = client(&server);
    c.close().await;
    assert_eq!(c.health().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn ping_sends_tenant_token_and_maps_no_org_id() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v2/search/tags"))
        .and(query_param("scope", "intrinsic"))
        .and(header(TENANT_HEADER, "tenant-a"))
        .and(header("authorization", "Bearer proxy-secret-1"))
        .respond_with(response("searchTags.v2-scope-intrinsic"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/search/tags"))
        .respond_with(response("searchTags.mt-no-tenant-401"))
        .mount(&server)
        .await;
    let ok = TempoClient::new(
        builder(&server.uri())
            .tenant_id(" tenant-a ")
            .token("proxy-secret-1")
            .build()
            .unwrap(),
    )
    .unwrap();
    assert!(ok.ping().await.unwrap() > Duration::ZERO);
    assert_eq!(ok.config().tenant_id(), Some("tenant-a"));
    assert!(!format!("{:?}", ok.config()).contains("proxy-secret-1"));

    let err = client(&server).ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert_eq!(err.http_status(), Some(401));
    assert_eq!(
        err.message(),
        "ping: tenant required (X-Scope-OrgID) (HTTP 401)"
    );

    // Other 401s (an auth proxy) keep their message.
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/search/tags"))
        .respond_with(ResponseTemplate::new(401).set_body_string("bad token"))
        .mount(&server)
        .await;
    let err = client(&server).ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert!(err.message().contains("bad token"), "{err}");
}

#[tokio::test]
async fn server_version_and_probes() {
    let server = MockServer::start().await;
    mount(&server, "buildInfo.ok").await;
    mount(&server, "echo.ok").await;
    mount(&server, "ready.ok").await;
    let c = client(&server);
    assert_eq!(c.server_version().await.unwrap(), "3.1.1");
    let info = c.build_info().await.unwrap();
    assert_eq!(info.revision, "9db7df79d");
    assert_eq!(info.go_version, "go1.27.3");
    assert_eq!(info.build_user, "");
    assert_eq!(c.echo().await.unwrap(), "echo");
    assert!(c.ready().await.unwrap());

    // Fallback to /status/version text when buildinfo is missing.
    let server = MockServer::start().await;
    Mock::given(path("/api/status/buildinfo"))
        .respond_with(ResponseTemplate::new(404).set_body_string("404 page not found"))
        .mount(&server)
        .await;
    mount(&server, "serverVersion.status-version-text").await;
    assert_eq!(client(&server).server_version().await.unwrap(), "3.1.1");

    // buildinfo without a version also falls back; unrecognised text is UNAVAILABLE.
    let server = MockServer::start().await;
    Mock::given(path("/api/status/buildinfo"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"version": ""})))
        .mount(&server)
        .await;
    Mock::given(path("/status/version"))
        .respond_with(ResponseTemplate::new(200).set_body_string("no version here"))
        .mount(&server)
        .await;
    let err = client(&server).server_version().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);

    // Neither route → UNSUPPORTED; other errors pass through.
    let server = MockServer::start().await;
    let c = client(&server);
    assert_eq!(
        c.server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    let server = MockServer::start().await;
    Mock::given(path("/api/status/buildinfo"))
        .respond_with(ResponseTemplate::new(400).set_body_string("bad"))
        .mount(&server)
        .await;
    assert_eq!(
        client(&server).server_version().await.unwrap_err().code(),
        ErrorCode::Validation
    );
    let server = MockServer::start().await;
    Mock::given(path("/api/status/buildinfo"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path("/status/version"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&server)
        .await;
    assert_eq!(
        client(&server).server_version().await.unwrap_err().code(),
        ErrorCode::PermissionDenied
    );
}

#[tokio::test]
async fn ready_states() {
    let server = MockServer::start().await;
    Mock::given(path("/ready"))
        .respond_with(ResponseTemplate::new(503).set_body_string("not ready"))
        .mount(&server)
        .await;
    assert!(!client(&server).ready().await.unwrap());
    let server = MockServer::start().await;
    Mock::given(path("/ready"))
        .respond_with(ResponseTemplate::new(500).set_body_string("boom"))
        .mount(&server)
        .await;
    assert_eq!(
        client(&server).ready().await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
}

#[tokio::test]
async fn close_is_idempotent_and_runs_hooks() {
    let server = MockServer::start().await;
    mount(&server, "ready.ok").await;
    let c = tenant_client(&server, "t1");
    let flag = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let f = flag.clone();
    c.on_close(move || {
        Box::pin(async move {
            f.store(true, std::sync::atomic::Ordering::SeqCst);
        })
    });
    assert!(!c.is_closed());
    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert!(flag.load(std::sync::atomic::Ordering::SeqCst));
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert!(c
        .transport()
        .user_agent()
        .contains("grow-codes-grafana-tempo"));
}

#[test]
fn config_defaults_and_validation() {
    let c = TempoConfig::builder().build().unwrap();
    assert_eq!(c.url().as_str(), "http://127.0.0.1:3200/");
    assert!(c.token().is_none());
    assert!(c.tenant_id().is_none());
    assert_eq!(c.common().url().as_str(), "http://127.0.0.1:3200/");

    let err = TempoConfig::builder().token("  ").build().unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    let err = TempoConfig::builder()
        .tenant_id("bad/tenant")
        .build()
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    assert!(!err.message().contains("bad/tenant"));

    assert!(validate_tenant_id("tenant-a").is_ok());
    assert!(validate_tenant_id("tenant-b|tenant-a").is_ok());
    assert!(validate_tenant_id("a!-_.*'()Z9").is_ok());
    for bad in ["", "a||b", ".", "..", "a b", "x/y"] {
        assert!(validate_tenant_id(bad).is_err(), "{bad:?}");
    }
    assert!(validate_tenant_id(&"x".repeat(151)).is_err());
    assert!(validate_tenant_id(&"x".repeat(150)).is_ok());

    // Every builder setter.
    let c = TempoConfig::builder()
        .url("https://tempo.example:3200")
        .connect_timeout(Duration::from_secs(1))
        .request_timeout(Duration::from_secs(2))
        .total_timeout(Duration::from_secs(3))
        .max_retries(1)
        .tls(Default::default())
        .ca_cert_path("/tmp/ca.pem")
        .insecure_skip_verify(true)
        .proxy("http://proxy:3128")
        .header("x-extra", "1")
        .user_agent_suffix("it")
        .hooks(std::sync::Arc::new(NoHooks))
        .common(|b| b.max_retries(2))
        .build()
        .unwrap();
    assert_eq!(c.common().connect_timeout(), Duration::from_secs(1));
    assert_eq!(c.common().retry().max_retries, 2);
    assert_eq!(c.common().proxy(), Some("http://proxy:3128"));
}

struct NoHooks;
impl sdk::core::Hooks for NoHooks {}

#[test]
fn config_from_env_precedence() {
    let env = EnvReader::from_map([
        ("TEMPO_URL", "http://vendor:3200"),
        ("GROW_TEMPO_URL", "http://alias:3200"),
        ("TEMPO_TENANT_ID", "vendor-tenant"),
        ("GROW_TEMPO_TOKEN", "alias-token"),
        ("GROW_TEMPO_CONNECT_TIMEOUT", "2s"),
        ("GROW_TEMPO_MAX_RETRIES", "5"),
        ("GROW_TEMPO_PROXY", "http://alias-proxy:3128"),
    ]);
    let c = TempoConfig::builder().build_with_env(&env).unwrap();
    assert_eq!(c.url().as_str(), "http://vendor:3200/");
    assert_eq!(c.tenant_id(), Some("vendor-tenant"));
    assert_eq!(c.token().unwrap().expose_secret(), "alias-token");
    assert_eq!(c.common().connect_timeout(), Duration::from_secs(2));
    assert_eq!(c.common().retry().max_retries, 5);
    assert_eq!(c.common().proxy(), Some("http://alias-proxy:3128"));

    let env = EnvReader::from_map([
        ("GROW_GRAFANA_TEMPO_URL", "http://grow:3200"),
        ("TEMPO_URL", "http://vendor:3200"),
        ("GROW_GRAFANA_TEMPO_TENANT_ID", " grow-tenant "),
        ("GROW_TEMPO_TENANT_ID", "alias-tenant"),
        ("GROW_GRAFANA_TEMPO_TOKEN", "grow-token"),
        ("GROW_GRAFANA_TEMPO_CONNECT_TIMEOUT", "3s"),
        ("GROW_TEMPO_CONNECT_TIMEOUT", "2s"),
    ]);
    let c = TempoConfig::builder().build_with_env(&env).unwrap();
    assert_eq!(c.url().as_str(), "http://grow:3200/");
    assert_eq!(c.tenant_id(), Some("grow-tenant"));
    assert_eq!(c.token().unwrap().expose_secret(), "grow-token");
    assert_eq!(c.common().connect_timeout(), Duration::from_secs(3));

    // Explicit values win.
    let c = TempoConfig::builder()
        .url("http://explicit:3200")
        .tenant_id("explicit")
        .token("explicit-token")
        .build_with_env(&env)
        .unwrap();
    assert_eq!(c.url().as_str(), "http://explicit:3200/");
    assert_eq!(c.tenant_id(), Some("explicit"));
    assert_eq!(c.token().unwrap().expose_secret(), "explicit-token");

    let env = EnvReader::from_map([("GROW_TEMPO_MAX_RETRIES", "lots")]);
    let err = TempoConfig::builder().build_with_env(&env).unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    let env = EnvReader::from_map([("TEMPO_TENANT_ID", "bad tenant")]);
    assert!(TempoConfig::builder().build_with_env(&env).is_err());
    let _ = TempoConfig::from_env();
    let _ = TempoClient::from_env();
}
