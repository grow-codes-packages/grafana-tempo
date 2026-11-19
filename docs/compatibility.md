# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.85, edition 2021, tokio | stable, 1.85 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## Grafana Tempo versions

The integration suite runs against Grafana Tempo **3.1.1**, the version pinned in the Grow Codes
stack catalog (`grafana/tempo:3.1.1`). The official image is distroless, so `docker/tempo/Dockerfile`
builds the image exactly like the Grow Codes stack template: `alpine:3.24.4` plus the SHA-256-pinned
official `tempo_3.1.1_linux_<arch>.tar.gz`, the template's `tempo.yaml` (OTLP gRPC 4317 and HTTP
4318 receivers, local storage) and its start command. Two instances run side by side:
`grafana-tempo` (single-tenant, the template config) and `grafana-tempo-mt` (the same image with
`-multitenancy.enabled=true`). When the catalog bumps the Tempo version, bump the Dockerfile
arguments in the same release cycle.

Other Tempo 2.x/3.x versions that expose the same HTTP API are expected to work: the v1 trace route
is used when the v2 route is missing, and `serverVersion()` falls back to `/status/version`.

## Functions the service or a language cannot serve

Every public function is called against the real service in the Docker suite. Functions that the
service cannot serve are listed here with the reason; they are covered by unit tests.

| Function | Languages | Reason |
| - | - | - |
| `getDependencies` | all | Tempo has no dependency-graph endpoint (its service-graph processor writes Prometheus metrics instead); always raises `UNSUPPORTED` |

## Behaviour covered by unit tests only

These paths cannot be produced by a healthy Tempo container and are tested against mock servers in
every language:

| Path | Why not in Docker |
| - | - |
| Retries on 429 / 5xx and `Retry-After` | Tempo does not rate-limit the query API |
| `health()` / `ready()` during start-up (HTTP 503 `Ingester not ready`) | the suite starts after Tempo is ready |
| v1 fallback of `getTrace` (v2 route missing) | Tempo 3.1.1 has the v2 route |
| `UNSUPPORTED` for a feature route that answers 404 (e.g. metrics generator disabled) | the template enables every route used |
| Proxy authentication (`token`, 401 / 403 from a reverse proxy) | Tempo has no authentication of its own |
| Partial v2 responses (`status: partial`) and zero-valued metric samples without `value` | hard to reproduce on demand |

## Tempo quirks the SDK handles

| Quirk | SDK behaviour |
| - | - |
| v2 trace miss is HTTP 200 `{"trace":{},"metrics":{}}`; v1 miss is 404 | both return "no trace" (null / `None` / empty / `nil`) |
| Ids in trace-by-id JSON are base64; search `traceID` is hex without leading zeros | converted to lower-case hex, left-padded to 32 / 16 characters |
| Enum names (`SPAN_KIND_SERVER`, `STATUS_CODE_ERROR`), unset status is `{}`, timestamps are decimal strings in ns | normalized `Span.kind`, `Span.status`, microsecond times |
| Search, tags and metrics see new data about 30 s after ingest | documented; integration tests poll up to 150 s |
| Search span sets carry no span `name` unless the query has `\| select(name)` | `name` is optional in search spans |
| Metric samples omit `value` when it is 0 | filled in as 0 |
| Tag endpoints ignore an invalid `q` and return unfiltered values | TraceQL is checked client side (`VALIDATION`) |
| Query without a tenant on a multi-tenant Tempo → 401 `no org id` | `AUTHENTICATION` "tenant required (X-Scope-OrgID)" |
| OTLP export without a tenant on a multi-tenant Tempo → HTTP 503 / gRPC `Unavailable` (code 14) | cannot be surfaced from `initTracing` (exporters retry, then drop); documented on [Tracing setup](functions/tracing-setup.md) |
| `/status/buildinfo` is not a route (connection closed) | `serverVersion()` uses `/api/status/buildinfo`, fallback `/status/version` |
| The trace-by-id `start` / `end` range does not hide recent (live-store) data | documented |

## Skipped conformance scenarios

| Scenario | Reason |
| - | - |
| — | none; `core.health`, `tracing.services` (polled for the 30 s search delay) and `tracing.tree` pass in all five languages |

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Decisions that affect behaviour:

| Topic | Difference |
| - | - |
| gRPC `insecureSkipVerify` | Python (grpcio) and Rust (tonic) cannot skip certificate verification for OTLP/gRPC, so `insecureSkipVerify` with an `https` gRPC endpoint is a `ConfigError` there. OTLP/HTTP supports it in every language; TypeScript, Go and Java also support it for gRPC. |
| `limit`, `spss`, `exemplars` = 0 | a `VALIDATION` error in TypeScript, Rust, Python and Java. Go uses the zero value of `SearchRequest.Limit` / `SpansPerSpanSet` / `MetricsRange.Exemplars` for "not set" (server default); only negative values are rejected there. |
| `TraceQL.span(map)` order | Go maps have no order, so Go sorts the keys; the other languages keep insertion order. Use `And` for a fixed order. |
| Numbers in TraceQL | TypeScript cannot tell `1` from `1.0` and writes `1`; the other languages write floats with a decimal point. |
| Tracing config defaults | Rust and Python configs always carry `protocol`, `compression` and `sampler` values, so the environment applies while they hold the defaults. |
| `listServices` / `listOperations` time range | TypeScript and Python accept an optional `start` / `end`; Rust, Go and Java follow the shared `TraceQueryClient` signature without one. |
| Python gRPC export | needs the `grpc` extra (`pip install "grow-grafana-tempo[grpc]"`). |

Java cannot change the process environment, so `loadEnv` sets **system properties** instead and
returns the names it set.
