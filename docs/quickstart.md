# Quickstart

Connect to Grafana Tempo, check that it is healthy, make a tenant-aware call and close the client.
The client reads `GROW_GRAFANA_TEMPO_URL` and `GROW_GRAFANA_TEMPO_TENANT_ID` (and the other
variables listed in [Functions → Service configuration](functions.md#service-configuration)), or
takes the values in code. Tempo has no authentication of its own; `GROW_GRAFANA_TEMPO_TOKEN` is only
needed behind an authenticating proxy.

```sh
export GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200            # query API
export GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318  # OTLP/HTTP receiver (initTracing)
# export GROW_GRAFANA_TEMPO_TENANT_ID=team-a                   # multi-tenant Tempo only
```

=== "TypeScript"

    ```ts
    import { TempoClient } from "@grow-codes/grafana-tempo";

    const client = TempoClient.fromEnv();
    try {
      const health = await client.health();
      console.log("healthy:", health.healthy, health.status);
      console.log("ping:", await client.ping(), "ms");
      console.log("server version:", await client.serverVersion());
    } finally {
      await client.close();
    }
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::TempoClient;

    #[tokio::main]
    async fn main() -> grow_grafana_tempo::Result<()> {
        let client = TempoClient::from_env()?;
        let health = client.health().await?;
        println!("healthy: {} {}", health.healthy, health.status);
        println!("ping: {:?}", client.ping().await?);
        println!("server version: {}", client.server_version().await?);
        client.close().await;
        Ok(())
    }
    ```

=== "Go"

    ```go
    package main

    import (
        "context"
        "fmt"
        "log"

        tempo "github.com/grow-codes-packages/grafana-tempo/go"
    )

    func main() {
        ctx := context.Background()
        client, err := tempo.NewClientFromEnv()
        if err != nil {
            log.Fatal(err)
        }
        defer client.Close()

        health, err := client.Health(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("healthy:", health.Healthy, health.Status)

        latency, err := client.Ping(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("ping:", latency)

        version, err := client.ServerVersion(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("server version:", version)
    }
    ```

=== "Python"

    ```python
    from grow_grafana_tempo import TempoClient

    with TempoClient.from_env() as client:
        health = client.health()
        print("healthy:", health.healthy, health.status)
        print("ping:", client.ping(), "ms")
        print("server version:", client.server_version())
    ```

    Async code uses `AsyncTempoClient` with the same methods:

    ```python
    import asyncio
    from grow_grafana_tempo import AsyncTempoClient

    async def main() -> None:
        async with AsyncTempoClient.from_env() as client:
            print(await client.health())

    asyncio.run(main())
    ```

=== "Java"

    ```java
    import codes.grow.packages.tempo.TempoClient;
    import codes.grow.packages.tempo.core.HealthStatus;

    public final class Quickstart {
      public static void main(String[] args) {
        try (TempoClient client = TempoClient.fromEnv()) {
          HealthStatus health = client.health();
          System.out.println("healthy: " + health.healthy() + " " + health.status());
          System.out.println("ping: " + client.ping().toMillis() + " ms");
          System.out.println("server version: " + client.serverVersion());
        }
      }
    }
    ```

## Send a trace and read it back

Spans go in over OTLP with `initTracing`; the query client reads them back. Trace by id is visible
right after the export; search, tags and metrics need about 30 seconds.

=== "TypeScript"

    ```ts
    import { TempoClient, currentTraceId, initTracing, withSpan } from "@grow-codes/grafana-tempo";

    const tracing = initTracing({ serviceName: "checkout" });
    const client = TempoClient.fromEnv();
    const traceId = await withSpan("GET /orders", async () => currentTraceId());
    await tracing.forceFlush();
    const trace = await client.query.waitForTrace(traceId!, { timeoutMs: 15_000 });
    console.log(trace.spans.map((s) => `${s.name} ${s.durationMicros}µs`));
    await tracing.shutdown();
    await client.close();
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo as sdk;
    use sdk::tracing::{current_trace_id, with_span, SpanOptions, TraceQueryClient, TracingConfig, WaitForTraceOptions};

    let tracing = sdk::init_tracing(TracingConfig::new("checkout"))?;
    let client = sdk::TempoClient::from_env()?;
    let trace_id = with_span("GET /orders", SpanOptions::default(), async {
        Ok::<_, std::io::Error>(current_trace_id().unwrap_or_default())
    })
    .await
    .expect("span");
    tracing.force_flush(None).await?;
    let trace = client.query().wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
    println!("{} spans", trace.spans.len());
    tracing.shutdown().await?;
    ```

=== "Go"

    ```go
    handle, err := tempo.InitTracing(ctx, tempo.TracingConfig{ServiceName: "checkout"})
    if err != nil {
        return err
    }
    defer handle.Shutdown(context.Background())
    traceID, _ := tempo.WithSpan(ctx, "GET /orders", func(ctx context.Context) (string, error) {
        id, _ := tempo.CurrentTraceID(ctx)
        return id, nil
    })
    _ = handle.ForceFlush(ctx)
    trace, err := client.Query().WaitForTrace(ctx, traceID, tempo.WaitForTraceOptions{})
    if err != nil {
        return err
    }
    fmt.Println(len(trace.Spans), "spans")
    ```

=== "Python"

    ```python
    import grow_grafana_tempo as sdk

    handle = sdk.init_tracing(service_name="checkout")
    trace_id = sdk.with_span("GET /orders", lambda span: sdk.current_trace_id())
    handle.force_flush()
    with sdk.TempoClient.from_env() as client:
        trace = client.query.wait_for_trace(trace_id, timeout_s=15)
        print([s.name for s in trace.spans])
    handle.shutdown()
    ```

=== "Java"

    ```java
    TracingHandle tracing = TempoTracing.initTracing(TempoTracingConfig.builder("checkout").build());
    try (TempoClient client = TempoClient.fromEnv()) {
      String traceId = Spans.withSpan("GET /orders", () -> Spans.currentTraceId().orElseThrow());
      tracing.forceFlush();
      Trace trace = client.query().waitForTrace(traceId, WaitForTraceOptions.defaults());
      System.out.println(trace.spans().size() + " spans");
    } finally {
      tracing.shutdown();
    }
    ```

## Next steps

- [Configuration](configuration.md): timeouts, retries, TLS, proxy, environment variables.
- [Functions](functions.md): the Grafana Tempo API of this SDK (query client, TraceQL builder,
  tracing setup).
- [Error handling](errors.md): error codes and retries.
