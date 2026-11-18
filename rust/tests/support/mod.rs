//! Shared helpers for the Tempo service tests: recorded fixtures (from the real Tempo 3.1.1
//! container) served by a wiremock server.
#![allow(dead_code)]

use std::time::Duration;

use grow_grafana_tempo as sdk;
use sdk::core::{RandomSource, RetryPolicy};
use sdk::{TempoClient, TempoConfig, TempoConfigBuilder};
use serde_json::Value;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// A recorded fixture (`tests/fixtures/tempo/<name>.json`).
pub fn fixture(name: &str) -> Value {
    let p = format!(
        "{}/tests/fixtures/tempo/{name}.json",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(&p).unwrap_or_else(|_| panic!("{p}")))
        .expect("fixture JSON")
}

/// The fixture's response body.
pub fn body(name: &str) -> Value {
    fixture(name)["response"]["body"].clone()
}

/// The response template of a fixture (text bodies as text/plain, JSON as JSON).
pub fn response(name: &str) -> ResponseTemplate {
    let f = fixture(name);
    let status = f["response"]["status"].as_u64().unwrap() as u16;
    let r = ResponseTemplate::new(status);
    match &f["response"]["body"] {
        Value::String(s) => r.set_body_raw(s.clone().into_bytes(), "text/plain; charset=utf-8"),
        Value::Null => r,
        v => r.set_body_json(v),
    }
}

/// Mounts a fixture on its recorded path (any query string).
pub async fn mount(server: &MockServer, name: &str) {
    let f = fixture(name);
    let p = f["request"]["path"].as_str().unwrap().to_owned();
    Mock::given(method("GET"))
        .and(path(p))
        .respond_with(response(name))
        .mount(server)
        .await;
}

/// Mounts a fixture on its recorded path and every recorded query parameter.
pub async fn mount_exact(server: &MockServer, name: &str) {
    let f = fixture(name);
    let p = f["request"]["path"].as_str().unwrap().to_owned();
    let mut m = Mock::given(method("GET")).and(path(p));
    if let Some(q) = f["request"]["query"].as_object() {
        for (k, v) in q {
            m = m.and(query_param(k.as_str(), v.as_str().unwrap()));
        }
    }
    m.respond_with(response(name)).mount(server).await;
}

/// Fast deterministic retries.
pub fn fast_retry() -> RetryPolicy {
    RetryPolicy {
        initial_backoff: Duration::from_millis(5),
        max_backoff: Duration::from_millis(20),
        ..RetryPolicy::default()
    }
    .with_random(RandomSource::fixed(0.0))
}

/// Builder pointing at `url` with fast retries.
pub fn builder(url: &str) -> TempoConfigBuilder {
    TempoConfig::builder().url(url).retry(fast_retry())
}

/// Client pointing at the mock server.
pub fn client(server: &MockServer) -> TempoClient {
    TempoClient::new(builder(&server.uri()).build().unwrap()).unwrap()
}

/// Client with a tenant.
pub fn tenant_client(server: &MockServer, tenant: &str) -> TempoClient {
    TempoClient::new(builder(&server.uri()).tenant_id(tenant).build().unwrap()).unwrap()
}

/// A local port with nothing listening.
pub fn closed_port() -> u16 {
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    drop(l);
    port
}
