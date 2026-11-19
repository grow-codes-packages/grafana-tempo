// Created by sdk-kit 0.1.0; owned by this repository.
//! Integration suite against the real service (Docker): client round trips and the
//! conformance scenarios for `core` and `tracing`.

use std::time::Duration;

use grow_grafana_tempo as sdk;
use grow_grafana_tempo_integration as support;
use sdk::testing::tracing::{TracingHandler, TracingTarget};
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::tracing::{TraceBatchConfig, TracingConfig};
use sdk::{init_tracing, ErrorCode};
use support::{conformance_dir, load_bootstrap_env, VisibleQuery};

use sdk::blocking::TempoClient as BlockingClient;

use sdk::TempoClient as Client;
use sdk::TempoConfig as Config;

const CONFORMANCE_SERVICE: &str = "grow-conformance-rust";

fn client() -> Client {
    load_bootstrap_env();
    Client::new(Config::from_env().expect("config from env")).expect("client")
}

#[tokio::test]
async fn client_round_trip() {
    let c = client();
    let health = c.health().await.expect("health");
    assert!(health.healthy, "service not healthy: {health:?}");
    c.ping().await.expect("tenant-checked ping");
    match c.server_version().await {
        Ok(v) => assert!(!v.is_empty()),
        Err(e) => assert_eq!(e.code(), ErrorCode::Unsupported, "{e}"),
    }
    c.close().await;
    c.close().await;
}

#[test]
fn blocking_round_trip() {
    load_bootstrap_env();
    let c = BlockingClient::from_env().expect("client");
    assert!(c.health().expect("health").healthy);
    c.ping().expect("ping");
    c.close();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance() {
    load_bootstrap_env();
    let handler = TracingHandler::new(|| async {
        let client = Client::from_env()?;
        let handle = init_tracing(
            TracingConfig::new(CONFORMANCE_SERVICE)
                .with_set_global(false)
                .with_batch(TraceBatchConfig {
                    scheduled_delay: Duration::from_millis(200),
                    ..TraceBatchConfig::default()
                }),
        )?;
        Ok(TracingTarget {
            handle,
            query: Some(VisibleQuery::new(client.query(), CONFORMANCE_SERVICE) as _),
        })
    });
    let runner = ConformanceRunner::new(conformance_dir())
        // "dependencies" is not declared: Tempo has no dependency graph API
        // (docs/compatibility.md).
        .capabilities(["version", "query", "services"])
        .var("serviceName", CONFORMANCE_SERVICE)
        .handler(CoreHandler::new(Client::from_env))
        .handler(handler);
    let report = runner.run().await.expect("scenarios load");
    println!("{report}");
    report.assert_success();
    assert_eq!(report.passed(), 3, "{report}");
}
