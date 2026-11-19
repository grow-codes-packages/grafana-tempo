// Integration tests of the tracing setup and the Tempo query API against the real Tempo:
// spans are exported over OTLP/HTTP protobuf and OTLP/gRPC with initTracing, then read back with
// every TempoQueryClient function. Trace-by-id is visible at once; search, tags and metrics need
// about 30 s (Tempo live-store cut), so those tests poll for up to 150 s.

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { after, before, describe, test } from "node:test";
import {
  TempoClient,
  TempoError,
  TempoQueryClient,
  TraceQL,
  addEvent,
  currentSpanId,
  currentTraceId,
  extract,
  extractHeaders,
  initTracing,
  inject,
  injectHeaders,
  pollUntil,
  recordException,
  setError,
  traceFields,
  traced,
  tracedFetch,
  withSpan,
  type Span,
  type Trace,
  type TracingHandle,
} from "@grow-codes/grafana-tempo";
import { loadBootstrapEnv, runId } from "./env.ts";

const RUN = `${runId.replace(/[^a-z0-9-]/gi, "").slice(0, 20)}-${randomBytes(3).toString("hex")}`;
const SERVICE = `grow-ts-it-${RUN}`;
const CHILD = `${SERVICE}-db`;
const NOTE = 'a "quoted" \\ value ü';
const SEARCH_TIMEOUT_MS = 150_000;

let client: TempoClient;
let query: TempoQueryClient;
let tracing: TracingHandle;
let traceId = "";
let fields: { trace_id?: string; span_id?: string } = {};
let rootSpanId = "";
let windowStart: Date;

function env(name: string): string {
  const value = process.env[name];
  assert.ok(value, `${name} must be set (bootstrap)`);
  return value;
}

function range(): { start: Date; end: Date } {
  return { start: windowStart, end: new Date(Date.now() + 60_000) };
}

function poll<T>(what: string, fn: () => Promise<T>, ok: (value: T) => boolean): Promise<T> {
  return pollUntil(fn, ok, { timeoutMs: SEARCH_TIMEOUT_MS, intervalMs: 2000, operation: what });
}

function byName(trace: Trace, name: string): Span {
  const span = trace.spans.find((s) => s.name === name);
  assert.ok(span, `span ${name} in ${JSON.stringify(trace.spans.map((s) => s.name))}`);
  return span;
}

before(async () => {
  loadBootstrapEnv();
  windowStart = new Date(Date.now() - 10 * 60_000);
  client = TempoClient.fromEnv();
  query = client.query;

  // Global handle: OTLP/HTTP protobuf to GROW_GRAFANA_TEMPO_OTLP_ENDPOINT (from the environment).
  tracing = initTracing({
    serviceName: SERVICE,
    serviceVersion: "1.0.0",
    environment: "integration",
    sampler: "always_on",
    resourceAttributes: { "grow.run": RUN },
  });
  assert.equal(tracing.config.protocol, "http/protobuf");
  // Second service over OTLP/gRPC (not global).
  const grpc = initTracing({
    serviceName: CHILD,
    protocol: "grpc",
    endpoint: env("GROW_GRAFANA_TEMPO_OTLP_GRPC_ENDPOINT"),
    setGlobal: false,
  });

  await withSpan(
    "GET /orders",
    async (span) => {
      span.setAttribute("http.response.status_code", 200);
      span.setAttribute("grow.run", RUN);
      span.setAttribute("grow.note", NOTE);
      span.setAttribute("grow weird.key", "x y");
      addEvent("cache-miss", { n: 1 });
      traceId = currentTraceId() ?? "";
      rootSpanId = currentSpanId() ?? "";
      fields = traceFields();

      const loadOrder = traced(async () => {
        await new Promise((resolve) => setTimeout(resolve, 250));
      }, "load order");
      await loadOrder();

      try {
        withSpan("render", () => {
          recordException(new Error("template warning"));
          throw new Error("boom");
        });
      } catch {
        // recorded on the span by withSpan
      }
      withSpan("validate", () => {
        setError(new Error("invalid cart"));
      });

      // Propagation: headers carry the context to the other "service" (exported over gRPC).
      const headers = injectHeaders({} as Record<string, string>);
      const carrier = inject({} as Record<string, string>);
      assert.equal(carrier.traceparent, headers.traceparent);
      await withSpan(
        "SELECT orders",
        async () => {
          await new Promise((resolve) => setTimeout(resolve, 20));
        },
        { tracer: grpc.tracer("db"), context: extractHeaders(headers), kind: "server" },
      );
      assert.ok(extract(carrier));

      // tracedFetch: a CLIENT span around a real call to Tempo.
      const fetchTraced = tracedFetch(fetch);
      const res = await fetchTraced(`${env("GROW_GRAFANA_TEMPO_URL")}/api/echo`);
      assert.equal(res.status, 200);
      await res.text();
    },
    { kind: "server", attributes: { "grow.kind": "root" } },
  );
  await tracing.forceFlush(30_000);
  await grpc.forceFlush(30_000);
  await grpc.shutdown();
  assert.match(traceId, /^[0-9a-f]{32}$/);
});

