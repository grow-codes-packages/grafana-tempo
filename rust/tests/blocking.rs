// Taken over from sdk-kit 0.1.0 (header removed): the skeleton test targeted the demo
// service; this tests the blocking Tempo client against recorded Tempo responses.
//! Blocking client (feature `blocking`).
#![cfg(feature = "blocking")]

mod support;

use std::time::Duration;

use grow_grafana_tempo as sdk;
use sdk::blocking::TempoClient as Client;
use sdk::core::ErrorCode;
use sdk::tracing::{DependencyOptions, TraceSearchQuery, WaitForTraceOptions};
use sdk::{
    GetTraceOptions, MetricsRange, SearchRequest, SearchTagValuesOptions, SearchTagsOptions,
    TagScope, TempoConfig,
};
use support::{builder, mount};
use wiremock::MockServer;

const SEED: &str = "5b8efff798038103d269b633813fc60c";

#[test]
fn blocking_client() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let server = rt.block_on(async {
        let server = MockServer::start().await;
        for f in [
            "ready.ok",
            "buildInfo.ok",
            "echo.ok",
            "searchTags.v2-scope-intrinsic",
            "getTrace.seed-v2",
            "search.traceql-service",
            "searchTagValues.v2-service-name",
            "searchTagValues.v2-name-filtered",
            "traceQLMetrics.count-over-time",
        ] {
            mount(&server, f).await;
        }
        server
    });
    let c = Client::new(builder(&server.uri()).build().unwrap()).unwrap();
    assert!(c.health().unwrap().healthy);
    assert!(c.ping().unwrap() > Duration::ZERO);
    assert_eq!(c.server_version().unwrap(), "3.1.1");
    assert_eq!(c.build_info().unwrap().version, "3.1.1");
    assert_eq!(c.echo().unwrap(), "echo");
    assert!(c.ready().unwrap());
    assert!(!c.async_client().is_closed());
    assert_eq!(c.block_on(async { 7 }), 7);

    let q = c.query();
    let t = q.get_trace(SEED).unwrap().unwrap();
    assert_eq!(t.spans.len(), 3);
    assert!(q
        .get_trace_with(SEED, GetTraceOptions::default())
        .unwrap()
        .is_some());
    assert!(!q
        .search(&SearchRequest::new("{}"))
        .unwrap()
        .traces
        .is_empty());
    assert!(!q
        .search_traces(&TraceSearchQuery::default())
        .unwrap()
        .is_empty());
    assert!(q
        .search_tags(&SearchTagsOptions::scope(TagScope::Intrinsic))
        .unwrap()
        .contains_key("intrinsic"));
    assert!(!q
        .search_tag_values("resource.service.name", &SearchTagValuesOptions::default())
        .unwrap()
        .is_empty());
    assert!(!q.list_services().unwrap().is_empty());
    assert!(!q.list_operations("grow-sdk-seed-api").unwrap().is_empty());
    assert_eq!(
        q.get_dependencies(DependencyOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Unsupported
    );
    assert_eq!(
        q.wait_for_trace(SEED, WaitForTraceOptions::default())
            .unwrap()
            .trace_id,
        SEED
    );
    assert!(!q
        .traceql_metrics(
            "{} | count_over_time()",
            MetricsRange::since(Duration::from_secs(60))
        )
        .unwrap()
        .series
        .is_empty());
    assert_eq!(q.echo().unwrap(), "echo");
    assert!(q.ready().unwrap());
    assert_eq!(q.build_info().unwrap().version, "3.1.1");

    c.close();
    c.close();
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
    let _ = Client::from_env();
    let bad = TempoConfig::builder()
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(Client::new(bad).unwrap_err().code(), ErrorCode::Config);
}
