// Created by sdk-kit 0.1.0; owned by this repository.
//! Grafana Tempo Rust SDK: export a trace over OTLP, read it back by id, then search it with
//! TraceQL (search data appears about 30 s after ingest).
//!
//! ```sh
//! GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200 \
//! GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318 \
//! cargo run
//! ```
//!
//! On a multi-tenant Tempo also set `GROW_GRAFANA_TEMPO_TENANT_ID` (sent as `X-Scope-OrgID`
//! on queries and exports).

use std::sync::Arc;
use std::time::Duration;

use grow_grafana_tempo as sdk;
use sdk::core::{ErrorInfo, Hooks};
use sdk::tracing::{
    add_event, current_trace_id, traced, with_span, SpanOptions, TraceQueryClient, TracingConfig,
    WaitForTraceOptions,
};
use sdk::{init_tracing, ErrorCode, SearchRequest, TempoClient, TempoConfig, TraceQL};

/// Prints every retry.
struct RetryLogger;

impl Hooks for RetryLogger {
    fn on_error(&self, info: &ErrorInfo) {
        if info.will_retry {
            eprintln!(
                "retrying {} {} (attempt {})",
                info.method, info.url, info.attempt
            );
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error [{}]: {err}", err.code());
        std::process::exit(1);
    }
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_GRAFANA_TEMPO_* > TEMPO_URL / TEMPO_TENANT_ID > GROW_TEMPO_* > defaults.
    let config = TempoConfig::builder()
        .hooks(Arc::new(RetryLogger))
        .build_from_env()?;
    println!("config: {config:?}"); // credentials print as [REDACTED]
    let client = TempoClient::new(config)?;

    let health = client.health().await?; // never fails for an unreachable service
    println!("health: {} ({})", health.healthy, health.status);
    match client.ping().await {
        Ok(latency) => println!("ping: {} ms", latency.as_millis()),
        Err(e) if e.code() == ErrorCode::Authentication => {
            println!("ping: set GROW_GRAFANA_TEMPO_TENANT_ID for a multi-tenant Tempo ({e})")
        }
        Err(e) => return Err(e),
    }
    println!("server version: {}", client.server_version().await?);

    // 1. Export a trace (OTLP/HTTP; GROW_GRAFANA_TEMPO_OTLP_ENDPOINT / OTEL_EXPORTER_OTLP_*).
    let handle = init_tracing(TracingConfig::new("grow-example-rust").with_set_global(false))?;
    let tracer = handle.tracer("example", None);
    let trace_id = with_span(
        "GET /orders",
        SpanOptions::default().tracer(tracer),
        async {
            add_event("cache-miss", vec![]);
            traced("load order", async {
                tokio::time::sleep(Duration::from_millis(250)).await;
                Ok::<_, std::io::Error>(())
            })
            .await?;
            Ok::<_, std::io::Error>(current_trace_id().unwrap_or_default())
        },
    )
    .await
    .map_err(|e| sdk::Error::unavailable(format!("example span failed: {e}")))?;
    handle.force_flush(None).await?;
    println!("exported trace {trace_id}");

    // 2. Read it back by id (visible right after the export).
    let query = client.query();
    let trace = query
        .wait_for_trace(&trace_id, WaitForTraceOptions::default())
        .await?;
    for span in &trace.spans {
        println!(
            "  {} {} ({} µs)",
            span.service_name, span.name, span.duration_micros
        );
    }

    // 3. TraceQL search (safe builder; data is searchable about 30 s after ingest).
    let q = TraceQL::span([("resource.service.name", "grow-example-rust")])
        .and("duration", ">", "200ms")
        .select(["name"])
        .build()?;
    let found = query.search(&SearchRequest::new(&q).limit(5)).await?;
    println!("search {q}: {} trace(s) so far", found.traces.len());

    handle.shutdown().await?;
    client.close().await;
    Ok(())
}
