//! Every public Tempo function against the real Tempo 3.1.1 (Docker): write → read back.

use std::future::Future;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_grafana_tempo as sdk;
use grow_grafana_tempo_integration as support;
use opentelemetry::trace::SpanKind as OtelKind;
use opentelemetry::KeyValue;
use sdk::tracing::{
    add_event, current_trace_id, init_tracing_with, with_span, DependencyOptions, SpanKind,
    StatusCode, TraceBatchConfig, TraceQueryClient, TraceSearchQuery, TracingConfig, TracingHandle,
    WaitForTraceOptions,
};
use sdk::{
    escape_traceql_string, init_tracing, tempo_exporter, tempo_tracing_config, with_tenant,
    ErrorCode, GetTraceOptions, MetricsRange, SearchRequest, SearchTagValuesOptions,
    SearchTagsOptions, TagScope, TagValue, TempoClient, TempoConfig, TraceQL,
};
use serde_json::json;
use support::{env, load_bootstrap_env, SEARCH_VISIBILITY};

fn run_id() -> String {
    let n = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    format!("{:08x}", (n ^ (n >> 32)) as u32)
}

fn quick() -> TraceBatchConfig {
    TraceBatchConfig {
        scheduled_delay: Duration::from_millis(200),
        ..TraceBatchConfig::default()
    }
}

/// Exports a two-span trace (`GET /orders` > `load order`, > 200 ms) and returns its id.
async fn emit(h: &TracingHandle, run: &str) -> String {
    let tracer = h.tracer("grow-sdk-it", None);
    let root_opts = sdk::tracing::SpanOptions::default()
        .tracer(tracer.clone())
        .kind(OtelKind::Server)
        .attribute(KeyValue::new("grow.run", run.to_owned()))
        .attribute(KeyValue::new("http.response.status_code", 200_i64))
        .attribute(KeyValue::new("grow weird.key", "x y"));
    let child_opts = sdk::tracing::SpanOptions::default()
        .tracer(tracer)
        .attribute(KeyValue::new("grow.run", run.to_owned()));
    let id = with_span("GET /orders", root_opts, async move {
        add_event("cache-miss", vec![KeyValue::new("n", 1_i64)]);
        with_span("load order", child_opts, async {
            tokio::time::sleep(Duration::from_millis(250)).await;
            Ok::<_, std::io::Error>(())
        })
        .await?;
        Ok::<_, std::io::Error>(current_trace_id().expect("active span"))
    })
    .await
    .unwrap();
    h.force_flush(None).await.unwrap();
    id
}

/// Retries `check` until it passes or the search visibility window ends.
async fn eventually<F, Fut>(what: &str, mut check: F)
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<(), String>>,
{
    let deadline = std::time::Instant::now() + SEARCH_VISIBILITY;
    loop {
        match check().await {
            Ok(()) => return,
            Err(e) if std::time::Instant::now() > deadline => panic!("{what}: {e}"),
            Err(_) => tokio::time::sleep(Duration::from_secs(2)).await,
        }
    }
}

