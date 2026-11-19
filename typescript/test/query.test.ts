import { afterEach, describe, expect, it } from "vitest";
import {
  TempoClient,
  TempoQueryClient,
  TraceQL,
  goDuration,
  mapTempoError,
  NotFoundError,
  type Trace,
} from "../src/index.js";
import type { MockServer } from "./helpers/server.js";
import { fixture, parse, tempoServer, type Route } from "./support/tempo.js";

const servers: MockServer[] = [];
const clients: TempoQueryClient[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

async function setup(
  routes: Record<string, Route>,
  tenantId?: string,
): Promise<{ s: MockServer; q: TempoQueryClient }> {
  const s = await tempoServer(routes);
  servers.push(s);
  const q = new TempoQueryClient({
    url: s.url,
    retry: { maxRetries: 0 },
    ...(tenantId === undefined ? {} : { tenantId }),
  });
  clients.push(q);
  return { s, q };
}

const ID = "00f74bc1b058a1a8b85f9ae0231e4001";
const START = new Date(1795034977_000);
const END = new Date(1795035036_500);

describe("getTrace", () => {
  it("normalizes the v2 response (base64 ids, ns times, enums, resources)", async () => {
    const { s, q } = await setup({ "/api/v2/traces/*": "getTrace.v2-ok" });
    const trace = (await q.getTrace(ID)) as Trace;
    expect(trace.traceId).toBe(ID);
    expect(trace.spans).toHaveLength(4);
    const root = trace.spans[0]!;
    expect(root).toMatchObject({
      traceId: ID,
      spanId: "a000000000000001",
      name: "GET /orders",
      kind: "server",
      serviceName: "grow-sdk-verify-179503498127460-api",
      startMicros: 1795035005729668,
      endMicros: 1795035006179668,
      durationMicros: 450000,
      status: { code: "ok" },
    });
    expect(root.parentSpanId).toBeUndefined();
    expect(root.attributes).toMatchObject({
      "http.request.method": "GET",
      "http.response.status_code": 200,
      "grow.note": 'a "quoted" \\ value ü',
      "grow weird.key": "x y",
    });
    expect(root.events).toEqual([
      { name: "cache-miss", timeMicros: 1795035005730668, attributes: {} },
    ]);
    const db = trace.spans.find((sp) => sp.name === "SELECT orders")!;
    expect(db.serviceName).toBe("grow-sdk-verify-179503498127460-db");
    expect(db.parentSpanId).toBe("a000000000000002");
    const starts = trace.spans.map((sp) => sp.startMicros);
    expect([...starts].sort((a, b) => a - b)).toEqual(starts);
    const req = s.requests[0]!;
    expect(parse(req).path).toBe(`/api/v2/traces/${ID}`);
    expect(req.headers.accept).toBe("application/json");
  });

  it("sends the range as epoch seconds and normalizes the id", async () => {
    const { s, q } = await setup({ "/api/v2/traces/*": "getTrace.v2-with-range" });
    const trace = await q.getTrace(" F74BC1B058A1A8B85F9AE0231E4001 ", { start: START, end: END });
    expect(trace?.traceId).toBe(ID);
    const { path, query } = parse(s.requests[0]!);
    expect(path).toBe(`/api/v2/traces/${ID}`);
    expect(query.get("start")).toBe("1795034977");
    expect(query.get("end")).toBe("1795035037");
  });

  it("returns null for a v2 miss (200 with an empty trace)", async () => {
    const { q } = await setup({ "/api/v2/traces/*": "getTrace.v2-not-found" });
    expect(await q.getTrace("1")).toBeNull();
    const { q: q2 } = await setup({ "/api/v2/traces/*": { status: 200, body: "" } });
    expect(await q2.getTrace("1")).toBeNull();
  });

  it("falls back to v1 when the v2 route is missing", async () => {
    const { s, q } = await setup({ "/api/traces/*": "getTrace.v1-ok" });
    const trace = await q.getTrace(ID);
    expect(trace?.spans).toHaveLength(4);
    expect(s.requests.map((r) => parse(r).path)).toEqual([
      `/api/v2/traces/${ID}`,
      `/api/traces/${ID}`,
    ]);
    const { q: q2 } = await setup({ "/api/traces/*": "getTrace.v1-not-found" });
    expect(await q2.getTrace("1")).toBeNull();
    const { q: q3 } = await setup({ "/api/traces/*": { status: 200, body: "" } });
    expect(await q3.getTrace("1")).toBeNull();
  });

  it("returns the trace for the right tenant and null for another", async () => {
    const { s, q } = await setup({ "/api/v2/traces/*": "getTrace.mt-tenant-a" }, "tenant-a");
    const trace = await q.getTrace("0af7651916cd43dd8448eb211c80319c");
    expect(trace?.spans.length).toBeGreaterThan(0);
    expect(s.requests[0]!.headers["x-scope-orgid"]).toBe("tenant-a");
    const { q: q2 } = await setup({ "/api/v2/traces/*": "getTrace.mt-wrong-tenant" }, "tenant-b");
    expect(await q2.getTrace("0af7651916cd43dd8448eb211c80319c")).toBeNull();
  });

  it("maps errors: invalid id (client side), 401 tenant, 400 range", async () => {
    const { s, q } = await setup({ "/api/v2/traces/*": "getTrace.mt-no-tenant-401" });
    await expect(q.getTrace("nothex")).rejects.toMatchObject({ code: "VALIDATION" });
    await expect(q.getTrace("a".repeat(40))).rejects.toMatchObject({ code: "VALIDATION" });
    expect(s.requests).toHaveLength(0);
    const err = await q.getTrace(ID).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "AUTHENTICATION", httpStatus: 401 });
    expect((err as Error).message).toContain("tenant required (X-Scope-OrgID)");
    const { q: q2 } = await setup({
      "/api/v2/traces/*": "getTrace.v2-range-end-before-start",
    });
    await expect(q2.getTrace(ID, { start: END, end: START })).rejects.toMatchObject({
      code: "VALIDATION",
      httpStatus: 400,
    });
    await expect(q2.getTrace(ID, { start: new Date("nope") })).rejects.toMatchObject({
      code: "VALIDATION",
    });
  });

  it("waitForTrace polls getTrace until the trace appears", async () => {
    let calls = 0;
    const { q } = await setup({
      "/api/v2/traces/*": () => (++calls < 2 ? "getTrace.v2-not-found" : "getTrace.v2-ok"),
    });
    const trace = await q.waitForTrace(ID, { intervalMs: 10, timeoutMs: 5000, minSpans: 4 });
    expect(trace.spans).toHaveLength(4);
    expect(calls).toBe(2);
  });

  it("getDependencies is unsupported", async () => {
    const { q } = await setup({});
    await expect(q.getDependencies({})).rejects.toMatchObject({ code: "UNSUPPORTED" });
  });
});

