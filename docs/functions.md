# Functions

The Grafana Tempo API of this SDK. Every function exists in all five languages with the same
behaviour, named per language convention (`getTrace` in TypeScript and Java, `get_trace` in Python
and Rust, `GetTrace` in Go). Each module page shows every function with an example per language.

| Module | Page | What |
| - | - | - |
| Client | [Client and probes](functions/client.md) | `TempoClient`: `health`, `ping`, `serverVersion`, `buildInfo`, `echo`, `ready`, `close` |
| Query | [Query client](functions/query.md) | `TempoQueryClient`: `getTrace`, `search`, `searchTraces`, `searchTags`, `searchTagValues`, `listServices`, `listOperations`, `traceQLMetrics`, `waitForTrace`, `getDependencies` |
| TraceQL | [TraceQL builder](functions/traceql.md) | `TraceQL`: spanset filters, escaping, structural operators, `select` |
| Tracing | [Tracing setup](functions/tracing-setup.md) | `initTracing` with the Tempo OTLP preset and tenant header; span helpers in [Tracing](tracing.md) |

## Overview

| Function | Tempo endpoint | Integration test |
| - | - | - |
| `health()` | `GET /ready` | every language (`core.health`) |
| `ping()` | `GET /api/v2/search/tags?scope=intrinsic` | every language, single- and multi-tenant |
| `serverVersion()` | `GET /api/status/buildinfo`, fallback `GET /status/version` | every language |
| `buildInfo()`, `echo()`, `ready()` | `/api/status/buildinfo`, `/api/echo`, `/ready` | every language |
| `getTrace(id, {start, end})` | `GET /api/v2/traces/{id}`, v1 `GET /api/traces/{id}` on a route 404 | every language |
| `search({traceql, start, end, limit, spss})` | `GET /api/search` | every language |
| `searchTraces(query)` | `GET /api/search` (TraceQL built from the query) | every language (`tracing.services`) |
| `searchTags({scope, query, start, end})` | `GET /api/v2/search/tags` | every language |
| `searchTagValues(tag, {query, start, end})` | `GET /api/v2/search/tag/{tag}/values` | every language |
| `listServices()`, `listOperations(service)` | tag values of `resource.service.name` / `name` | every language |
| `traceQLMetrics(query, range)` | `GET /api/metrics/query_range` | every language |
| `waitForTrace(id, options)` | polls `getTrace` | every language (`tracing.tree`) |
| `getDependencies(range)` | none | unit tests only: always `UNSUPPORTED` |
| `initTracing(config)` | OTLP `POST /v1/traces` (4318) or gRPC `TraceService/Export` (4317) | every language, HTTP and gRPC, single- and multi-tenant |

## Service configuration

Precedence: explicit value > `GROW_GRAFANA_TEMPO_*` > Tempo's own variables (`TEMPO_URL`,
`TEMPO_TENANT_ID`) > short `GROW_TEMPO_*` aliases > default. An invalid value raises a
`ConfigError` that names the variable, never its value.

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url` | `http://127.0.0.1:3200` | `GROW_GRAFANA_TEMPO_URL`, `TEMPO_URL`, `GROW_TEMPO_URL` |
| `tenantId` | none | `GROW_GRAFANA_TEMPO_TENANT_ID`, `TEMPO_TENANT_ID`, `GROW_TEMPO_TENANT_ID` |
| `token` | none | `GROW_GRAFANA_TEMPO_TOKEN`, `GROW_TEMPO_TOKEN` |
| `connectTimeout`, `requestTimeout`, `totalTimeout` | 10 s, 30 s, 120 s | `GROW_GRAFANA_TEMPO_<NAME>`, `GROW_TEMPO_<NAME>` (`CONNECT_TIMEOUT`, `REQUEST_TIMEOUT`, `TOTAL_TIMEOUT`) |
| `retry.maxRetries` | 3 | `GROW_GRAFANA_TEMPO_MAX_RETRIES`, `GROW_TEMPO_MAX_RETRIES` |
| TLS (`caCertPath`, `clientCertPath`, `clientKeyPath`, `insecureSkipVerify`) | system roots | `GROW_GRAFANA_TEMPO_CA_CERT` / `_CLIENT_CERT` / `_CLIENT_KEY` / `_INSECURE_SKIP_VERIFY`, then the `GROW_TEMPO_*` alias of each |
| `proxy` | none | `GROW_GRAFANA_TEMPO_PROXY`, `GROW_TEMPO_PROXY` |

- **Tenant.** `tenantId` is sent as `X-Scope-OrgID` on every query call. A multi-tenant Tempo
  (`multitenancy_enabled: true`) needs it; a single-tenant Tempo ignores it. `tenant-a|tenant-b`
  runs a federated query. Allowed characters: `A-Z a-z 0-9 ! - _ . * ' ( ) |`, at most 150.
- **Token.** Tempo has no authentication of its own. `token` is sent as `Authorization: Bearer …`
  for an authenticating reverse proxy in front of Tempo; it is redacted in every string form, log
  line and error message. A blank token is a `ConfigError`.

The tracing variables (`GROW_GRAFANA_TEMPO_OTLP_*`, `OTEL_EXPORTER_OTLP_*`, `OTEL_SERVICE_NAME`,
`OTEL_RESOURCE_ATTRIBUTES`, `OTEL_TRACES_SAMPLER*`) are listed on [Tracing setup](functions/tracing-setup.md).

## Errors

Tempo answers are mapped to the shared [error codes](errors.md):

| Tempo answer | Code |
| - | - |
| 400 (invalid TraceQL, bad parameter) | `VALIDATION` |
| 401 `no org id` (multi-tenant Tempo, no tenant) | `AUTHENTICATION`, message "tenant required (X-Scope-OrgID)" |
| 401 / 403 from an auth proxy | `AUTHENTICATION` / `PERMISSION_DENIED` |
| 404 on a feature route (e.g. metrics disabled) | `UNSUPPORTED` |
| v2 trace miss (200 `{"trace":{}}`), v1 trace miss (404) | not an error: null / `None` / empty |
| 429, 5xx, network errors | `RATE_LIMITED`, `UNAVAILABLE` (retried with backoff, `Retry-After` honoured) |
| Invalid TraceQL found client side (unbalanced braces, unknown scope) | `VALIDATION` before any request |

Tempo's tag endpoints silently ignore an invalid `q` and return unfiltered values, so the SDK checks
TraceQL passed to `searchTags` / `searchTagValues` on the client side first.
