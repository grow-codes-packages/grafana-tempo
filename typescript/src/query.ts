// Service-owned: Tempo query API (TempoQueryClient) and the SPEC §5 TraceQueryClient mapping.

import {
  AuthenticationError,
  NotFoundError,
  type QueryValue,
  TempoError,
  UnsupportedError,
  ValidationError,
} from "./core/index.js";
import {
  BaseTraceQueryClient,
  normalizeTraceId,
  type SpanAttributes,
  type Trace,
  type TraceSearchQuery,
  type TraceSummary,
} from "./tracing/index.js";
import { TempoClient, type CallOptions, type ClientInternals } from "./client.js";
import { TempoConfig, type TempoConfigOptions } from "./config.js";
import {
  fromKeyValues,
  nanosToMicros,
  spansFromResourceSpans,
  type OtlpKeyValue,
  type OtlpResourceSpans,
} from "./otlp-json.js";
import { TraceQL, checkTraceQL, traceQLText } from "./traceql.js";

/** A time window in Unix time; Tempo receives whole epoch seconds. */
export interface TimeRange {
  /** Window start (sent as epoch seconds, rounded down). */
  start?: Date;
  /** Window end (sent as epoch seconds, rounded up). */
  end?: Date;
}

/** Options of {@link TempoQueryClient.getTrace}. */
export interface GetTraceOptions extends TimeRange, CallOptions {}

/** Options of {@link TempoQueryClient.search}. */
export interface TempoSearchOptions extends TimeRange, CallOptions {
  /** TraceQL query (text or {@link TraceQL} builder). Default `{}` (every trace). */
  traceql?: string | TraceQL;
  /** Maximum number of traces (Tempo default 20). */
  limit?: number;
  /** Maximum spans returned per spanset (Tempo default 3; `matched` still counts all). */
  spss?: number;
}

/** One span of a search spanset. `name` is present only when the query selects it. */
export interface TempoSearchSpan {
  /** 16 lower-case hex characters. */
  spanId: string;
  name?: string;
  startMicros: number;
  durationMicros: number;
  /** Always `service.name` plus the selected attributes. */
  attributes: SpanAttributes;
}

/** Spans of one trace that matched the query. */
export interface TempoSpanSet {
  spans: TempoSearchSpan[];
  /** Number of matching spans (may exceed `spans.length`, see `spss`). */
  matched: number;
}

/** One trace found by {@link TempoQueryClient.search}. */
export interface TempoSearchTrace {
  /** 32 lower-case hex characters (Tempo trims leading zeros; the SDK pads them back). */
  traceId: string;
  rootServiceName?: string;
  rootTraceName?: string;
  startMicros: number;
  /** Trace duration (Tempo reports whole milliseconds). */
  durationMicros: number;
  spanSets: TempoSpanSet[];
  /** Spans and errors per service. */
  serviceStats: Record<string, { spanCount: number; errorCount: number }>;
}

/** Result of {@link TempoQueryClient.search}. */
export interface TempoSearchResult {
  traces: TempoSearchTrace[];
  /** Query statistics (`inspectedBytes`, `completedJobs`, `totalJobs`, …) as numbers. */
  metrics: Record<string, number>;
}

/** Attribute scopes accepted by {@link TempoQueryClient.searchTags}. */
export type TagScope = "resource" | "span" | "intrinsic" | "event" | "link" | "instrumentation";

const TAG_SCOPES: readonly TagScope[] = [
  "resource",
  "span",
  "intrinsic",
  "event",
  "link",
  "instrumentation",
];

/** Options of {@link TempoQueryClient.searchTags}. */
export interface SearchTagsOptions extends TimeRange, CallOptions {
  /** Only this scope; default: every scope that has data. */
  scope?: TagScope;
  /** Only tags present on spans matching this TraceQL query. */
  query?: string | TraceQL;
}

/** Options of {@link TempoQueryClient.searchTagValues}. */
export interface SearchTagValuesOptions extends TimeRange, CallOptions {
  /** Only values on spans matching this TraceQL query. */
  query?: string | TraceQL;
}

