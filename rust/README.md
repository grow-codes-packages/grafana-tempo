# grow-grafana-tempo (Rust)

Rust SDK for Grafana Tempo by [Grow Codes](https://grow.codes): OpenTelemetry tracing that
exports to Tempo over OTLP, a client for the Tempo HTTP query API (trace by id, TraceQL search,
tag names and values, TraceQL metrics) and a safe TraceQL builder. Async on tokio, with an
optional blocking client.

```toml
[dependencies]
grow-grafana-tempo = "0.1.0"
# blocking client:   features = ["blocking"]
# test fakes/runner: features = ["testing"] (dev-dependency)
# tower/axum layer:  features = ["tower"]; reqwest middleware: features = ["reqwest-middleware"]
```

```rust,no_run
use grow_grafana_tempo::tracing::{traced, TraceQueryClient, TracingConfig};
use grow_grafana_tempo::{init_tracing, SearchRequest, TempoClient, TraceQL};

#[tokio::main]
async fn main() -> grow_grafana_tempo::Result<()> {
    // OTLP/HTTP to http://127.0.0.1:4318 unless GROW_GRAFANA_TEMPO_OTLP_ENDPOINT /
    // OTEL_EXPORTER_OTLP_* say otherwise; GROW_GRAFANA_TEMPO_TENANT_ID adds X-Scope-OrgID.
    let handle = init_tracing(TracingConfig::new("checkout"))?;
    traced("charge-card", async { Ok::<_, std::io::Error>(()) }).await.ok();
    handle.force_flush(None).await?;

    // explicit > GROW_GRAFANA_TEMPO_* > TEMPO_URL / TEMPO_TENANT_ID > GROW_TEMPO_* > defaults
    let client = TempoClient::from_env()?;
    let query = client.query();
    let trace = query.get_trace("5b8efff798038103d269b633813fc60c").await?;
    let q = TraceQL::span([("resource.service.name", "checkout")])
        .and("duration", ">", "200ms")
        .select(["span.http.status_code"])
        .build()?;
    let found = query.search(&SearchRequest::new(q).limit(20)).await?;
    println!("{trace:?} {}", found.traces.len());
    client.close().await;
    handle.shutdown().await
}
```

- Query client: `get_trace` / `get_trace_with`, `search`, `search_traces`, `search_tags`,
  `search_tag_values`, `list_services`, `list_operations`, `traceql_metrics`, `echo`, `ready`,
  `build_info`, `wait_for_trace`; `get_dependencies` is always `UNSUPPORTED` (Tempo has no
  dependency API).
- Search, tags and metrics see new data about 30 s after ingest; trace by id sees it at once.
- Errors: one `Error` enum with `code()`, `http_status()`, `request_id()`, `is_retryable()`,
  `retry_after()`; messages never contain credentials. A multi-tenant Tempo without a tenant
  gives `AUTHENTICATION` ("tenant required (X-Scope-OrgID)").
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation (drop the future or pass a `CancellationToken`), request hooks, `tracing` debug
  events.
- MSRV 1.88. Licence Apache-2.0. Documentation: https://grow-codes-packages.github.io/grafana-tempo/

Development: `make deps build lint test coverage license pack docs` (see `Makefile`).
