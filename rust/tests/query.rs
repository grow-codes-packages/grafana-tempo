//! `TempoQueryClient` against recorded Tempo 3.1.1 responses.

mod support;

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_grafana_tempo as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::{
    DependencyOptions, SpanKind, StatusCode, TraceQueryClient, TraceSearchQuery,
    WaitForTraceOptions,
};
use sdk::{
    search_traces_traceql, GetTraceOptions, MetricsRange, SearchRequest, SearchTagValuesOptions,
    SearchTagsOptions, TagScope, TagValue, TraceQL, TAG_SCOPES,
};
use serde_json::json;
use support::{client, mount, mount_exact, response, tenant_client};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

const SEED: &str = "5b8efff798038103d269b633813fc60c";
const VERIFY: &str = "00f74bc1b058a1a8b85f9ae0231e4001";

fn at(secs: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(secs)
}

#[tokio::test]
async fn get_trace_normalizes_the_seed_trace() {
    let server = MockServer::start().await;
    mount(&server, "getTrace.seed-v2").await;
    let q = client(&server).query();
    let t = q.get_trace(&SEED.to_uppercase()).await.unwrap().unwrap();
    assert_eq!(t.trace_id, SEED);
    assert_eq!(t.spans.len(), 3);
    let root = &t.spans[0];
    assert_eq!(root.name, "GET /seed");
    assert_eq!(root.kind, SpanKind::Server);
    assert_eq!(root.status.code, StatusCode::Ok);
    assert_eq!(root.service_name, "grow-sdk-seed-api");
    assert_eq!(root.trace_id, SEED);
    assert_eq!(root.span_id.len(), 16);
    assert!(root.parent_span_id.is_none());
    assert_eq!(root.attributes["http.response.status_code"], json!(200));
    assert_eq!(root.attributes["grow.seed"], json!(true));
    assert_eq!(root.events.len(), 1);
    assert_eq!(root.duration_micros, 1_000_000);
    assert_eq!(root.start_micros, 1_795_034_968_000_000);
    let db = &t.spans[2];
    assert_eq!(db.status.code, StatusCode::Error);
    assert_eq!(db.status.message.as_deref(), Some("seed failure"));
    assert_eq!(t.spans[1].links.len(), 1);
    assert_eq!(t.spans[1].links[0].trace_id.len(), 32);
    assert_eq!(
        t.spans[1].parent_span_id.as_deref(),
        Some(root.span_id.as_str())
    );
}

#[tokio::test]
async fn get_trace_variants() {
    let server = MockServer::start().await;
    mount_exact(&server, "getTrace.v2-with-range").await;
    let q = client(&server).query();
    // Range: start rounded down, end rounded up.
    let opts = GetTraceOptions::range(
        at(1_795_034_977),
        at(1_795_035_036) + Duration::from_millis(1),
    );
    let t = q.get_trace_with(VERIFY, opts).await.unwrap().unwrap();
    let root = t.spans.iter().find(|s| s.name == "GET /orders").unwrap();
    assert_eq!(root.attributes["http.request.method"], json!("GET"));
    assert_eq!(root.kind, SpanKind::Server);

    let err = q
        .get_trace_with(VERIFY, GetTraceOptions::range(at(10), at(5)))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    for bad in [
        "nothex",
        "",
        &"a".repeat(33),
        "00000000000000000000000000000000",
    ] {
        assert_eq!(
            q.get_trace(bad).await.unwrap_err().code(),
            ErrorCode::Validation,
            "{bad:?}"
        );
    }

    // v2 miss is 200 {"trace":{}} → None.
    let server = MockServer::start().await;
    mount(&server, "getTrace.v2-not-found").await;
    let q = client(&server).query();
    assert!(q
        .get_trace("00000000000000000000000000000001")
        .await
        .unwrap()
        .is_none());

    // Short ids are left-padded before the request.
    let server = MockServer::start().await;
    mount(&server, "getTrace.v2-ok").await;
    let q = client(&server).query();
    assert!(q
        .get_trace("f74bc1b058a1a8b85f9ae0231e4001")
        .await
        .unwrap()
        .is_some());
}