/** One tag value. `value` is always text; `type` tells how to read it (`string`, `int`, `keyword`, …). */
export interface TagValue {
  type: string;
  value: string;
}

/** Options of {@link TempoQueryClient.traceQLMetrics}. */
export interface TraceQLMetricsOptions extends TimeRange, CallOptions {
  /** Relative window ending now (e.g. 900000 = last 15 minutes) instead of `start`/`end`. */
  sinceMs?: number;
  /** Step between samples in ms (Tempo picks one when unset). */
  stepMs?: number;
  /** Maximum number of exemplars to return. */
  exemplars?: number;
}

/** One sample of a TraceQL metrics series. Tempo omits zero values; the SDK reports 0. */
export interface MetricSample {
  timestampMs: number;
  value: number;
}

/** An exemplar span behind a metrics series (labels include `trace:id`). */
export interface MetricExemplar {
  labels: SpanAttributes;
  value: number;
  timestampMs: number;
}

/** One series of a TraceQL metrics query. */
export interface MetricSeries {
  /** Series labels (`__name__`, `by()` attributes, quantile `p`). */
  labels: SpanAttributes;
  samples: MetricSample[];
  exemplars: MetricExemplar[];
}

/** Result of {@link TempoQueryClient.traceQLMetrics}. */
export interface TraceQLMetricsResult {
  series: MetricSeries[];
  /** Step Tempo used, in ms, when reported. */
  stepMs?: number;
}

/** Build information from `/api/status/buildinfo` (empty strings for unset fields). */
export interface BuildInfo {
  version: string;
  revision: string;
  branch: string;
  buildUser: string;
  buildDate: string;
  goVersion: string;
}

interface RawSearchSpan {
  spanID?: string;
  name?: string;
  startTimeUnixNano?: string;
  durationNanos?: string | number;
  attributes?: OtlpKeyValue[];
}
interface RawSearchTrace {
  traceID?: string;
  rootServiceName?: string;
  rootTraceName?: string;
  startTimeUnixNano?: string;
  durationMs?: number;
  spanSet?: { spans?: RawSearchSpan[]; matched?: number };
  spanSets?: { spans?: RawSearchSpan[]; matched?: number }[];
  serviceStats?: Record<string, { spanCount?: number; errorCount?: number }>;
}
interface RawMetricSeries {
  labels?: OtlpKeyValue[];
  samples?: { timestampMs?: string | number; value?: number }[];
  exemplars?: { labels?: OtlpKeyValue[]; value?: number; timestampMs?: string | number }[];
}

function padId(id: string | undefined, length: number): string {
  return (id ?? "").toLowerCase().padStart(length, "0");
}

