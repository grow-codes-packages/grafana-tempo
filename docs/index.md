# Grafana Tempo SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/grafana-tempo/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Grafana Tempo** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`grafana/tempo:3.1.1` in Docker.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/grafana-tempo`](https://www.npmjs.com/package/@grow-codes/grafana-tempo) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-typescript.json) |
| Rust | [`grow-grafana-tempo`](https://crates.io/crates/grow-grafana-tempo) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/grafana-tempo/go`](https://pkg.go.dev/github.com/grow-codes-packages/grafana-tempo/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-go.json) |
| Python | [`grow-grafana-tempo`](https://pypi.org/project/grow-grafana-tempo/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-python.json) |
| Java | [`codes.grow.packages:grafana-tempo`](https://central.sonatype.com/artifact/codes.grow.packages/grafana-tempo) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/grafana-tempo/badges/coverage-java.json) |

## What you get

- **Client** with health, authenticated ping and server version ([Client](client.md)).
- **Configuration** from code or `GROW_GRAFANA_TEMPO_*` / vendor environment variables, immutable
  and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).
- **Tracing**: OpenTelemetry setup, span helpers, propagation, framework adapters and trace
  queries ([Tracing](tracing.md)).
- **Tempo query client**: trace by id (v2 with v1 fallback), TraceQL search, tags, tag values and
  TraceQL metrics, with multi-tenancy (`X-Scope-OrgID`) built in ([Query client](functions/query.md)).
- **TraceQL builder** with escaping, quoting and structural operators
  ([TraceQL builder](functions/traceql.md)).
- **`initTracing` preset** for Tempo's OTLP receivers (HTTP 4318 or gRPC 4317) with the tenant
  header ([Tracing setup](functions/tracing-setup.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Grafana Tempo is a trademark of
    Grafana Labs. This project is not affiliated with or endorsed by Grafana Labs. See
    [License & disclaimer](license.md).