#[tokio::test]
async fn get_trace_falls_back_to_v1() {
    let server = MockServer::start().await;
    Mock::given(path(format!("/api/v2/traces/{VERIFY}")))
        .respond_with(ResponseTemplate::new(404).set_body_string("404 page not found"))
        .mount(&server)
        .await;
    mount(&server, "getTrace.v1-ok").await;
    mount(&server, "getTrace.v1-not-found").await;
    let q = client(&server).query();
    let t = q.get_trace(VERIFY).await.unwrap().unwrap();
    assert!(t.spans.len() >= 2);
    assert!(t
        .spans
        .windows(2)
        .all(|w| w[0].start_micros <= w[1].start_micros));
    // v1 404 (empty body) = miss.
    assert!(q
        .get_trace("00000000000000000000000000000001")
        .await
        .unwrap()
        .is_none());

    // v1 errors other than 404 pass through.
    let server = MockServer::start().await;
    Mock::given(path(format!("/api/v2/traces/{VERIFY}")))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path(format!("/api/traces/{VERIFY}")))
        .respond_with(ResponseTemplate::new(400).set_body_string("bad"))
        .mount(&server)
        .await;
    let err = client(&server).query().get_trace(VERIFY).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);

    // v1 body with old instrumentationLibrarySpans and numeric enums.
    let server = MockServer::start().await;
    Mock::given(path(format!("/api/v2/traces/{VERIFY}")))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path(format!("/api/traces/{VERIFY}")))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "batches": [{
                "resource": {"attributes": []},
                "instrumentationLibrarySpans": [{"spans": [{
                    "spanId": "a000000000000001", "name": "old", "kind": 3,
                    "startTimeUnixNano": 2000000, "endTimeUnixNano": "1000000",
                    "status": {"code": 2},
                    "attributes": [
                        {"key": "d", "value": {"doubleValue": 1.5}},
                        {"key": "ds", "value": {"doubleValue": "2.5"}},
                        {"key": "b", "value": {"bytesValue": "AQI="}},
                        {"key": "a", "value": {"arrayValue": {"values": [{"intValue": 1}, {"stringValue": "x"}]}}},
                        {"key": "k", "value": {"kvlistValue": {"values": [{"key": "in", "value": {"boolValue": false}}]}}},
                        {"key": "e", "value": {}},
                        {"key": "badint", "value": {"intValue": "nope"}},
                        {"key": "fint", "value": {"intValue": "3.0"}},
                        {"novalue": true}
                    ]
                }]}]
            }]
        })))
        .mount(&server)
        .await;
    let t = client(&server)
        .query()
        .get_trace(VERIFY)
        .await
        .unwrap()
        .unwrap();
    let s = &t.spans[0];
    assert_eq!(
        s.trace_id, VERIFY,
        "missing span traceId falls back to the requested id"
    );
    assert_eq!(s.span_id, "a000000000000001", "hex ids are accepted");
    assert_eq!(s.kind, SpanKind::Client);
    assert_eq!(s.status.code, StatusCode::Error);
    assert_eq!(s.duration_micros, 0);
    assert_eq!(s.service_name, "");
    assert_eq!(s.attributes["d"], json!(1.5));
    assert_eq!(s.attributes["ds"], json!(2.5));
    assert_eq!(s.attributes["b"], json!("AQI="));
    assert_eq!(s.attributes["a"], json!([1, "x"]));
    assert_eq!(s.attributes["k"], json!({"in": false}));
    assert_eq!(s.attributes["e"], json!(null));
    assert_eq!(s.attributes["badint"], json!(null));
    assert_eq!(s.attributes["fint"], json!(3));
}

#[tokio::test]
async fn multi_tenant_errors() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/traces/0af7651916cd43dd8448eb211c80319c"))
        .and(header("X-Scope-OrgID", "tenant-a"))
        .respond_with(response("getTrace.mt-tenant-a"))
        .mount(&server)
        .await;
    mount(&server, "getTrace.mt-no-tenant-401").await;
    let id = "0af7651916cd43dd8448eb211c80319c";
    let t = tenant_client(&server, "tenant-a")
        .query()
        .get_trace(id)
        .await
        .unwrap()
        .unwrap();
    assert!(!t.spans.is_empty());
    let err = client(&server).query().get_trace(id).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert!(
        err.message().contains("tenant required (X-Scope-OrgID)"),
        "{err}"
    );
    assert!(!err.is_retryable());

    let server = MockServer::start().await;
    mount(&server, "getTrace.mt-invalid-tenant").await;
    let err = client(&server).query().get_trace(id).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
}