describe("search", () => {
  it("sends TraceQL, range, limit, spss and maps the result", async () => {
    const { s, q } = await setup({ "/api/search": "search.builder-duration-select" });
    const traceql = TraceQL.span({
      "resource.service.name": "grow-sdk-verify-179503498127460-api",
    })
      .and("duration", ">", "200ms")
      .select("span.http.response.status_code");
    const res = await q.search({ traceql, start: START, end: END, limit: 20, spss: 10 });
    const { query } = parse(s.requests[0]!);
    expect(query.get("q")).toBe(fixture("search.builder-duration-select").request.query?.q);
    expect(query.get("limit")).toBe("20");
    expect(query.get("spss")).toBe("10");
    expect(query.get("start")).toBe("1795034977");
    expect(res.metrics).toEqual({ inspectedBytes: 29179, completedJobs: 2, totalJobs: 2 });
    expect(res.traces).toHaveLength(1);
    const t = res.traces[0]!;
    expect(t).toMatchObject({
      traceId: ID,
      rootServiceName: "grow-sdk-verify-179503498127460-api",
      rootTraceName: "GET /orders",
      startMicros: 1795035005729668,
      durationMicros: 450000,
      serviceStats: {
        "grow-sdk-verify-179503498127460-api": { spanCount: 3, errorCount: 0 },
        "grow-sdk-verify-179503498127460-db": { spanCount: 1, errorCount: 0 },
      },
    });
    expect(t.spanSets[0]!.matched).toBe(2);
    expect(t.spanSets[0]!.spans[0]).toEqual({
      spanId: "a000000000000001",
      startMicros: 1795035005729668,
      durationMicros: 450000,
      attributes: {
        "http.response.status_code": 200,
        "service.name": "grow-sdk-verify-179503498127460-api",
      },
    });
  });

  it("returns span names when selected and sends no q without a query", async () => {
    const { s, q } = await setup({ "/api/search": "search.select-name-status" });
    const res = await q.search();
    expect(parse(s.requests[0]!).query.has("q")).toBe(false);
    expect(parse(s.requests[0]!).query.has("limit")).toBe(false);
    expect(res.traces[0]!.spanSets[0]!.spans.some((sp) => sp.name !== undefined)).toBe(true);
    await q.search({ traceql: "  " });
    expect(parse(s.requests[1]!).query.has("q")).toBe(false);
  });

  it("handles a legacy spanSet-only answer and empty fields", async () => {
    const { q } = await setup({
      "/api/search": {
        status: 200,
        body: {
          traces: [
            { traceID: "abc", spanSet: { spans: [{ spanID: "1" }] } },
            { traceID: "def" },
            { traceID: "123", spanSets: [{}], serviceStats: { a: {} } },
          ],
        },
      },
    });
    const res = await q.search({ traceql: "{ }" });
    expect(res.metrics).toEqual({});
    expect(res.traces[0]).toEqual({
      traceId: "abc".padStart(32, "0"),
      startMicros: 0,
      durationMicros: 0,
      spanSets: [
        {
          spans: [
            { spanId: "0000000000000001", startMicros: 0, durationMicros: 0, attributes: {} },
          ],
          matched: 0,
        },
      ],
      serviceStats: {},
    });
    expect(res.traces[1]!.spanSets).toEqual([]);
    expect(res.traces[2]!.spanSets).toEqual([{ spans: [], matched: 0 }]);
    expect(res.traces[2]!.serviceStats).toEqual({ a: { spanCount: 0, errorCount: 0 } });
    const { q: q2 } = await setup({ "/api/search": { status: 200, body: "" } });
    expect(await q2.search()).toEqual({ traces: [], metrics: {} });
    const { q: q3 } = await setup({
      "/api/search": { status: 200, body: { traces: [], metrics: { a: "x", b: null } } },
    });
    expect((await q3.search()).metrics).toEqual({ a: 0, b: 0 });
  });

  it("maps errors and validates options", async () => {
    const { q } = await setup({ "/api/search": "search.invalid-traceql-400" });
    const err = await q.search({ traceql: "{ bad" }).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "VALIDATION", httpStatus: 400 });
    expect((err as Error).message).toContain("invalid TraceQL query");
    await expect(q.search({ limit: 0 })).rejects.toMatchObject({ code: "VALIDATION" });
    await expect(q.search({ spss: 1.5 })).rejects.toMatchObject({ code: "VALIDATION" });
    const { q: mt } = await setup({ "/api/search": "search.mt-no-tenant-401" });
    await expect(mt.search()).rejects.toMatchObject({ code: "AUTHENTICATION" });
    const { q: missing } = await setup({});
    await expect(missing.search()).rejects.toMatchObject({ code: "UNSUPPORTED", httpStatus: 404 });
  });
});

