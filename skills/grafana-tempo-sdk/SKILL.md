---
name: grafana-tempo-sdk
description: Add and use the Grow Codes SDK for Grafana Tempo (`grafana-tempo`) in TypeScript, Rust, Go, Python or Java projects. Use when code must talk to Grafana Tempo - installing @grow-codes/grafana-tempo, the grow-grafana-tempo crate or PyPI package, the Go module github.com/grow-codes-packages/grafana-tempo/go or Maven codes.grow.packages:grafana-tempo, configuring it with GROW_GRAFANA_TEMPO_* / TEMPO_* variables, sending spans to Tempo over OTLP with initTracing, reading traces back (getTrace, TraceQL search, tags, TraceQL metrics), multi-tenancy with X-Scope-OrgID, building TraceQL with the TraceQL builder, handling TempoError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# Grafana Tempo SDK (Grow Codes)

One API for Grafana Tempo in five languages (categories: tracing). Pick the package for the
project's language, configure it from the environment, and handle errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/grafana-tempo` | `import { TempoClient } from "@grow-codes/grafana-tempo"` |
| Rust | `cargo add grow-grafana-tempo` (add `tokio` with `macros,rt-multi-thread`) | `use grow_grafana_tempo::TempoClient;` |
| Go | `go get github.com/grow-codes-packages/grafana-tempo/go` | `tempo "github.com/grow-codes-packages/grafana-tempo/go"` |
| Python | `pip install grow-grafana-tempo` (or `uv add grow-grafana-tempo`) | `from grow_grafana_tempo import TempoClient` |
| Java | Maven `codes.grow.packages:grafana-tempo` (Gradle `implementation("codes.grow.packages:grafana-tempo:<version>")`) | `import codes.grow.packages.tempo.TempoClient;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

Prefer environment configuration so nothing environment-specific is written into code:

```sh
export GROW_GRAFANA_TEMPO_URL=http://tempo:3200              # query API (default http://127.0.0.1:3200)
export GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://tempo:4318    # OTLP/HTTP for initTracing
export GROW_GRAFANA_TEMPO_TENANT_ID=team-a                   # only for a multi-tenant Tempo
# export GROW_GRAFANA_TEMPO_TOKEN=...   # only behind an authenticating proxy; from a secret store
```

Precedence: explicit config value > `GROW_GRAFANA_TEMPO_*` > `TEMPO_URL` / `TEMPO_TENANT_ID` >
short `GROW_TEMPO_*` aliases > default. Tracing also reads `OTEL_EXPORTER_OTLP_*`,
`OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES` and `OTEL_TRACES_SAMPLER*` (after
`GROW_GRAFANA_TEMPO_OTLP_ENDPOINT` / `_PROTOCOL` / `_COMPRESSION`). Tempo has no authentication of
its own. Other variables: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`, `1500`),
`…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Environment proxies
(`HTTPS_PROXY`) are ignored unless `proxy` is set.

## 3. Use

Create one client per process and close it on shutdown (TypeScript `await client.close()`, Rust
`client.close().await`, Go `defer client.Close()`, Python `with …`/`close()`, Java
try-with-resources).

- `health()` never throws for an unhealthy service; check `healthy`.
- `ping()` is a tenant-aware round trip: on a multi-tenant Tempo without a tenant it raises
  `AUTHENTICATION` ("tenant required (X-Scope-OrgID)").
- All I/O is retried with jitter on 429/502/503/504 and connection errors; only idempotent calls
  are retried on 500.
- Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
  `RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
  `httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
  `errors.Is(err, tempo.ErrNotFound)`.

### Tracing

Call `initTracing({ serviceName })` once at startup, wrap work in `withSpan(name, fn)` or the
`traced` helper, propagate context with `inject`/`extract`, and call `shutdown()` on exit. Use the
trace query client to read traces back (`getTrace`, `searchTraces`, `waitForTrace`).

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### Grafana Tempo specifics

- **Two endpoints.** Spans go in over OTLP (`initTracing`, HTTP/protobuf on 4318 by default, gRPC
  on 4317 with `protocol: "grpc"`); reads go to the HTTP API on 3200 (`TempoClient`).
- **Tenant.** Set `tenantId` (`tenant_id`) on both the client and `initTracing` for a multi-tenant
  Tempo. Exports without it are answered with 503 and silently dropped by the exporter.
- **Visibility.** Trace by id is readable right after `forceFlush()`; search, tags and metrics need
  about 30 s. In tests use `waitForTrace` for ids and poll for search.
- **Query client** (`client.query` / `client.query()` / `client.Query()`):
  `getTrace(id)` (null / `None` / empty / `nil` when missing — not an error), `search({traceql,
  start, end, limit, spss})`, `searchTraces({service, operation, tags, minDuration, maxDuration})`,
  `searchTags({scope})`, `searchTagValues(tag, {query})`, `listServices()`,
  `listOperations(service)`, `traceQLMetrics(query, {since, step})`, `waitForTrace(id)`.
  `getDependencies` always raises `UNSUPPORTED`.
- **TraceQL.** Never build TraceQL by string concatenation with user input; use the builder:

| Language | Example |
| - | - |
| TypeScript | `TraceQL.span({ "resource.service.name": svc }).and("duration", ">", "200ms").select("span.http.route")` |
| Rust | `TraceQL::span([("resource.service.name", svc)]).and("duration", ">", "200ms").build()?` |
| Go | `tempo.TraceQL.Span(map[string]any{"resource.service.name": svc}).And("duration", ">", "200ms").Build()` |
| Python | `TraceQL.span({"resource.service.name": svc}).and_("duration", ">", "200ms")` |
| Java | `TraceQL.span(Map.of("resource.service.name", svc)).and("duration", ">", "200ms")` |

  Structural operators: `.child(other)`, `.descendant(other)`, `.parent(other)`, `.ancestor(other)`,
  `.sibling(other)`.

Minimal round trip (TypeScript; the other languages are the same calls in their naming style, see
`docs/functions/` and `examples/<lang>/`):

```ts
const tracing = initTracing({ serviceName: "checkout", tenantId: "team-a" });
const client = TempoClient.fromEnv({ tenantId: "team-a" });
const traceId = await withSpan("GET /orders", async () => currentTraceId());
await tracing.forceFlush();
const trace = await client.query.waitForTrace(traceId!);
await tracing.shutdown();
await client.close();
```

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real Grafana Tempo in unit tests:
TypeScript `@grow-codes/grafana-tempo/testing`, Rust feature `testing`, Go package `testkit`, Python
`grow_grafana_tempo.testing`, Java `codes.grow.packages.tempo.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against Tempo 3.1.1
(single- and multi-tenant instances, built like the Grow Codes stack template). Service quirks and
unit-test-only paths are in `docs/compatibility.md`.