function toNumber(value: unknown): number {
  if (typeof value === "number") return value;
  if (typeof value === "string" && value.trim() !== "") {
    const n = Number(value);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

function epochSeconds(date: Date, name: string, round: (n: number) => number): number {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) {
    throw new ValidationError(`${name} must be a valid Date`);
  }
  return round(date.getTime() / 1000);
}

function rangeQuery(range: TimeRange): Record<string, QueryValue> {
  const q: Record<string, QueryValue> = {};
  if (range.start !== undefined) q.start = epochSeconds(range.start, "start", Math.floor);
  if (range.end !== undefined) q.end = epochSeconds(range.end, "end", Math.ceil);
  return q;
}

function positiveInt(value: number | undefined, name: string): number | undefined {
  if (value === undefined) return undefined;
  if (!Number.isInteger(value) || value <= 0) {
    throw new ValidationError(`${name} must be a positive integer`);
  }
  return value;
}

/** Formats milliseconds as a Go duration (`5s`, `1500ms`). */
export function goDuration(ms: number, name: string): string {
  if (!Number.isFinite(ms) || ms <= 0) throw new ValidationError(`${name} must be positive`);
  const whole = Math.round(ms);
  return whole % 1000 === 0 ? `${whole / 1000}s` : `${whole}ms`;
}

function mapSearchSpan(span: RawSearchSpan): TempoSearchSpan {
  const out: TempoSearchSpan = {
    spanId: padId(span.spanID, 16),
    startMicros: nanosToMicros(span.startTimeUnixNano),
    durationMicros: nanosToMicros(span.durationNanos),
    attributes: fromKeyValues(span.attributes),
  };
  if (span.name !== undefined) out.name = span.name;
  return out;
}

function mapSearchTrace(raw: RawSearchTrace): TempoSearchTrace {
  const sets = raw.spanSets ?? (raw.spanSet === undefined ? [] : [raw.spanSet]);
  const serviceStats: TempoSearchTrace["serviceStats"] = {};
  for (const [svc, stats] of Object.entries(raw.serviceStats ?? {})) {
    serviceStats[svc] = { spanCount: stats.spanCount ?? 0, errorCount: stats.errorCount ?? 0 };
  }
  const out: TempoSearchTrace = {
    traceId: padId(raw.traceID, 32),
    startMicros: nanosToMicros(raw.startTimeUnixNano),
    durationMicros: (raw.durationMs ?? 0) * 1000,
    spanSets: sets.map((s) => ({
      spans: (s.spans ?? []).map(mapSearchSpan),
      matched: s.matched ?? 0,
    })),
    serviceStats,
  };
  if (raw.rootServiceName !== undefined) out.rootServiceName = raw.rootServiceName;
  if (raw.rootTraceName !== undefined) out.rootTraceName = raw.rootTraceName;
  return out;
}

/**
 * Client for Tempo's HTTP query API (port 3200). Implements the SPEC §5 `TraceQueryClient`
 * (`getTrace`, `searchTraces`, `listServices`, `listOperations`, `getDependencies`,
 * `waitForTrace`) plus Tempo's own `search` (TraceQL), `searchTags`, `searchTagValues`,
 * `traceQLMetrics`, `echo`, `ready` and `buildInfo`.
 *
 * Get one from `client.query`, or build one from a config (it then owns its client and
 * {@link close} closes it). The tenant (`X-Scope-OrgID`) comes from the config.
 *
 * Note: Tempo shows a trace by id immediately after ingest, but search, tags and metrics only
 * after about 30 seconds.
 */
export class TempoQueryClient extends BaseTraceQueryClient {
  readonly #client: TempoClient;
  readonly #owned: boolean;

  /** Wraps an existing {@link TempoClient}, or builds an owned one from config options. */
  constructor(source: TempoClient | TempoConfigOptions = {}, internals: ClientInternals = {}) {
    super();
    if (source instanceof TempoClient) {
      this.#client = source;
      this.#owned = false;
    } else {
      this.#client = new TempoClient(source, internals);
      this.#owned = true;
    }
  }

  /** Builds an owned query client from `GROW_GRAFANA_TEMPO_*` / `TEMPO_*` variables. */
  static fromEnv(overrides: TempoConfigOptions = {}): TempoQueryClient {
    return new TempoQueryClient(TempoConfig.fromEnv(overrides));
  }

  /** The underlying client. */
  get client(): TempoClient {
    return this.#client;
  }

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
  async #get<T>(
    path: string,
    operation: string,
    options: {
      query?: Record<string, QueryValue>;
      signal?: AbortSignal | undefined;
      responseType?: "json" | "text";
      allowStatus?: readonly number[];
      unsupported404?: boolean;
    } = {},
  ): Promise<{ status: number; data: T }> {
    try {
      const res = await this.#client.transport.get<T>(path, {
        operation,
        ...(options.query === undefined ? {} : { query: options.query }),
        ...(options.signal === undefined ? {} : { signal: options.signal }),
        ...(options.responseType === undefined ? {} : { responseType: options.responseType }),
        ...(options.allowStatus === undefined ? {} : { allowStatus: options.allowStatus }),
      });
      return { status: res.status, data: res.data };
    } catch (err) {
      throw mapTempoError(err, operation, options.unsupported404 === true);
    }
  }

  /**
   * Fetches a trace by id: `GET /api/v2/traces/{id}` (falls back to v1 `/api/traces/{id}` when
   * the v2 route is missing). Returns `null` when the trace does not exist (or belongs to another
   * tenant). `start`/`end` narrow the backend blocks searched; recent data is returned anyway.
   * Throws `ValidationError` for an invalid id.
   */
  async getTrace(traceId: string, options: GetTraceOptions = {}): Promise<Trace | null> {
    const id = normalizeTraceId(traceId);
    const operation = `getTrace ${id}`;
    const query = rangeQuery(options);
    const v2 = await this.#get<
      { trace?: { resourceSpans?: OtlpResourceSpans[] } | null } | undefined
    >(`/api/v2/traces/${id}`, operation, { query, signal: options.signal, allowStatus: [404] });
    let resourceSpans: OtlpResourceSpans[] | undefined;
    if (v2.status === 404) {
      const v1 = await this.#get<{ batches?: OtlpResourceSpans[] } | undefined>(
        `/api/traces/${id}`,
        operation,
        { query, signal: options.signal, allowStatus: [404] },
      );
      if (v1.status === 404) return null;
      resourceSpans = v1.data?.batches;
    } else {
      resourceSpans = v2.data?.trace?.resourceSpans;
    }
    const spans = spansFromResourceSpans(resourceSpans ?? []);
    return spans.length === 0 ? null : { traceId: id, spans };
  }

  /**
   * Runs a TraceQL search: `GET /api/search?q=…&start&end&limit&spss` (no `q` without a query).
   * Trace ids come back
   * padded to 32 hex characters. Invalid TraceQL → `ValidationError` (HTTP 400).
   */
  async search(options: TempoSearchOptions = {}): Promise<TempoSearchResult> {
    // No TraceQL → no `q` (Tempo then matches every trace), as in every language.
    const q = options.traceql === undefined ? "" : traceQLText(options.traceql).trim();
    const query: Record<string, QueryValue> = {
      ...(q === "" ? {} : { q }),
      ...rangeQuery(options),
      limit: positiveInt(options.limit, "search: limit"),
      spss: positiveInt(options.spss, "search: spss"),
    };
    const res = await this.#get<
      { traces?: RawSearchTrace[]; metrics?: Record<string, unknown> } | undefined
    >("/api/search", "search", { query, signal: options.signal, unsupported404: true });
    const metrics: Record<string, number> = {};
    for (const [k, v] of Object.entries(res.data?.metrics ?? {})) metrics[k] = toNumber(v);
    return { traces: (res.data?.traces ?? []).map(mapSearchTrace), metrics };
  }

  /**
   * SPEC §5 search: builds one TraceQL spanset (`resource.service.name`, `name`, unscoped
   * `.tag` filters, `trace:duration` bounds in µs) and maps hits to `TraceSummary`
   * (duration has millisecond precision).
   */
  async searchTraces(
    query: TraceSearchQuery = {},
    options: CallOptions = {},
  ): Promise<TraceSummary[]> {
    let q = TraceQL.span();
    if (query.service !== undefined) q = q.and("resource.service.name", "=", query.service);
    if (query.operation !== undefined) q = q.and("name", "=", query.operation);
    for (const [k, v] of Object.entries(query.tags ?? {})) q = q.and(`.${k}`, "=", v);
    if (query.minDurationMicros !== undefined)
      q = q.and("trace:duration", ">=", micros(query.minDurationMicros, "minDurationMicros"));
    if (query.maxDurationMicros !== undefined)
      q = q.and("trace:duration", "<=", micros(query.maxDurationMicros, "maxDurationMicros"));
    const searchOptions: TempoSearchOptions = { traceql: q, limit: query.limit ?? 20 };
    if (query.start !== undefined) searchOptions.start = query.start;
    if (query.end !== undefined) searchOptions.end = query.end;
    if (options.signal !== undefined) searchOptions.signal = options.signal;
    const result = await this.search(searchOptions);
    return result.traces.map((t) => {
      const summary: TraceSummary = {
        traceId: t.traceId,
        startMicros: t.startMicros,
        durationMicros: t.durationMicros,
        spanCount: Object.values(t.serviceStats).reduce((n, s) => n + s.spanCount, 0),
      };
      if (t.rootServiceName !== undefined) summary.rootServiceName = t.rootServiceName;
      if (t.rootTraceName !== undefined) summary.rootName = t.rootTraceName;
      return summary;
    });
  }

  /**
   * Tag names per scope: `GET /api/v2/search/tags` → `{ resource: [...], span: [...], … }`.
   * Without `scope` it returns every scope that has data.
   */
  async searchTags(options: SearchTagsOptions = {}): Promise<Record<string, string[]>> {
    if (options.scope !== undefined && !TAG_SCOPES.includes(options.scope)) {
      throw new ValidationError(`searchTags: invalid scope "${options.scope}"`);
    }
    const query: Record<string, QueryValue> = { scope: options.scope, ...rangeQuery(options) };
    if (options.query !== undefined)
      query.q = checkTraceQL(traceQLText(options.query), "searchTags");
    const res = await this.#get<{ scopes?: { name?: string; tags?: string[] }[] } | undefined>(
      "/api/v2/search/tags",
      "searchTags",
      { query, signal: options.signal, unsupported404: true },
    );
    const out: Record<string, string[]> = {};
    for (const scope of res.data?.scopes ?? []) {
      if (scope.name !== undefined) out[scope.name] = [...(scope.tags ?? [])];
    }
    return out;
  }

  /**
   * Values of one tag: `GET /api/v2/search/tag/{tag}/values`. `tag` is a TraceQL identifier
   * (`resource.service.name`, `span.http.response.status_code`, `name`, `status`,
   * `span.grow weird.key`); an unscoped attribute name is a `ValidationError`.
   */
  async searchTagValues(tag: string, options: SearchTagValuesOptions = {}): Promise<TagValue[]> {
    const field = TraceQL.field(tag);
    const query: Record<string, QueryValue> = rangeQuery(options);
    if (options.query !== undefined)
      query.q = checkTraceQL(traceQLText(options.query), "searchTagValues");
    const res = await this.#get<
      { tagValues?: ({ type?: string; value?: string } | string)[] } | undefined
    >(`/api/v2/search/tag/${encodeURIComponent(field)}/values`, `searchTagValues ${field}`, {
      query,
      signal: options.signal,
      unsupported404: true,
    });
    return (res.data?.tagValues ?? []).map((v) =>
      typeof v === "string"
        ? { type: "string", value: v }
        : { type: v.type ?? "string", value: v.value ?? "" },
    );
  }

  /** Service names (`resource.service.name` values) seen recently or in the window, sorted. */
  async listServices(options: TimeRange & CallOptions = {}): Promise<string[]> {
    const values = await this.searchTagValues("resource.service.name", options);
    return [...new Set(values.map((v) => v.value))].sort();
  }

  /** Span names of one service (`name` values filtered by the service), sorted. */
  async listOperations(service: string, options: TimeRange & CallOptions = {}): Promise<string[]> {
    if (service.trim() === "") throw new ValidationError("listOperations: service is required");
    const values = await this.searchTagValues("name", {
      ...options,
      query: TraceQL.span({ "resource.service.name": service }),
    });
    return [...new Set(values.map((v) => v.value))].sort();
  }

  /**
   * TraceQL metrics over a range: `GET /api/metrics/query_range` (e.g.
   * `{ resource.service.name = "api" } | rate()`). Zero samples omitted by Tempo are reported
   * as 0. A query without a metrics pipeline → `ValidationError`.
   */
  async traceQLMetrics(
    query: string | TraceQL,
    options: TraceQLMetricsOptions = {},
  ): Promise<TraceQLMetricsResult> {
    const q = traceQLText(query).trim();
    if (q === "") throw new ValidationError("traceQLMetrics: query is required");
    const params: Record<string, QueryValue> = {
      q,
      ...rangeQuery(options),
      exemplars: positiveInt(options.exemplars, "traceQLMetrics: exemplars"),
    };
    if (options.sinceMs !== undefined)
      params.since = goDuration(options.sinceMs, "traceQLMetrics: sinceMs");
    if (options.stepMs !== undefined)
      params.step = goDuration(options.stepMs, "traceQLMetrics: stepMs");
    const res = await this.#get<{ series?: RawMetricSeries[]; step?: string | number } | undefined>(
      "/api/metrics/query_range",
      "traceQLMetrics",
      { query: params, signal: options.signal, unsupported404: true },
    );
    const result: TraceQLMetricsResult = {
      series: (res.data?.series ?? []).map((s) => ({
        labels: fromKeyValues(s.labels),
        samples: (s.samples ?? []).map((p) => ({
          timestampMs: toNumber(p.timestampMs),
          value: p.value ?? 0,
        })),
        exemplars: (s.exemplars ?? []).map((e) => ({
          labels: fromKeyValues(e.labels),
          value: e.value ?? 0,
          timestampMs: toNumber(e.timestampMs),
        })),
      })),
    };
    if (res.data?.step !== undefined) result.stepMs = Math.round(toNumber(res.data.step) / 1e6);
    return result;
  }

  /** `GET /api/echo` → `"echo"` (no tenant needed). */
  async echo(options: CallOptions = {}): Promise<string> {
    const res = await this.#get<string>("/api/echo", "echo", {
      responseType: "text",
      signal: options.signal,
    });
    return res.data.trim();
  }

  /**
   * `GET /ready`: `true` when Tempo answers `ready`, `false` while it is starting (HTTP 503)
   * or reports another state. Throws on network errors (unlike `health()`).
   */
  async ready(options: CallOptions = {}): Promise<boolean> {
    try {
      const res = await this.#get<string>("/ready", "ready", {
        responseType: "text",
        signal: options.signal,
      });
      return res.data.trim() === "ready";
    } catch (err) {
      if (err instanceof TempoError && err.httpStatus !== undefined) return false;
      throw err;
    }
  }

  /** `GET /api/status/buildinfo` → version, revision, branch, build user/date, Go version. */
  async buildInfo(options: CallOptions = {}): Promise<BuildInfo> {
    const res = await this.#get<Record<string, unknown> | undefined>(
      "/api/status/buildinfo",
      "buildInfo",
      { signal: options.signal, unsupported404: true },
    );
    const data = res.data ?? {};
    const text = (key: string): string => {
      const v = data[key];
      return typeof v === "string" ? v : "";
    };
    return {
      version: text("version"),
      revision: text("revision"),
      branch: text("branch"),
      buildUser: text("buildUser"),
      buildDate: text("buildDate"),
      goVersion: text("goVersion"),
    };
  }

  /** Closes the underlying client when this query client owns it. Idempotent. */
  async close(): Promise<void> {
    if (this.#owned) await this.#client.close();
  }
}

function micros(value: number, name: string): string {
  if (!Number.isFinite(value) || value < 0) {
    throw new ValidationError(`searchTraces: ${name} must be a non-negative number`);
  }
  return `${Math.round(value)}us`;
}

/**
 * Tempo-specific error mapping on top of the SPEC status mapping: a 401 `no org id` names the
 * missing tenant header; a 404 on a feature route means the endpoint is not available
 * (`UnsupportedError`).
 */
export function mapTempoError(err: unknown, operation: string, unsupported404: boolean): unknown {
  if (err instanceof AuthenticationError && /no org id/i.test(err.message)) {
    return new AuthenticationError(`${operation}: tenant required (X-Scope-OrgID) (HTTP 401)`, {
      httpStatus: 401,
      cause: err,
    });
  }
  if (unsupported404 && err instanceof NotFoundError) {
    return new UnsupportedError(`${operation}: endpoint not available on this Tempo (HTTP 404)`, {
      httpStatus: 404,
      cause: err,
    });
  }
  return err;
}