fn plain(url: &str, tenant: Option<&str>) -> TempoClient {
    let mut b = TempoConfig::builder().url(url);
    if let Some(t) = tenant {
        b = b.tenant_id(t);
    }
    TempoClient::new(b.build().unwrap()).unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn tempo_round_trips() {
    load_bootstrap_env();
    let run = run_id();
    let version = env("GROW_GRAFANA_TEMPO_SERVER_VERSION");
    let client = TempoClient::from_env().unwrap();
    let q = client.query();

    // client surface + probes
    let hs = client.health().await.unwrap();
    assert!(hs.healthy && hs.status == "ok", "{hs:?}");
    assert!(client.ping().await.unwrap() > Duration::ZERO);
    assert_eq!(client.server_version().await.unwrap(), version);
    assert_eq!(
        client.config().url().as_str().trim_end_matches('/'),
        env("GROW_GRAFANA_TEMPO_URL").trim_end_matches('/')
    );
    assert_eq!(q.echo().await.unwrap(), "echo");
    assert_eq!(client.echo().await.unwrap(), "echo");
    assert!(q.ready().await.unwrap());
    assert!(client.ready().await.unwrap());
    let info = q.build_info().await.unwrap();
    assert_eq!(info.version, version);
    assert!(!info.go_version.is_empty());
    assert_eq!(client.build_info().await.unwrap(), info);

    // seed trace by id
    let seed_id = env("GROW_GRAFANA_TEMPO_SEED_TRACE_ID");
    let tr = q.get_trace(&seed_id).await.unwrap().expect("seed trace");
    assert_eq!(tr.trace_id, seed_id);
    assert_eq!(tr.spans.len(), 3);
    let (root, db) = (&tr.spans[0], &tr.spans[2]);
    assert_eq!(root.name, "GET /seed");
    assert_eq!(root.kind, SpanKind::Server);
    assert_eq!(root.status.code, StatusCode::Ok);
    assert_eq!(root.service_name, env("GROW_GRAFANA_TEMPO_SEED_SERVICE"));
    assert_eq!(root.attributes["http.response.status_code"], json!(200));
    assert_eq!(root.events.len(), 1);
    assert_eq!(root.duration_micros, 1_000_000);
    assert_eq!(
        db.service_name,
        env("GROW_GRAFANA_TEMPO_SEED_CHILD_SERVICE")
    );
    assert_eq!(db.status.code, StatusCode::Error);
    assert_eq!(db.status.message.as_deref(), Some("seed failure"));
    assert_eq!(tr.spans[1].links.len(), 1);
    let start: u64 = env("GROW_GRAFANA_TEMPO_SEED_START").parse().unwrap();
    let end: u64 = env("GROW_GRAFANA_TEMPO_SEED_END").parse().unwrap();
    let at = |s: u64| UNIX_EPOCH + Duration::from_secs(s);
    let ranged = q
        .get_trace_with(
            &seed_id,
            GetTraceOptions::range(at(start - 60), at(end + 60)),
        )
        .await
        .unwrap()
        .expect("ranged");
    assert_eq!(ranged.spans.len(), 3);
    assert!(q
        .get_trace("0123456789abcdef0123456789abcdef")
        .await
        .unwrap()
        .is_none());
    let err = q
        .get_trace_with(&seed_id, GetTraceOptions::range(at(end), at(start)))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);

    // OTLP/HTTP (default) export, read back by id at once.
    let service = format!("grow-sdk-rust-it-{run}");
    let cfg = tempo_tracing_config(
        TracingConfig::new(&service)
            .with_set_global(false)
            .with_batch(quick()),
        &sdk::core::EnvReader::new(),
    )
    .unwrap();
    assert_eq!(cfg.endpoint, Some(env("GROW_GRAFANA_TEMPO_OTLP_ENDPOINT")));
    assert_eq!(cfg.protocol, sdk::tracing::Protocol::HttpProtobuf);
    let h = init_tracing(
        TracingConfig::new(&service)
            .with_set_global(false)
            .with_batch(quick()),
    )
    .unwrap();
    let trace_id = emit(&h, &run).await;
    let tr = q
        .wait_for_trace(
            &trace_id,
            WaitForTraceOptions::default()
                .timeout(Duration::from_secs(30))
                .min_spans(2),
        )
        .await
        .unwrap();
    let (root, child) = (&tr.spans[0], &tr.spans[1]);
    assert_eq!(root.name, "GET /orders");
    assert_eq!(child.name, "load order");
    assert_eq!(child.parent_span_id.as_deref(), Some(root.span_id.as_str()));
    assert_eq!(root.service_name, service);
    assert_eq!(root.kind, SpanKind::Server);
    assert_eq!(root.attributes["grow.run"], json!(run));
    assert_eq!(root.attributes["grow weird.key"], json!("x y"));
    assert_eq!(root.events.len(), 1);
    assert_eq!(root.events[0].name, "cache-miss");
    assert!(child.duration_micros >= 250_000);

    // OTLP/gRPC export.
    let grpc_cfg = tempo_tracing_config(
        TracingConfig::new(format!("{service}-grpc"))
            .with_protocol(sdk::tracing::Protocol::Grpc)
            .with_endpoint(env("GROW_GRAFANA_TEMPO_OTLP_GRPC_ENDPOINT"))
            .with_set_global(false)
            .with_batch(quick()),
        &sdk::core::EnvReader::new(),
    )
    .unwrap();
    let g = init_tracing_with(grpc_cfg, tempo_exporter).unwrap();
    let gid = emit(&g, &run).await;
    let gt = q
        .wait_for_trace(
            &gid,
            WaitForTraceOptions::default()
                .timeout(Duration::from_secs(30))
                .min_spans(2),
        )
        .await
        .unwrap();
    assert_eq!(gt.spans[0].service_name, format!("{service}-grpc"));
    g.shutdown().await.unwrap();

    // Multi-tenant instance.
    let mt_url = env("GROW_GRAFANA_TEMPO_MT_URL");
    let tenant_a = env("GROW_GRAFANA_TEMPO_TENANT_ID");
    let tenant_b = env("GROW_GRAFANA_TEMPO_OTHER_TENANT_ID");
    let a = plain(&mt_url, Some(&tenant_a)).query();
    let b = plain(&mt_url, Some(&tenant_b)).query();
    let none = plain(&mt_url, None);
    let err = none.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert!(err.message().contains("tenant required"), "{err}");
    assert!(a
        .get_trace(&env("GROW_GRAFANA_TEMPO_MT_SEED_TRACE_ID"))
        .await
        .unwrap()
        .is_some());
    let mh = init_tracing(with_tenant(
        TracingConfig::new(format!("{service}-mt"))
            .with_endpoint(env("GROW_GRAFANA_TEMPO_MT_OTLP_ENDPOINT"))
            .with_set_global(false)
            .with_batch(quick()),
        &tenant_a,
    ))
    .unwrap();
    let mid = emit(&mh, &run).await;
    let mt = a
        .wait_for_trace(
            &mid,
            WaitForTraceOptions::default()
                .timeout(Duration::from_secs(30))
                .min_spans(2),
        )
        .await
        .unwrap();
    assert_eq!(mt.spans[0].service_name, format!("{service}-mt"));
    assert!(
        b.get_trace(&mid).await.unwrap().is_none(),
        "tenant b must not see it"
    );
    mh.shutdown().await.unwrap();

    // Search, tags and metrics (visible about 30 s after ingest).
    let window_start = SystemTime::now() - Duration::from_secs(600);
    let window_end = SystemTime::now() + Duration::from_secs(300);
    let query = TraceQL::span([("resource.service.name", service.as_str())])
        .and("duration", ">", "200ms")
        .select(["span.grow.run"])
        .build()
        .unwrap();
    eventually("search", || {
        let req = SearchRequest::new(query.clone())
            .range(window_start, window_end)
            .limit(5)
            .spss(5);
        let (q, trace_id, run, service) = (&q, &trace_id, &run, &service);
        async move {
            let res = q.search(&req).await.map_err(|e| e.to_string())?;
            res.traces
                .iter()
                .find(|t| {
                    &t.trace_id == trace_id
                        && t.span_sets.first().is_some_and(|s| {
                            s.matched == 2 && s.spans[0].attributes["grow.run"] == json!(run)
                        })
                        && t.root_service_name.as_deref() == Some(service.as_str())
                })
                .map(|_| ())
                .ok_or_else(|| format!("trace {trace_id} not in {res:?}"))
        }
    })
    .await;
    eventually("search_traces", || {
        let tq = TraceSearchQuery {
            min_duration: Some(Duration::from_millis(200)),
            max_duration: Some(Duration::from_secs(60)),
            start: Some(window_start),
            end: Some(window_end),
            ..TraceSearchQuery::service(service.clone())
                .operation("GET /orders")
                .tag("grow.run", run.clone())
        };
        let (q, trace_id) = (&q, &trace_id);
        async move {
            let got = q.search_traces(&tq).await.map_err(|e| e.to_string())?;
            if got.len() == 1
                && &got[0].trace_id == trace_id
                && got[0].span_count == 2
                && got[0].root_name.as_deref() == Some("GET /orders")
            {
                Ok(())
            } else {
                Err(format!("summaries {got:?}"))
            }
        }
    })
    .await;
    eventually("search_tags", || {
        let opts = SearchTagsOptions::scope(TagScope::Span).query(query.clone());
        let q = &q;
        async move {
            let tags = q.search_tags(&opts).await.map_err(|e| e.to_string())?;
            if tags
                .get("span")
                .is_some_and(|t| t.iter().any(|t| t == "grow.run"))
            {
                Ok(())
            } else {
                Err(format!("tags {tags:?}"))
            }
        }
    })
    .await;
    eventually("search_tag_values", || {
        let opts = SearchTagValuesOptions::query(format!(
            "{{ resource.service.name = {} }}",
            escape_traceql_string(&service)
        ));
        let q = &q;
        async move {
            let vals = q
                .search_tag_values("span.\"grow weird.key\"", &opts)
                .await
                .map_err(|e| e.to_string())?;
            if vals == [TagValue::new("string", "x y")] {
                Ok(())
            } else {
                Err(format!("values {vals:?}"))
            }
        }
    })
    .await;
    eventually("list_services", || {
        let (q, service) = (&q, &service);
        async move {
            let svcs = q.list_services().await.map_err(|e| e.to_string())?;
            if svcs.contains(service) && svcs.windows(2).all(|w| w[0] <= w[1]) {
                Ok(())
            } else {
                Err(format!("services {svcs:?}"))
            }
        }
    })
    .await;
    eventually("list_operations", || {
        let (q, service) = (&q, &service);
        async move {
            let ops = q
                .list_operations(service)
                .await
                .map_err(|e| e.to_string())?;
            // Tempo's filtered tag values occasionally omit child span names; the root
            // operation must be there and nothing foreign.
            if ops.iter().any(|o| o == "GET /orders")
                && ops.iter().all(|o| o == "GET /orders" || o == "load order")
            {
                Ok(())
            } else {
                Err(format!("operations {ops:?}"))
            }
        }
    })
    .await;
    eventually("traceql_metrics", || {
        let metrics_query = format!(
            "{{ resource.service.name = {} }} | count_over_time()",
            escape_traceql_string(&service)
        );
        let range = MetricsRange::range(window_start, window_end).step(Duration::from_secs(30));
        let q = &q;
        async move {
            let res = q
                .traceql_metrics(&metrics_query, range)
                .await
                .map_err(|e| e.to_string())?;
            let total: f64 = res
                .series
                .iter()
                .flat_map(|s| &s.samples)
                .map(|p| p.value)
                .sum();
            if total == 2.0 && res.step == Some(Duration::from_secs(30)) {
                Ok(())
            } else {
                Err(format!("count {total} in {res:?}"))
            }
        }
    })
    .await;

    // errors and unsupported
    let err = q
        .search(&SearchRequest::new("{ resource.service.name = }"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    let err = q
        .search_tag_values("service.name", &SearchTagValuesOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    let err = q
        .traceql_metrics("{}", MetricsRange::since(Duration::from_secs(900)))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    let err = q
        .get_dependencies(DependencyOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);

    h.shutdown().await.unwrap();
    client.close().await;
    client.close().await;
    assert_eq!(
        client.ping().await.unwrap_err().code(),
        ErrorCode::Cancelled
    );
}

#[test]
fn blocking_query_round_trip() {
    load_bootstrap_env();
    let c = sdk::blocking::TempoClient::from_env().unwrap();
    let seed = env("GROW_GRAFANA_TEMPO_SEED_TRACE_ID");
    let q = c.query();
    assert_eq!(q.get_trace(&seed).unwrap().unwrap().spans.len(), 3);
    assert_eq!(c.echo().unwrap(), "echo");
    assert!(c.ready().unwrap());
    assert_eq!(
        c.build_info().unwrap().version,
        env("GROW_GRAFANA_TEMPO_SERVER_VERSION")
    );
    assert_eq!(
        c.server_version().unwrap(),
        env("GROW_GRAFANA_TEMPO_SERVER_VERSION")
    );
    c.close();
}