after(async () => {
  await tracing.shutdown();
  await client.close();
});

describe("tracing setup + getTrace (immediate)", () => {
  test("waitForTrace / getTrace return the whole tree with attributes, events and status", async () => {
    const trace = await query.waitForTrace(traceId, {
      timeoutMs: 30_000,
      intervalMs: 500,
      minSpans: 6,
    });
    assert.equal(trace.traceId, traceId);
    assert.equal(trace.spans.length, 6, JSON.stringify(trace.spans.map((s) => s.name)));
    const root = byName(trace, "GET /orders");
    assert.equal(root.spanId, rootSpanId);
    assert.deepEqual(fields, { trace_id: traceId, span_id: rootSpanId });
    assert.equal(root.kind, "server");
    assert.equal(root.serviceName, SERVICE);
    assert.equal(root.parentSpanId, undefined);
    assert.equal(root.attributes["http.response.status_code"], 200);
    assert.equal(root.attributes["grow.note"], NOTE);
    assert.equal(root.attributes["grow weird.key"], "x y");
    assert.equal(root.attributes["grow.kind"], "root");
    assert.deepEqual(
      root.events.map((e) => [e.name, e.attributes]),
      [["cache-miss", { n: 1 }]],
    );
    const load = byName(trace, "load order");
    assert.equal(load.parentSpanId, root.spanId);
    assert.ok(load.durationMicros >= 200_000, `load order took ${load.durationMicros} µs`);
    const render = byName(trace, "render");
    assert.deepEqual(render.status, { code: "error", message: "boom" });
    assert.ok(render.events.some((e) => e.name === "exception"));
    assert.equal(byName(trace, "validate").status.code, "error");
    const db = byName(trace, "SELECT orders");
    assert.equal(db.serviceName, CHILD, "exported over OTLP/gRPC");
    assert.equal(db.parentSpanId, root.spanId, "propagated through headers");
    const fetchSpan = byName(trace, "GET");
    assert.equal(fetchSpan.kind, "client");
    assert.equal(fetchSpan.attributes["http.response.status_code"], 200);
    const sorted = [...trace.spans].sort((a, b) => a.startMicros - b.startMicros);
    assert.deepEqual(
      trace.spans.map((s) => s.startMicros),
      sorted.map((s) => s.startMicros),
    );
  });

  test("getTrace accepts a range, returns null for a miss and rejects invalid ids", async () => {
    const ranged = await query.getTrace(traceId.toUpperCase(), range());
    assert.equal(ranged?.spans.length, 6);
    assert.equal(await query.getTrace(randomBytes(16).toString("hex")), null);
    await assert.rejects(query.getTrace("not-hex"), { code: "VALIDATION" });
  });

  test("the bootstrap seed trace has a link, an event and an error status", async () => {
    const seed = await query.getTrace(env("GROW_GRAFANA_TEMPO_SEED_TRACE_ID"));
    assert.ok(seed);
    assert.equal(seed.spans.length, 3);
    assert.ok(seed.spans.some((s) => s.links.length > 0));
    assert.ok(seed.spans.some((s) => s.events.length > 0));
    assert.ok(seed.spans.some((s) => s.status.code === "error"));
    const services = new Set(seed.spans.map((s) => s.serviceName));
    assert.ok(services.has(env("GROW_GRAFANA_TEMPO_SEED_SERVICE")));
    assert.ok(services.has(env("GROW_GRAFANA_TEMPO_SEED_CHILD_SERVICE")));
  });

  test("multi-tenant Tempo: export with tenantId, read back only as that tenant", async () => {
    const tenantA = env("GROW_GRAFANA_TEMPO_TENANT_ID");
    const tenantB = env("GROW_GRAFANA_TEMPO_OTHER_TENANT_ID");
    const mt = initTracing({
      serviceName: `${SERVICE}-mt`,
      endpoint: env("GROW_GRAFANA_TEMPO_MT_OTLP_ENDPOINT"),
      tenantId: tenantA,
      setGlobal: false,
    });
    const id = withSpan("mt-span", () => currentTraceId(), { tracer: mt.tracer("mt") });
    await mt.forceFlush(30_000);
    await mt.shutdown();
    assert.ok(id);
    const url = env("GROW_GRAFANA_TEMPO_MT_URL");
    const a = new TempoQueryClient({ url, tenantId: tenantA });
    const b = new TempoQueryClient({ url, tenantId: tenantB });
    const federated = new TempoQueryClient({ url, tenantId: `${tenantB}|${tenantA}` });
    const anonymous = new TempoQueryClient({ url, retry: { maxRetries: 0 } });
    try {
      const trace = await a.waitForTrace(id, { timeoutMs: 30_000, intervalMs: 500 });
      assert.equal(trace.spans[0]?.serviceName, `${SERVICE}-mt`);
      assert.equal(await b.getTrace(id), null);
      assert.equal((await federated.getTrace(id))?.spans.length, 1);
      await assert.rejects(anonymous.getTrace(id), (err: unknown) => {
        assert.ok(err instanceof TempoError);
        assert.equal(err.code, "AUTHENTICATION");
        return true;
      });
      const mtSeed = await a.getTrace(env("GROW_GRAFANA_TEMPO_MT_SEED_TRACE_ID"));
      assert.ok(mtSeed && mtSeed.spans.length > 0);
    } finally {
      await Promise.all([a.close(), b.close(), federated.close(), anonymous.close()]);
    }
  });

  test("getDependencies is UNSUPPORTED on Tempo", async () => {
    await assert.rejects(query.getDependencies(range()), { code: "UNSUPPORTED" });
  });
});

