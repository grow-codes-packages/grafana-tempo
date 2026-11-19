# Tracing setup

`initTracing` sets up the official OpenTelemetry SDK of each language with the Tempo OTLP exporter
preset: resource (`service.name`, `service.version`, `deployment.environment` and extra
attributes), sampler, batch span processor, propagators (W3C trace context + baggage by default) and,
unless `setGlobal` is false, global registration. It returns a `TracingHandle` with `tracer(name,
version)`, `forceFlush()` and `shutdown()`. Span helpers, propagation, adapters and log correlation
are shared by every tracing package and documented on [Tracing](../tracing.md).

Tempo ingests **OTLP only**:

| Protocol | Default endpoint | Notes |
| - | - | - |
| `http/protobuf` (default) | `http://127.0.0.1:4318` | `/v1/traces` is appended to a bare endpoint; works through HTTP proxies |
| `grpc` | `http://127.0.0.1:4317` | tenant sent as `x-scope-orgid` metadata; Python needs the `grpc` extra |
| `native` | — | same as `http/protobuf` (Tempo has no other ingest API) |

## Multi-tenant Tempo

Set the tenant (`tenantId` / `tenant_id`, `withTenant`, or `GROW_GRAFANA_TEMPO_TENANT_ID` >
`TEMPO_TENANT_ID` > `GROW_TEMPO_TENANT_ID`). It is sent as the `X-Scope-OrgID` header (HTTP) or the
`x-scope-orgid` metadata entry (gRPC) on every export. A multi-tenant Tempo answers an export
without a tenant with HTTP 503 / gRPC `Unavailable` ("no org id"), not 401; the OpenTelemetry
exporters retry that and finally drop the spans, so the SDK cannot surface it from `initTracing`.

## Environment

Precedence per field: explicit value > environment > default.

| Setting | Environment variables (precedence order) | Default |
| - | - | - |
| `endpoint` | `GROW_GRAFANA_TEMPO_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (used verbatim), `OTEL_EXPORTER_OTLP_ENDPOINT` (+ `/v1/traces` for HTTP) | see above |
| `protocol` | `GROW_GRAFANA_TEMPO_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` |
| `compression` | `GROW_GRAFANA_TEMPO_OTLP_COMPRESSION`, `OTEL_EXPORTER_OTLP_TRACES_COMPRESSION`, `OTEL_EXPORTER_OTLP_COMPRESSION` (`gzip`, `none`) | `gzip` |
| `headers` | `OTEL_EXPORTER_OTLP_TRACES_HEADERS`, `OTEL_EXPORTER_OTLP_HEADERS` (`k=v,k2=v2`, percent-decoded); explicit headers win per key | none |
| tenant | `GROW_GRAFANA_TEMPO_TENANT_ID`, `TEMPO_TENANT_ID`, `GROW_TEMPO_TENANT_ID` | none |
| `serviceName` | `OTEL_SERVICE_NAME`, then `service.name` in `OTEL_RESOURCE_ATTRIBUTES` | required |
| `resourceAttributes` | `OTEL_RESOURCE_ATTRIBUTES`; explicit attributes win per key | none |
| `sampler` / `samplerRatio` | `OTEL_TRACES_SAMPLER` (`always_on`, `always_off`, `traceidratio`, `parentbased_*`) / `OTEL_TRACES_SAMPLER_ARG` | `parentbased_always_on` / 1.0 |

In Rust and Python, where the config always carries a protocol, compression and sampler value,
the environment applies while those fields hold their defaults. Invalid values raise `CONFIG`
naming the variable, never its value.

**TLS.** CA bundle, client certificate and key work for both protocols. `insecureSkipVerify` (tests
only) works for OTLP/HTTP in every language and for OTLP/gRPC in TypeScript, Go and Java; Python
(grpcio) and Rust (tonic) cannot skip certificate verification for gRPC, so that combination is a
`ConfigError`.

## Example

=== "TypeScript"

    ```ts
    import { initTracing, withSpan } from "@grow-codes/grafana-tempo";

    const tracing = initTracing({
      serviceName: "checkout",
      serviceVersion: "1.4.0",
      tenantId: "team-a",
      protocol: "grpc",                     // default http/protobuf
      endpoint: "http://tempo:4317",
    });
    await withSpan("charge card", async (span) => span.setAttribute("amount", 42));
    await tracing.shutdown();
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::{init_tracing, with_tenant, tracing::{Protocol, TracingConfig}};

    let config = TracingConfig::new("checkout")
        .with_endpoint("http://tempo:4317")
        .with_protocol(Protocol::Grpc);
    let tracing = init_tracing(with_tenant(config, "team-a"))?;
    // ... spans ...
    tracing.shutdown().await?;
    ```

=== "Go"

    ```go
    cfg := tempo.WithTenant(tempo.TracingConfig{
        ServiceName:    "checkout",
        ServiceVersion: "1.4.0",
        Protocol:       tempo.ProtocolGRPC,
        Endpoint:       "http://tempo:4317",
    }, "team-a")
    handle, err := tempo.InitTracing(ctx, cfg)
    if err != nil {
        return err
    }
    defer handle.Shutdown(context.Background())
    ```

=== "Python"

    ```python
    import grow_grafana_tempo as sdk

    handle = sdk.init_tracing(
        service_name="checkout",
        service_version="1.4.0",
        tenant_id="team-a",
        protocol="grpc",  # pip install "grow-grafana-tempo[grpc]"
        endpoint="http://tempo:4317",
    )
    sdk.with_span("charge card", lambda span: span.set_attribute("amount", 42))
    handle.shutdown()
    ```

=== "Java"

    ```java
    TracingHandle tracing =
        TempoTracing.initTracing(
            TempoTracingConfig.builder("checkout")
                .serviceVersion("1.4.0")
                .tenantId("team-a")
                .protocol(TracingConfig.Protocol.GRPC)
                .endpoint("http://tempo:4317")
                .build());
    Spans.withSpan("charge card", () -> null);
    tracing.shutdown();
    ```