describe("searchTraces", () => {
  it("builds one TraceQL spanset and maps TraceSummary", async () => {
    const { s, q } = await setup({ "/api/search": "search.builder-duration-select" });
    const res = await q.searchTraces({
      service: "api",
      operation: "GET /orders",
      tags: { "grow.run": "1", "odd key": 'a"b' },
      minDurationMicros: 1000,
      maxDurationMicros: 2_000_000.4,
      start: START,
      end: END,
      limit: 5,
    });
    const { query } = parse(s.requests[0]!);
    expect(query.get("q")).toBe(
      '{ resource.service.name = "api" && name = "GET /orders" && .grow.run = "1" && ."odd key" = "a\\"b" && trace:duration >= 1000us && trace:duration <= 2000000us }',
    );
    expect(query.get("limit")).toBe("5");
    expect(query.get("start")).toBe("1795034977");
    expect(res).toEqual([
      {
        traceId: ID,
        rootServiceName: "grow-sdk-verify-179503498127460-api",
        rootName: "GET /orders",
        startMicros: 1795035005729668,
        durationMicros: 450000,
        spanCount: 4,
      },
    ]);
  });

  it("defaults to {} with limit 20 and omits missing root fields", async () => {
    const { s, q } = await setup({
      "/api/search": { status: 200, body: { traces: [{ traceID: "1" }] } },
    });
    const res = await q.searchTraces({}, { signal: new AbortController().signal });
    expect(parse(s.requests[0]!).query.get("q")).toBe("{}");
    expect(parse(s.requests[0]!).query.get("limit")).toBe("20");
    expect(res[0]).toEqual({
      traceId: "1".padStart(32, "0"),
      startMicros: 0,
      durationMicros: 0,
      spanCount: 0,
    });
    await q.searchTraces();
    await expect(q.searchTraces({ minDurationMicros: -1 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
  });
});

describe("tags", () => {
  it("searchTags returns tags per scope and passes scope, q and range", async () => {
    const { s, q } = await setup({ "/api/v2/search/tags": "searchTags.v2-filtered-q" });
    const tags = await q.searchTags({
      scope: "span",
      query: TraceQL.span({ "resource.service.name": "grow-sdk-verify-179503498127460-db" }),
      start: START,
      end: END,
    });
    expect(Object.keys(tags)).toEqual(["span"]);
    expect(tags.span).toContain("db.system.name");
    const { query } = parse(s.requests[0]!);
    expect(query.get("scope")).toBe("span");
    expect(query.get("q")).toBe(fixture("searchTags.v2-filtered-q").request.query?.q);
  });

  it("searchTags without scope returns every scope; empty scopes give {}", async () => {
    const { q } = await setup({ "/api/v2/search/tags": "searchTags.v2-all-scopes" });
    const tags = await q.searchTags();
    expect(Object.keys(tags).sort()).toEqual(["event", "intrinsic", "link", "resource", "span"]);
    const { q: q2 } = await setup({
      "/api/v2/search/tags": "searchTags.v2-scope-instrumentation",
    });
    expect(await q2.searchTags({ scope: "instrumentation" })).toEqual({});
    const { q: q3 } = await setup({
      "/api/v2/search/tags": { status: 200, body: { scopes: [{ tags: ["x"] }, { name: "a" }] } },
    });
    expect(await q3.searchTags()).toEqual({ a: [] });
    const { q: q4 } = await setup({ "/api/v2/search/tags": { status: 200, body: "" } });
    expect(await q4.searchTags()).toEqual({});
  });

  it("searchTags validates scope and query, maps 401", async () => {
    const { s, q } = await setup({ "/api/v2/search/tags": "searchTags.mt-no-tenant-401" });
    await expect(q.searchTags({ scope: "nope" as "span" })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(q.searchTags({ query: "{ bad" })).rejects.toMatchObject({ code: "VALIDATION" });
    expect(s.requests).toHaveLength(0);
    await expect(q.searchTags()).rejects.toMatchObject({ code: "AUTHENTICATION" });
  });

  it("searchTagValues encodes the tag as one segment and keeps types", async () => {
    const { s, q } = await setup({
      "/api/v2/search/tag/*": (req) =>
        parse(req).path.includes("weird")
          ? "searchTagValues.v2-quoted-tag-path"
          : "searchTagValues.v2-int-typed",
    });
    expect(await q.searchTagValues("span.grow weird.key", { start: START })).toEqual([
      { type: "string", value: "x y" },
    ]);
    expect(s.requests[0]!.url).toContain("/api/v2/search/tag/span.%22grow%20weird.key%22/values");
    const values = await q.searchTagValues("span.http.response.status_code", {
      query: '{ resource.service.name = "x" }',
      end: END,
    });
    expect(values).toEqual([
      { type: "int", value: "200" },
      { type: "int", value: "503" },
    ]);
    expect(parse(s.requests[1]!).query.get("q")).toBe('{ resource.service.name = "x" }');
  });

  it("searchTagValues handles v1-style strings, empty answers and validation", async () => {
    const { q } = await setup({
      "/api/v2/search/tag/*": {
        status: 200,
        body: { tagValues: ["a", { value: "b" }, { type: "int" }] },
      },
    });
    expect(await q.searchTagValues("status")).toEqual([
      { type: "string", value: "a" },
      { type: "string", value: "b" },
      { type: "int", value: "" },
    ]);
    await expect(q.searchTagValues("service.name")).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(q.searchTagValues("name", { query: "{ bad" })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    const { q: q2 } = await setup({ "/api/v2/search/tag/*": { status: 200, body: "" } });
    expect(await q2.searchTagValues("name")).toEqual([]);
    const { q: q3 } = await setup({ "/api/v2/search/tag/*": "searchTagValues.v2-unscoped-tag" });
    await expect(q3.searchTagValues("span.x")).rejects.toMatchObject({
      code: "VALIDATION",
      httpStatus: 400,
    });
  });

  it("listServices and listOperations are sorted and unique", async () => {
    const { s, q } = await setup({
      "/api/v2/search/tag/resource.service.name/values": "searchTagValues.v2-service-name",
      "/api/v2/search/tag/name/values": "searchTagValues.v2-name-filtered",
    });
    const services = await q.listServices({ start: START, end: END });
    expect(services).toEqual([...services].sort());
    expect(services).toContain("grow-sdk-verify-179503498127460-api");
    const ops = await q.listOperations("grow-sdk-verify-179503498127460-api");
    expect(ops).toEqual([...new Set(ops)].sort());
    expect(ops).toContain("GET /orders");
    expect(parse(s.requests[1]!).query.get("q")).toBe(
      '{ resource.service.name = "grow-sdk-verify-179503498127460-api" }',
    );
    await expect(q.listOperations(" ")).rejects.toMatchObject({ code: "VALIDATION" });
    await q.listServices();
  });
});

describe("traceQLMetrics", () => {
  it("maps series, zero samples, exemplars and step", async () => {
    const { s, q } = await setup({
      "/api/metrics/query_range": "traceQLMetrics.quantile-exemplars",
    });
    const res = await q.traceQLMetrics(
      '{ resource.service.name = "api" } | quantile_over_time(duration, .9)',
      { start: START, end: END, stepMs: 5000, exemplars: 5 },
    );
    const { query } = parse(s.requests[0]!);
    expect(query.get("step")).toBe("5s");
    expect(query.get("exemplars")).toBe("5");
    expect(query.get("start")).toBe("1795034977");
    expect(res.stepMs).toBe(5000);
    const series = res.series[0]!;
    expect(series.labels).toEqual({ p: 0.9 });
    expect(series.samples[0]).toEqual({ timestampMs: 1795034945000, value: 0 });
    expect(series.samples.at(-1)).toEqual({ timestampMs: 1795035010000, value: 0.536870912 });
    expect(series.exemplars[0]).toEqual({
      labels: {
        "trace:id": "f74bc1b058a1a8b85f9ae0231e4001",
        "resource.service.name": "grow-sdk-verify-179503498127460-api",
        duration: "450ms",
      },
      value: 0.45,
      timestampMs: 1795035005729,
    });
  });

  it("accepts a builder, since and sub-second steps; handles sparse answers", async () => {
    const { s, q } = await setup({
      "/api/metrics/query_range": {
        status: 200,
        body: {
          series: [
            { labels: [{ value: { stringValue: "x" } }, { key: "e", value: {} }] },
            { samples: [{}], exemplars: [{}] },
          ],
        },
      },
    });
    const res = await q.traceQLMetrics(TraceQL.span(), { sinceMs: 900_000, stepMs: 1500 });
    const { query } = parse(s.requests[0]!);
    expect(query.get("q")).toBe("{}");
    expect(query.get("since")).toBe("900s");
    expect(query.get("step")).toBe("1500ms");
    expect(res).toEqual({
      series: [
        { labels: {}, samples: [], exemplars: [] },
        {
          labels: {},
          samples: [{ timestampMs: 0, value: 0 }],
          exemplars: [{ labels: {}, value: 0, timestampMs: 0 }],
        },
      ],
    });
    const { q: q2 } = await setup({ "/api/metrics/query_range": { status: 200, body: "" } });
    expect(await q2.traceQLMetrics("{} | rate()")).toEqual({ series: [] });
  });

  it("maps errors and validates options", async () => {
    const { q } = await setup({ "/api/metrics/query_range": "traceQLMetrics.no-pipeline-400" });
    const err = await q.traceQLMetrics("{ }").catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "VALIDATION", httpStatus: 400 });
    expect((err as Error).message).toContain("not a metrics query");
    await expect(q.traceQLMetrics(" ")).rejects.toMatchObject({ code: "VALIDATION" });
    await expect(q.traceQLMetrics("{}", { stepMs: 0 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(q.traceQLMetrics("{}", { exemplars: -1 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    const { q: mt } = await setup({
      "/api/metrics/query_range": "traceQLMetrics.mt-no-tenant-401",
    });
    await expect(mt.traceQLMetrics("{} | count_over_time()")).rejects.toMatchObject({
      code: "AUTHENTICATION",
    });
  });
});

describe("probes and helpers", () => {
  it("buildInfo fills missing fields with empty strings; 404 is unsupported", async () => {
    const { q } = await setup({
      "/api/status/buildinfo": { status: 200, body: { version: "3.1.1", branch: 7 } },
    });
    expect(await q.buildInfo()).toEqual({
      version: "3.1.1",
      revision: "",
      branch: "",
      buildUser: "",
      buildDate: "",
      goVersion: "",
    });
    const { q: q2 } = await setup({ "/api/status/buildinfo": { status: 200, body: "" } });
    expect((await q2.buildInfo()).version).toBe("");
    const { q: q3 } = await setup({});
    await expect(q3.buildInfo()).rejects.toMatchObject({ code: "UNSUPPORTED" });
  });

  it("a client-wrapping query client does not close the client", async () => {
    const { s } = await setup({ "/api/echo": "echo.ok" });
    const client = new TempoClient({ url: s.url });
    const q = new TempoQueryClient(client);
    await q.close();
    expect(await client.echo()).toBe("echo");
    await client.close();
  });

  it("goDuration and mapTempoError", () => {
    expect(goDuration(60_000, "x")).toBe("60s");
    expect(goDuration(250, "x")).toBe("250ms");
    expect(() => goDuration(Number.NaN, "x")).toThrow(/x must be positive/);
    const nf = new NotFoundError("op: not found (HTTP 404)", { httpStatus: 404 });
    expect(mapTempoError(nf, "op", false)).toBe(nf);
    expect(mapTempoError(nf, "op", true)).toMatchObject({ code: "UNSUPPORTED" });
    const other = new Error("x");
    expect(mapTempoError(other, "op", true)).toBe(other);
  });
});