#[tokio::test]
async fn search_maps_results() {
    let server = MockServer::start().await;
    mount_exact(&server, "search.builder-duration-select").await;
    let q = client(&server).query();
    let traceql = TraceQL::span([(
        "resource.service.name",
        "grow-sdk-verify-179503498127460-api",
    )])
    .and("duration", ">", "200ms")
    .select(["span.http.response.status_code"])
    .build()
    .unwrap();
    let req = SearchRequest::new(traceql)
        .range(at(1_795_034_947), at(1_795_035_067))
        .limit(20)
        .spss(10);
    let res = q.search(&req).await.unwrap();
    assert!(!res.traces.is_empty());
    let t = &res.traces[0];
    assert_eq!(t.trace_id.len(), 32);
    assert!(t.duration_micros > 0 && t.duration_micros % 1000 == 0);
    assert!(!t.span_sets.is_empty());
    let span = &t.span_sets[0].spans[0];
    assert_eq!(span.span_id.len(), 16);
    assert!(span.attributes.contains_key("http.response.status_code"));
    assert!(res.metrics.inspected_bytes > 0);
    assert!(t.service_stats.values().any(|s| s.span_count > 0));

    // Trimmed trace ids are padded back; spanSet (deprecated) is used without spanSets.
    let server = MockServer::start().await;
    Mock::given(path("/api/search"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "traces": [{
                "traceID": "B8E", "startTimeUnixNano": "2000", "durationMs": 7,
                "spanSet": {"spans": [{"spanID": "ABC", "durationNanos": "4000"}], "matched": 3}
            }, {"traceID": "1"}],
            "metrics": {"inspectedTraces": 2, "completedJobs": "1", "totalJobs": 1.0}
        })))
        .mount(&server)
        .await;
    let res = client(&server)
        .query()
        .search(&SearchRequest::default())
        .await
        .unwrap();
    let t = &res.traces[0];
    assert_eq!(t.trace_id, format!("{}b8e", "0".repeat(29)));
    assert_eq!(t.duration_micros, 7000);
    assert_eq!(t.start_micros, 2);
    assert_eq!(t.span_sets[0].matched, 3);
    assert_eq!(t.span_sets[0].spans[0].span_id, "0000000000000abc");
    assert_eq!(t.span_sets[0].spans[0].duration_micros, 4);
    assert_eq!(t.span_sets[0].spans[0].name, None);
    assert!(t.root_service_name.is_none());
    assert!(res.traces[1].span_sets.is_empty());
    assert_eq!(res.metrics.inspected_traces, 2);
    assert_eq!(res.metrics.completed_jobs, 1);
    assert_eq!(res.metrics.total_jobs, 1);
}