describe("search, tags and metrics (visible after ~30 s)", () => {
  test("search with the TraceQL builder: duration filter + select", async () => {
    const traceql = TraceQL.span({ "resource.service.name": SERVICE })
      .and("duration", ">", "200ms")
      .select("span.http.response.status_code");
    const res = await poll(
      "search",
      () => query.search({ traceql, ...range(), limit: 20, spss: 10 }),
      (r) => r.traces.some((t) => t.traceId === traceId),
    );
    const hit = res.traces.find((t) => t.traceId === traceId);
    assert.ok(hit);
    assert.equal(hit.rootServiceName, SERVICE);
    assert.equal(hit.rootTraceName, "GET /orders");
    assert.ok(hit.durationMicros >= 200_000);
    assert.equal(hit.serviceStats[SERVICE]?.spanCount, 5);
    assert.equal(hit.serviceStats[CHILD]?.spanCount, 1);
    const spans = hit.spanSets.flatMap((s) => s.spans);
    assert.equal(spans.length, 2, "root and load order are longer than 200 ms");
    assert.ok(
      spans.some(
        (s) => s.spanId === rootSpanId && s.attributes["http.response.status_code"] === 200,
      ),
    );
    assert.ok(typeof res.metrics.inspectedBytes === "number");
  });

  test("search escapes string values and quotes odd attribute names", async () => {
    const escaped = await query.search({
      traceql: TraceQL.span({ "resource.service.name": SERVICE, "span.grow.note": NOTE }),
      ...range(),
    });
    assert.ok(
      escaped.traces.some((t) => t.traceId === traceId),
      "escaped value matches",
    );
    const quoted = await query.search({
      traceql: TraceQL.span({ "resource.service.name": SERVICE })
        .and("span.grow weird.key", "=", "x y")
        .select("name", "status"),
      ...range(),
      spss: 1,
    });
    const hit = quoted.traces.find((t) => t.traceId === traceId);
    assert.ok(hit, "quoted attribute name matches");
    assert.equal(hit.spanSets[0]?.spans[0]?.name, "GET /orders");
    const errors = await query.search({
      traceql: TraceQL.span({ "resource.service.name": SERVICE }).and("status", "=", "error"),
      ...range(),
    });
    assert.ok(errors.traces.some((t) => t.traceId === traceId));
    await assert.rejects(query.search({ traceql: "{ bad" }), { code: "VALIDATION" });
    // Structural operator: the root span has a direct child "load order".
    const structural = await query.search({
      traceql: TraceQL.span({ "resource.service.name": SERVICE, name: "GET /orders" }).child(
        TraceQL.span({ name: "load order" }),
      ),
      ...range(),
    });
    assert.ok(
      structural.traces.some((t) => t.traceId === traceId),
      "structural child query matches",
    );
  });

  test("searchTraces maps service/operation/tags/duration to TraceQL", async () => {
    const found = await poll(
      "searchTraces",
      () =>
        query.searchTraces({
          service: SERVICE,
          operation: "GET /orders",
          tags: { "grow.run": RUN },
          minDurationMicros: 200_000,
          ...range(),
          limit: 5,
        }),
      (r) => r.some((t) => t.traceId === traceId),
    );
    const summary = found.find((t) => t.traceId === traceId);
    assert.ok(summary);
    assert.equal(summary.rootName, "GET /orders");
    assert.equal(summary.rootServiceName, SERVICE);
    assert.equal(summary.spanCount, 6);
    const none = await query.searchTraces({
      service: SERVICE,
      minDurationMicros: 60_000_000,
      ...range(),
    });
    assert.equal(none.length, 0);
  });

  test("searchTags and searchTagValues", async () => {
    const tags = await poll(
      "searchTags",
      () =>
        query.searchTags({
          scope: "span",
          query: TraceQL.span({ "resource.service.name": SERVICE }),
          ...range(),
        }),
      (t) => (t.span ?? []).includes("grow weird.key"),
    );
    assert.ok(tags.span?.includes("grow.note"));
    const all = await query.searchTags(range());
    assert.ok(all.resource?.includes("service.name"));
    assert.ok(all.intrinsic?.includes("duration"));

    const quoted = await query.searchTagValues("span.grow weird.key", {
      query: TraceQL.span({ "resource.service.name": SERVICE }),
      ...range(),
    });
    assert.deepEqual(quoted, [{ type: "string", value: "x y" }]);
    const codes = await query.searchTagValues("span.http.response.status_code", {
      query: TraceQL.span({ "resource.service.name": SERVICE }),
    });
    assert.ok(codes.some((v) => v.type === "int" && v.value === "200"));
    await assert.rejects(query.searchTagValues("service.name"), { code: "VALIDATION" });
  });

  test("listServices and listOperations", async () => {
    const services = await poll(
      "listServices",
      () => query.listServices(range()),
      (s) => s.includes(SERVICE) && s.includes(CHILD),
    );
    assert.deepEqual(services, [...services].sort());
    const ops = await poll(
      "listOperations",
      () => query.listOperations(SERVICE),
      (o) => o.includes("load order"),
    );
    for (const name of ["GET /orders", "load order", "render", "validate", "GET"]) {
      assert.ok(ops.includes(name), `${name} in ${JSON.stringify(ops)}`);
    }
    assert.ok(!ops.includes("SELECT orders"), "operations are per service");
  });

  test("traceQLMetrics counts the spans of the service", async () => {
    const q = `{ resource.service.name = ${TraceQL.quote(SERVICE)} } | count_over_time()`;
    const res = await poll(
      "traceQLMetrics",
      () => query.traceQLMetrics(q, { sinceMs: 15 * 60_000, stepMs: 5000 }),
      (r) => r.series.reduce((n, s) => n + s.samples.reduce((m, p) => m + p.value, 0), 0) >= 5,
    );
    const total = res.series.reduce((n, s) => n + s.samples.reduce((m, p) => m + p.value, 0), 0);
    assert.equal(total, 5);
    assert.equal(res.series[0]?.labels.__name__, "count_over_time");
    assert.equal(res.stepMs, 5000);
    const ranged = await query.traceQLMetrics(q, { ...range(), stepMs: 60_000, exemplars: 2 });
    assert.ok(ranged.series.length > 0);
    await assert.rejects(
      query.traceQLMetrics(`{ resource.service.name = "x" }`, { sinceMs: 300_000 }),
      {
        code: "VALIDATION",
      },
    );
  });
});
