# @grow-codes/grafana-tempo

TypeScript SDK for [Grafana Tempo](https://grow-codes-packages.github.io/grafana-tempo/) by Grow Codes:
OpenTelemetry tracing with the Tempo OTLP preset (`initTracing`), the Tempo query API
(`getTrace`, TraceQL `search`, tags, TraceQL metrics) and a TraceQL builder.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/grafana-tempo
```

```ts
import { TempoClient, TraceQL, initTracing, withSpan } from "@grow-codes/grafana-tempo";

// OTLP/HTTP protobuf to GROW_GRAFANA_TEMPO_OTLP_ENDPOINT / OTEL_EXPORTER_OTLP_* (default :4318).
const tracing = initTracing({ serviceName: "checkout", tenantId: "team-a" });
await withSpan("checkout", async () => chargeCard());

// Query API: GROW_GRAFANA_TEMPO_URL (or TEMPO_URL), tenant GROW_GRAFANA_TEMPO_TENANT_ID.
const client = TempoClient.fromEnv();
try {
  const trace = await client.query.getTrace("5b8efff798038103d269b633813fc60c");
  const hits = await client.query.search({
    traceql: TraceQL.span({ "resource.service.name": "checkout" }).and("duration", ">", "200ms"),
    limit: 10,
  });
} finally {
  await tracing.shutdown();
  await client.close();
}
```

Every error is a `TempoError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/grafana-tempo/testing`.

Documentation: https://grow-codes-packages.github.io/grafana-tempo/ · Licence: Apache-2.0.
Grafana Tempo is a trademark of Grafana Labs; this package is not affiliated with or endorsed by it.
