# Grow Codes SDK for Grafana Tempo

[![CI](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/grafana-tempo?sort=semver)](https://github.com/grow-codes-packages/grafana-tempo/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/grafana-tempo/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/grafana-tempo)](https://www.npmjs.com/package/@grow-codes/grafana-tempo) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-grafana-tempo)](https://crates.io/crates/grow-grafana-tempo) [![docs.rs](https://img.shields.io/docsrs/grow-grafana-tempo)](https://docs.rs/grow-grafana-tempo) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-rust.json)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/grafana-tempo/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/grafana-tempo/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-go.json)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-grafana-tempo)](https://pypi.org/project/grow-grafana-tempo/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-python.json)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/grafana-tempo)](https://central.sonatype.com/artifact/codes.grow.packages/grafana-tempo) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-java.json)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml) |

Open-source client libraries for [Grafana Tempo](#trademarks-and-disclaimer) in **TypeScript, Rust, Go,
Python and Java** — one API, five idiomatic packages, tested against a real Grafana Tempo in Docker.

- **Typed client** with timeouts, retries with jitter, `Retry-After`, cancellation and one error
  model (`TempoError` with canonical codes) in every language.
- **Secrets stay secret**: credentials are redacted in `toString`, logs, hooks and error messages.
- **Tracing**: OpenTelemetry setup in one call, span helpers, context propagation, framework
  middleware and a trace query client.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Tested in CI against **`grafana/tempo:3.1.1`** (the version pinned in the Grow Codes stack catalog). Other
versions with the same API usually work; see [Compatibility](#compatibility) and
[docs/compatibility.md](docs/compatibility.md).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/grafana-tempo` | `npm install @grow-codes/grafana-tempo` |
| Rust (MSRV 1.85) | `grow-grafana-tempo` | `cargo add grow-grafana-tempo` |
| Go (1.26+) | `github.com/grow-codes-packages/grafana-tempo/go` | `go get github.com/grow-codes-packages/grafana-tempo/go` |
| Python (≥ 3.10) | `grow-grafana-tempo` | `pip install grow-grafana-tempo` |
| Java (17+) | `codes.grow.packages:grafana-tempo` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>grafana-tempo</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:grafana-tempo:0.1.0")
```

## Quickstart

Every package reads its configuration from the environment (`GROW_GRAFANA_TEMPO_URL`,
`GROW_GRAFANA_TEMPO_TENANT_ID`, `GROW_GRAFANA_TEMPO_OTLP_ENDPOINT`, …, see
[Configuration](#configuration)) or from an explicit config object. Tempo has two sides: spans go
**in** over OTLP (`initTracing`, ports 4317/4318) and come **out** through the query API
(`TempoClient`, port 3200). The examples send one span and read the trace back by id.

```sh
export GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200            # query API
export GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318  # OTLP/HTTP receiver
# export GROW_GRAFANA_TEMPO_TENANT_ID=team-a                   # multi-tenant Tempo only
```

### TypeScript

```ts
import { TempoClient, currentTraceId, initTracing, withSpan } from "@grow-codes/grafana-tempo";

const tracing = initTracing({ serviceName: "checkout" });
const client = TempoClient.fromEnv();
try {
  console.log(await client.health()); // never throws for an unreachable service
  const traceId = await withSpan("GET /orders", async () => currentTraceId());
  await tracing.forceFlush();
  const trace = await client.query.waitForTrace(traceId!, { timeoutMs: 15_000 });
  console.log(trace.spans.map((s) => s.name));
} finally {
  await tracing.shutdown();
  await client.close();
}
```

### Rust

```rust
use grow_grafana_tempo as sdk;
use sdk::tracing::{current_trace_id, with_span, SpanOptions, TraceQueryClient, TracingConfig, WaitForTraceOptions};

#[tokio::main]
async fn main() -> sdk::Result<()> {
    let tracing = sdk::init_tracing(TracingConfig::new("checkout"))?;
    let client = sdk::TempoClient::from_env()?;
    println!("healthy: {}", client.health().await?.healthy);
    let trace_id = with_span("GET /orders", SpanOptions::default(), async {
        Ok::<_, std::io::Error>(current_trace_id().unwrap_or_default())
    })
    .await
    .expect("span");
    tracing.force_flush(None).await?;
    let trace = client.query().wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
    println!("{} spans", trace.spans.len());
    tracing.shutdown().await?;
    client.close().await;
    Ok(())
}
```

### Go

```go
ctx := context.Background()
handle, err := tempo.InitTracing(ctx, tempo.TracingConfig{ServiceName: "checkout"})
if err != nil {
	log.Fatal(err)
}
defer handle.Shutdown(context.Background())
client, err := tempo.NewClientFromEnv()
if err != nil {
	log.Fatal(err)
}
defer client.Close()

traceID, _ := tempo.WithSpan(ctx, "GET /orders", func(ctx context.Context) (string, error) {
	id, _ := tempo.CurrentTraceID(ctx)
	return id, nil
})
_ = handle.ForceFlush(ctx)
trace, err := client.Query().WaitForTrace(ctx, traceID, tempo.WaitForTraceOptions{})
if err != nil {
	log.Fatal(err)
}
fmt.Println(len(trace.Spans), "spans")
```

(`import tempo "github.com/grow-codes-packages/grafana-tempo/go"`)

### Python

```python
import grow_grafana_tempo as sdk

handle = sdk.init_tracing(service_name="checkout")
trace_id = sdk.with_span("GET /orders", lambda span: sdk.current_trace_id())
handle.force_flush()

with sdk.TempoClient.from_env() as client:
    print(client.health())
    trace = client.query.wait_for_trace(trace_id, timeout_s=15)
    print([s.name for s in trace.spans])
handle.shutdown()
```

Async code uses `AsyncTempoClient` with the same methods (`async with`, `await client.query.get_trace(…)`).

### Java

```java
import codes.grow.packages.tempo.*;
import codes.grow.packages.tempo.tracing.*;
import codes.grow.packages.tempo.tracing.query.WaitForTraceOptions;

TracingHandle tracing = TempoTracing.initTracing(TempoTracingConfig.builder("checkout").build());
try (TempoClient client = TempoClient.fromEnv()) {
  System.out.println(client.health());
  String traceId = Spans.withSpan("GET /orders", () -> Spans.currentTraceId().orElseThrow());
  tracing.forceFlush();
  var trace = client.query().waitForTrace(traceId, WaitForTraceOptions.defaults());
  System.out.println(trace.spans().size() + " spans");
} finally {
  tracing.shutdown();
}
```

More complete programs (TraceQL search, tag values, metrics, hooks) live in [examples/](examples/) —
one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_GRAFANA_TEMPO_*` > Tempo's own variables (`TEMPO_URL`, `TEMPO_TENANT_ID`) > short
`GROW_TEMPO_*` aliases > default.** A present but invalid value raises a `ConfigError` that names
the variable (never its value).

**Query client** (`TempoConfig`, `TempoClient`):

| Setting | Environment variables (precedence order) | Default |
| - | - | - |
| `url` | `GROW_GRAFANA_TEMPO_URL`, `TEMPO_URL`, `GROW_TEMPO_URL` | `http://127.0.0.1:3200` |
| `tenantId` (`X-Scope-OrgID`) | `GROW_GRAFANA_TEMPO_TENANT_ID`, `TEMPO_TENANT_ID`, `GROW_TEMPO_TENANT_ID` | none (single-tenant) |
| `token` (bearer for an auth proxy) | `GROW_GRAFANA_TEMPO_TOKEN`, `GROW_TEMPO_TOKEN` | none |
| `connectTimeout` | `GROW_GRAFANA_TEMPO_CONNECT_TIMEOUT`, `GROW_TEMPO_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_GRAFANA_TEMPO_REQUEST_TIMEOUT`, `GROW_TEMPO_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_GRAFANA_TEMPO_TOTAL_TIMEOUT`, `GROW_TEMPO_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_GRAFANA_TEMPO_MAX_RETRIES`, `GROW_TEMPO_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_GRAFANA_TEMPO_CA_CERT`, `GROW_TEMPO_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_GRAFANA_TEMPO_CLIENT_CERT` / `_CLIENT_KEY`, `GROW_TEMPO_CLIENT_CERT` / `_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_GRAFANA_TEMPO_INSECURE_SKIP_VERIFY`, `GROW_TEMPO_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_GRAFANA_TEMPO_PROXY`, `GROW_TEMPO_PROXY` | none (environment proxies are ignored) |
| `headers`, `userAgentSuffix`, `hooks`, `logger` | — | none |

Tempo has no authentication of its own. `token` (sent as `Authorization: Bearer …`) and `headers`
exist for an authenticating reverse proxy in front of it; both are redacted everywhere.

**Tracing** (`initTracing`, OTLP export):

| Setting | Environment variables (precedence order) | Default |
| - | - | - |
| `endpoint` | `GROW_GRAFANA_TEMPO_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` (+ `/v1/traces` for HTTP) | `http://127.0.0.1:4318` (`:4317` for gRPC) |
| `protocol` | `GROW_GRAFANA_TEMPO_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` (`grpc` selectable; `native` = OTLP/HTTP) |
| `compression` | `GROW_GRAFANA_TEMPO_OTLP_COMPRESSION`, `OTEL_EXPORTER_OTLP_TRACES_COMPRESSION`, `OTEL_EXPORTER_OTLP_COMPRESSION` | `gzip` |
| `headers` | `OTEL_EXPORTER_OTLP_TRACES_HEADERS`, `OTEL_EXPORTER_OTLP_HEADERS` (explicit headers win per key) | none |
| tenant (`X-Scope-OrgID` header / `x-scope-orgid` metadata) | `GROW_GRAFANA_TEMPO_TENANT_ID`, `TEMPO_TENANT_ID`, `GROW_TEMPO_TENANT_ID` | none |
| `serviceName` | `OTEL_SERVICE_NAME`, `service.name` in `OTEL_RESOURCE_ATTRIBUTES` | required |
| `resourceAttributes` | `OTEL_RESOURCE_ATTRIBUTES` (explicit wins per key) | none |
| `sampler` / `samplerRatio` | `OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG` | `parentbased_always_on` / 1.0 |

Durations accept milliseconds (`1500`) or units (`500ms`, `10s`, `1m30s`); booleans accept
`true/false/1/0/yes/no/on/off`. Field names follow each language's convention
(`request_timeout` in Python and Rust, `RequestTimeout` in Go). The full reference is in the
[documentation](https://grow-codes-packages.github.io/grafana-tempo/functions/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`getTrace` in
TypeScript and Java, `get_trace` in Python and Rust, `GetTrace` in Go). Every function is called
against a real Tempo in the Docker suite unless [docs/compatibility.md](docs/compatibility.md)
says otherwise. Full signatures and one example per language for every function:
[Functions](https://grow-codes-packages.github.io/grafana-tempo/functions/).

### Client (`TempoClient`)

| Function | Tempo endpoint | Description |
| - | - | - |
| `health()` | `GET /ready` | `{healthy, status}`; never throws for an unhealthy or unreachable server |
| `ping()` | `GET /api/v2/search/tags?scope=intrinsic` | tenant-aware round trip, returns the latency; missing tenant on a multi-tenant Tempo → `AuthenticationError` |
| `serverVersion()` | `GET /api/status/buildinfo` (fallback `/status/version`) | `"3.1.1"` |
| `buildInfo()`, `echo()`, `ready()` | `/api/status/buildinfo`, `/api/echo`, `/ready` | raw probes (also on the query client) |
| `query` | — | the `TempoQueryClient` sharing this client's transport |
| `close()` | — | idempotent; flushes and releases connections |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.serverVersion()` | `client.server_version().await?` | `client.ServerVersion(ctx)` | `client.server_version()` | `client.serverVersion()` |

### Query (`TempoQueryClient`, implements `TraceQueryClient`)

| Function | Tempo endpoint | Description |
| - | - | - |
| `getTrace(traceId, {start, end})` | `GET /api/v2/traces/{id}` (v1 fallback on a route 404) | normalized `Trace` (hex ids, sorted spans) or null/None/empty when missing |
| `search({traceql, start, end, limit, spss})` | `GET /api/search` | TraceQL search with span sets and service stats; no `q` when no query is given |
| `searchTraces({service, operation, tags, minDuration, maxDuration, start, end, limit})` | `GET /api/search` | SPEC search, rendered as one TraceQL span set (`trace:duration` bounds) → `TraceSummary[]` |
| `searchTags({scope, query, start, end})` | `GET /api/v2/search/tags` | attribute names per scope |
| `searchTagValues(tag, {query, start, end})` | `GET /api/v2/search/tag/{tag}/values` | typed values (`string`, `int`, `keyword`, …) |
| `listServices()`, `listOperations(service)` | tag values of `resource.service.name` / `name` | names |
| `traceQLMetrics(query, {start, end, since, step, exemplars})` | `GET /api/metrics/query_range` | TraceQL metrics series (zero samples filled in) |
| `waitForTrace(traceId, {timeout, interval, minSpans})` | polls `getTrace` | test helper |
| `getDependencies(range)` | — | always `UnsupportedError` (Tempo has no dependency endpoint) |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.query.getTrace(id)` | `client.query().get_trace(&id).await?` | `client.Query().GetTrace(ctx, id)` | `client.query.get_trace(id)` | `client.query().getTrace(id)` |

### TraceQL builder (`TraceQL`)

| Function | Description |
| - | - |
| `span(filters)`, `and(field, op, value)`, `or(…)` | span-set filter; values quoted and escaped (`\`, `"`, newline, tab, CR); odd attribute names quoted (`span."my key"`) |
| `child`, `descendant`, `parent`, `ancestor`, `sibling`, `structural(op, other)` | structural operators `>`, `>>`, `<`, `<<`, `~` (also `&&`, `\|\|`) |
| `select(fields…)` | `\| select(…)` |
| `field(name)`, `quote(text)` | render one field / string literal |

| Language | Example |
| - | - |
| TypeScript | `TraceQL.span({ "resource.service.name": "api" }).and("duration", ">", "200ms").select("span.http.status_code")` |
| Rust | `TraceQL::span([("resource.service.name", "api")]).and("duration", ">", "200ms").select(["span.http.status_code"]).build()?` |
| Go | `tempo.TraceQL.Span(map[string]any{"resource.service.name": "api"}).And("duration", ">", "200ms").Select("span.http.status_code").Build()` |
| Python | `TraceQL.span({"resource.service.name": "api"}).and_("duration", ">", "200ms").select("span.http.status_code")` |
| Java | `TraceQL.span(Map.of("resource.service.name", "api")).and("duration", ">", "200ms").select("span.http.status_code")` |

All render `{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)`.

### Tracing setup and helpers

| Function | Description |
| - | - |
| `initTracing(config)` | OpenTelemetry SDK with the Tempo OTLP preset (http/protobuf :4318 or gRPC :4317), tenant header, sampler, batch processor, propagators; returns `TracingHandle` (`tracer`, `forceFlush`, `shutdown`) |
| `withTenant(config, tenant)` | adds `X-Scope-OrgID` to an exporter config (Go, Rust, Java; TS/Python take `tenantId`) |
| `withSpan`, `traced`, `currentSpan`, `currentTraceId`, `currentSpanId`, `addEvent`, `recordException`, `setError` | span helpers |
| `inject`, `extract`, `injectHeaders`, `extractHeaders` | W3C trace context + baggage propagation (B3 optional) |
| `traceFields()` | `{trace_id, span_id}` for log correlation |
| adapters | TS Express/Fastify/NestJS + `fetch`; Rust tower/axum layer, reqwest middleware, `tracing-opentelemetry`; Go `net/http` handler + `RoundTripper`, gRPC interceptors; Python ASGI/WSGI, httpx/requests hooks; Java Servlet filter, `HttpClient` and OkHttp interceptors |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `initTracing({ serviceName: "api", tenantId: "team-a" })` | `init_tracing(with_tenant(TracingConfig::new("api"), "team-a"))?` | `tempo.InitTracing(ctx, tempo.WithTenant(tempo.TracingConfig{ServiceName: "api"}, "team-a"))` | `init_tracing(service_name="api", tenant_id="team-a")` | `TempoTracing.initTracing(TempoTracingConfig.builder("api").tenantId("team-a").build())` |

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs every public function against a real Grafana Tempo (`grafana/tempo:3.1.1`) in Docker.
Each language package is packed and installed into a separate consumer project first, so the tests
use the package exactly as you would.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). Unit tests need no Docker:
`make test` or `make coverage`.

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.85 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| OTLP/HTTP protobuf export | yes | yes | yes | yes | yes |
| OTLP/gRPC export | yes | yes | yes | yes (`grpc` extra) | yes |
| gRPC `insecureSkipVerify` | yes | `ConfigError` | yes | `ConfigError` | yes |
| Grafana Tempo | `3.1.1` | `3.1.1` | `3.1.1` | `3.1.1` | `3.1.1` |

The Docker suite runs Tempo **3.1.1** built like the Grow Codes stack template (Alpine plus the
official release binary; single-tenant and multi-tenant instances). Tempo-specific behaviour the SDK
handles for you:

- A trace miss on the v2 API is HTTP 200 with an empty body (`{"trace":{}}`); the SDK returns
  null/None/empty, like a v1 404.
- Search, tags and metrics see new spans about **30 s** after ingest; trace by id is immediate.
- Export to a multi-tenant Tempo without a tenant fails with HTTP 503 / gRPC `Unavailable`
  ("no org id"), which OpenTelemetry exporters retry and then drop. Always set the tenant there.
- `getDependencies` is `UNSUPPORTED`: Tempo has no dependency-graph endpoint.

Language and service differences, and the functions that are covered by unit tests only, are listed
in [docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by Grafana Labs**. Grafana Tempo is a trademark of Grafana Labs; it is used here only to name the
service this SDK connects to. The Grafana Tempo server is licensed separately by its owner
(AGPL-3.0-only, with Apache-2.0 exceptions (`pkg/tempopb`, `operations/`)).

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost that results from using it.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