#[tokio::test]
async fn search_fixtures_and_errors() {
    let server = MockServer::start().await;
    mount(&server, "search.select-name-status").await;
    let res = client(&server)
        .query()
        .search(&SearchRequest::new("{ x } | select(name, status)"))
        .await
        .unwrap();
    assert_eq!(
        res.traces[0].span_sets[0].spans[0].name.as_deref(),
        Some("GET /health")
    );

    let server = MockServer::start().await;
    mount(&server, "search.invalid-traceql-400").await;
    let q = client(&server).query();
    let err = q.search(&SearchRequest::new("{ bad")).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(err.message().contains("invalid TraceQL query"), "{err}");
    assert_eq!(
        q.search(&SearchRequest::new("{}").limit(0))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert_eq!(
        q.search(&SearchRequest::new("{}").spss(0))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert_eq!(
        q.search(&SearchRequest::new("{}").range(at(10), at(1)))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    let built = SearchRequest::from_traceql(&TraceQL::where_("status", "=", "error")).unwrap();
    assert_eq!(built.traceql, "{ status = error }");
    assert!(SearchRequest::from_traceql(&TraceQL::where_("nope", "=", 1)).is_err());

    let server = MockServer::start().await;
    mount(&server, "search.mt-no-tenant-401").await;
    let err = client(&server)
        .query()
        .search(&SearchRequest::new("{}"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);

    // 5xx is retried, then UNAVAILABLE.
    let server = MockServer::start().await;
    Mock::given(path("/api/search"))
        .respond_with(ResponseTemplate::new(502).set_body_string("bad gateway"))
        .expect(4)
        .mount(&server)
        .await;
    let err = client(&server)
        .query()
        .search(&SearchRequest::new("{}"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
}

#[tokio::test]
async fn search_traces_maps_the_category_query() {
    let server = MockServer::start().await;
    let query = TraceSearchQuery::service("grow-sdk-verify-179503498127460-api")
        .operation("GET /orders")
        .tag("grow.run", "179503498127460")
        .limit(20);
    let query = TraceSearchQuery {
        min_duration: Some(Duration::from_millis(400)),
        max_duration: Some(Duration::from_millis(500)),
        start: Some(at(1_795_034_947)),
        end: Some(at(1_795_035_067)),
        ..query
    };
    let traceql = search_traces_traceql(&query).unwrap();
    assert_eq!(
        traceql,
        r#"{ resource.service.name = "grow-sdk-verify-179503498127460-api" && name = "GET /orders" && .grow.run = "179503498127460" && trace:duration >= 400000us && trace:duration <= 500000us }"#
    );
    Mock::given(method("GET"))
        .and(path("/api/search"))
        .and(query_param("q", traceql.as_str()))
        .and(query_param("limit", "20"))
        .and(query_param("start", "1795034947"))
        .respond_with(response("searchTraces.mapped-traceql"))
        .mount(&server)
        .await;
    let got = client(&server).query().search_traces(&query).await.unwrap();
    assert_eq!(got.len(), 1);
    assert_eq!(got[0].trace_id, VERIFY);
    assert_eq!(got[0].root_name.as_deref(), Some("GET /orders"));
    assert!(got[0].span_count >= 2);
    assert!(got[0].duration_micros >= 400_000);

    assert_eq!(
        search_traces_traceql(&TraceSearchQuery::default()).unwrap(),
        "{}"
    );
    let server = MockServer::start().await;
    mount(&server, "searchTraces.duration-excludes").await;
    let none = client(&server)
        .query()
        .search_traces(&TraceSearchQuery {
            limit: 0,
            ..TraceSearchQuery::default()
        })
        .await
        .unwrap();
    assert!(none.is_empty());
}

#[tokio::test]
async fn search_tags_scopes() {
    let server = MockServer::start().await;
    mount_exact(&server, "searchTags.v2-filtered-q").await;
    mount(&server, "searchTags.v2-all-scopes").await;
    let q = client(&server).query();
    let filter = r#"{ resource.service.name = "grow-sdk-verify-179503498127460-db" }"#;
    let opts = SearchTagsOptions::scope(TagScope::Span)
        .query(filter)
        .range(at(1_795_034_947), at(1_795_035_067));
    let tags = q.search_tags(&opts).await.unwrap();
    assert_eq!(tags.keys().collect::<Vec<_>>(), ["span"]);
    let all = q.search_tags(&SearchTagsOptions::default()).await.unwrap();
    assert!(all["resource"].contains(&"service.name".to_owned()));
    assert!(all.contains_key("intrinsic"));

    let err = q
        .search_tags(&SearchTagsOptions::default().query("{ bad"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);

    for s in TAG_SCOPES {
        assert_eq!(s.as_str().parse::<TagScope>().unwrap(), *s);
        assert_eq!(s.to_string(), s.as_str());
    }
    assert_eq!(
        "nope".parse::<TagScope>().unwrap_err().code(),
        ErrorCode::Validation
    );

    let server = MockServer::start().await;
    mount(&server, "searchTags.v2-scope-instrumentation").await;
    let empty = client(&server)
        .query()
        .search_tags(&SearchTagsOptions::scope(TagScope::Instrumentation))
        .await
        .unwrap();
    assert!(empty.is_empty());
}

#[tokio::test]
async fn search_tag_values_and_listings() {
    let server = MockServer::start().await;
    mount(&server, "searchTagValues.v2-int-typed").await;
    mount(&server, "searchTagValues.v2-quoted-tag-path").await;
    mount(&server, "searchTagValues.v2-service-name").await;
    mount(&server, "searchTagValues.v2-name-filtered").await;
    mount(&server, "searchTagValues.v2-status-intrinsic").await;
    mount(&server, "searchTagValues.v2-unknown-tag-empty").await;
    let q = client(&server).query();
    let vals = q
        .search_tag_values(
            "span.http.response.status_code",
            &SearchTagValuesOptions::query("{ resource.service.name = \"x\" }").range(at(1), at(2)),
        )
        .await
        .unwrap();
    assert_eq!(vals[0], TagValue::new("int", "200"));
    // A name with a space is quoted and sent as one path segment.
    let quoted = q
        .search_tag_values("span.grow weird.key", &SearchTagValuesOptions::default())
        .await
        .unwrap();
    assert_eq!(quoted, vec![TagValue::new("string", "x y")]);
    let quoted = q
        .search_tag_values(
            "span.\"grow weird.key\"",
            &SearchTagValuesOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(quoted.len(), 1);
    let status = q
        .search_tag_values("status", &SearchTagValuesOptions::default())
        .await
        .unwrap();
    assert!(status.iter().all(|v| v.kind == "keyword"));
    assert!(q
        .search_tag_values("span.does.not.exist", &SearchTagValuesOptions::default())
        .await
        .unwrap()
        .is_empty());

    let services = q.list_services().await.unwrap();
    assert!(services.windows(2).all(|w| w[0] < w[1]));
    assert!(services.iter().any(|s| s == "grow-sdk-seed-api"));
    let ops = q
        .list_operations("grow-sdk-verify-179503498127460-api")
        .await
        .unwrap();
    assert!(ops.contains(&"GET /orders".to_owned()));
    assert!(ops.windows(2).all(|w| w[0] < w[1]));

    for (tag, query) in [
        ("service.name", None),
        ("", None),
        ("span.x", Some("{ bad")),
    ] {
        let opts = SearchTagValuesOptions {
            query: query.map(str::to_owned),
            ..Default::default()
        };
        assert_eq!(
            q.search_tag_values(tag, &opts).await.unwrap_err().code(),
            ErrorCode::Validation,
            "{tag}"
        );
    }
    assert_eq!(
        q.list_operations(" ").await.unwrap_err().code(),
        ErrorCode::Validation
    );

    // v1-style plain strings, missing type and junk entries.
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/search/tag/resource.service.name/values"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"tagValues": ["b", {"value": "a"}, 3, "b"]})),
        )
        .mount(&server)
        .await;
    let q = client(&server).query();
    assert_eq!(q.list_services().await.unwrap(), ["a", "b"]);
    let raw = q
        .search_tag_values("resource.service.name", &Default::default())
        .await
        .unwrap();
    assert_eq!(raw[1], TagValue::new("string", "a"));
}

#[tokio::test]
async fn traceql_metrics() {
    let server = MockServer::start().await;
    mount_exact(&server, "traceQLMetrics.count-over-time").await;
    mount_exact(&server, "traceQLMetrics.quantile-exemplars").await;
    mount_exact(&server, "traceQLMetrics.rate-since").await;
    mount_exact(&server, "traceQLMetrics.by-service-name").await;
    let q = client(&server).query();
    let range =
        MetricsRange::range(at(1_795_034_947), at(1_795_035_043)).step(Duration::from_secs(5));
    let res = q
        .traceql_metrics(
            r#"{ resource.service.name = "grow-sdk-verify-179503498127460-api" } | count_over_time()"#,
            range,
        )
        .await
        .unwrap();
    let total: f64 = res
        .series
        .iter()
        .flat_map(|s| &s.samples)
        .map(|p| p.value)
        .sum();
    assert!(total > 0.0);
    assert_eq!(res.step, Some(Duration::from_secs(5)));
    assert_eq!(res.series[0].labels["__name__"], json!("count_over_time"));
    assert!(res.series[0].samples[0].timestamp_ms > 1_700_000_000_000);

    let res = q
        .traceql_metrics(
            r#"{ resource.service.name = "grow-sdk-verify-179503498127460-api" } | quantile_over_time(duration, .9)"#,
            range.exemplars(5),
        )
        .await
        .unwrap();
    assert_eq!(res.series[0].labels["p"], json!(0.9));
    assert_eq!(
        res.series[0].samples[0].value, 0.0,
        "omitted value reads as 0"
    );
    assert!(!res.series[0].exemplars.is_empty());
    assert!(res.series[0].exemplars[0].labels.contains_key("trace:id"));
    assert!(res.series[0].exemplars[0].timestamp_ms > 0);

    let res = q
        .traceql_metrics(
            r#"{ resource.service.name = "grow-sdk-verify-179503498127460-api" } | rate()"#,
            MetricsRange::since(Duration::from_secs(900)),
        )
        .await
        .unwrap();
    assert!(!res.series.is_empty());

    let res = q
        .traceql_metrics(
            r#"{ resource.service.name =~ "grow-sdk-verify-179503498127460-.*" } | count_over_time() by (resource.service.name)"#,
            range,
        )
        .await
        .unwrap();
    assert!(res.series.len() >= 2);

    for (query, range) in [
        ("", MetricsRange::default()),
        ("{} | rate()", MetricsRange::since(Duration::ZERO)),
        ("{} | rate()", MetricsRange::default().step(Duration::ZERO)),
        ("{} | rate()", MetricsRange::default().exemplars(0)),
        ("{} | rate()", MetricsRange::range(at(5), at(1))),
    ] {
        assert_eq!(
            q.traceql_metrics(query, range).await.unwrap_err().code(),
            ErrorCode::Validation
        );
    }

    let server = MockServer::start().await;
    mount(&server, "traceQLMetrics.no-pipeline-400").await;
    let err = client(&server)
        .query()
        .traceql_metrics("{ x }", MetricsRange::since(Duration::from_secs(300)))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(err.message().contains("not a metrics query"));

    // No step in the answer, string values.
    let server = MockServer::start().await;
    Mock::given(path("/api/metrics/query_range"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "series": [{"samples": [{"timestampMs": 1, "value": "2.5"}, {"value": true}]}]
        })))
        .mount(&server)
        .await;
    let res = client(&server)
        .query()
        .traceql_metrics("{} | rate()", MetricsRange::default())
        .await
        .unwrap();
    assert_eq!(res.step, None);
    assert_eq!(res.series[0].samples[0].value, 2.5);
    assert_eq!(res.series[0].samples[1].value, 0.0);
}

#[tokio::test]
async fn probes_dependencies_and_wait() {
    let server = MockServer::start().await;
    mount(&server, "echo.ok").await;
    mount(&server, "ready.ok").await;
    mount(&server, "buildInfo.ok").await;
    mount(&server, "getTrace.seed-v2").await;
    let c = client(&server);
    let q = c.query();
    assert_eq!(q.client().config().url(), c.config().url());
    assert_eq!(q.echo().await.unwrap(), "echo");
    assert!(q.ready().await.unwrap());
    assert_eq!(q.build_info().await.unwrap().version, "3.1.1");
    let err = q
        .get_dependencies(DependencyOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);
    let t = q
        .wait_for_trace(SEED, WaitForTraceOptions::default().min_spans(3))
        .await
        .unwrap();
    assert_eq!(t.spans.len(), 3);
    let err = q
        .wait_for_trace(
            SEED,
            WaitForTraceOptions::default()
                .min_spans(4)
                .timeout(Duration::from_millis(50))
                .interval(Duration::from_millis(10)),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);

    // Empty JSON body reads as {} (no spans → None).
    let server = MockServer::start().await;
    Mock::given(path(format!("/api/v2/traces/{SEED}")))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    Mock::given(path("/api/status/buildinfo"))
        .respond_with(ResponseTemplate::new(200).set_body_string("not json"))
        .mount(&server)
        .await;
    let q = client(&server).query();
    assert!(q.get_trace(SEED).await.unwrap().is_none());
    assert_eq!(
        q.build_info().await.unwrap_err().code(),
        ErrorCode::Validation
    );
}
