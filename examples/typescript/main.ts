// Grafana Tempo with the Grow Codes TypeScript SDK: send spans over OTLP with initTracing, read
// the trace back by id, then use TraceQL search, tag values and TraceQL metrics.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200 \
//   GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318 npm start
//
// Multi-tenant Tempo: also set GROW_GRAFANA_TEMPO_TENANT_ID (sent as X-Scope-OrgID).

import {
  TempoClient,
  TempoError,
  TraceQL,
  addEvent,
  currentTraceId,
  initTracing,
  withSpan,
} from "@grow-codes/grafana-tempo";

const service = `example-checkout-${Date.now()}`;
const client = TempoClient.fromEnv({
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
});
const tracing = initTracing({ serviceName: service, serviceVersion: "1.0.0" });

try {
  const health = await client.health(); // never throws for an unreachable service
  console.log("health:", health);
  if (!health.healthy) process.exit(1);
  console.log("server version:", await client.serverVersion());

  // 1. Send a small trace over OTLP/HTTP protobuf.
  const traceId = await withSpan("checkout", async (span) => {
    span.setAttribute("cart.items", 3);
    addEvent("prices.loaded");
    await withSpan("charge card", () => new Promise((r) => setTimeout(r, 250)));
    return currentTraceId();
  });
  await tracing.forceFlush();

  // 2. Trace by id is visible at once.
  const trace = await client.query.waitForTrace(traceId!, {
    timeoutMs: 15_000,
    minSpans: 2,
  });
  for (const s of trace.spans)
    console.log(`  ${s.name}: ${s.durationMicros} µs (${s.serviceName})`);

  // 3. Search, tags and metrics see new data about 30 s after ingest.
  console.log("waiting 35 s for the search index …");
  await new Promise((r) => setTimeout(r, 35_000));
  const traceql = TraceQL.span({ "resource.service.name": service })
    .and("duration", ">", "200ms")
    .select("span.cart.items");
  console.log("TraceQL:", traceql.toString());
  const result = await client.query.search({ traceql, limit: 5 });
  console.log(
    "search hits:",
    result.traces.map((t) => t.traceId),
  );
  console.log("operations:", await client.query.listOperations(service));
  const metrics = await client.query.traceQLMetrics(
    `{ resource.service.name = ${TraceQL.quote(service)} } | count_over_time()`,
    { sinceMs: 15 * 60_000, stepMs: 60_000 },
  );
  console.log(
    "spans counted:",
    metrics.series.flatMap((s) => s.samples).reduce((n, p) => n + p.value, 0),
  );
} catch (err) {
  if (err instanceof TempoError) {
    // Canonical code + HTTP status; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, {
      httpStatus: err.httpStatus,
    });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await tracing.shutdown();
  await client.close();
}
