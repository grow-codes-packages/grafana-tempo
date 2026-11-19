// Service-owned: generated once by sdk-kit, then maintained in this repository.

import {
  type HealthStatus,
  HttpTransport,
  TempoError,
  type TransportOptions,
  UnsupportedError,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { TempoConfig, type TempoConfigOptions } from "./config.js";
import { TempoQueryClient, mapTempoError, type BuildInfo } from "./query.js";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

const VERSION_TEXT = /version\s+v?([0-9][0-9A-Za-z.+-]*)/;

function stripHttpSuffix(message: string, operation: string): string {
  return message
    .replace(new RegExp(`^${operation}: `), "")
    .replace(/ \(HTTP \d+\)$/, "")
    .slice(0, 300);
}

/**
 * Client for Grafana Tempo's HTTP API (port 3200): health, ping, version, and the query API
 * through {@link TempoClient.query}. Spans are sent with {@link initTracing} (OTLP).
 *
 * ```ts
 * const client = TempoClient.fromEnv();
 * const trace = await client.query.getTrace(traceId);
 * await client.close();
 * ```
 */
export class TempoClient {
  /** The immutable configuration. */
  readonly config: TempoConfig;
  readonly #transport: HttpTransport;
  #query: TempoQueryClient | undefined;
  #closed = false;

  /** Accepts a {@link TempoConfig} or plain options. */
  constructor(config: TempoConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = TempoConfig.from(config);
    this.config = cfg;
    this.#transport = new HttpTransport({
      ...internals,
      config: cfg,
      defaultHeaders: () => cfg.requestHeaders(),
      knownSecrets: () => cfg.knownSecrets(),
    });
  }

  /** Builds a client from `GROW_GRAFANA_TEMPO_*` (then `TEMPO_URL`, `TEMPO_TENANT_ID`, `GROW_TEMPO_*`). */
  static fromEnv(overrides: TempoConfigOptions = {}): TempoClient {
    return new TempoClient(TempoConfig.fromEnv(overrides));
  }

  /** The shared HTTP transport (for service modules). @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /** Query API (`getTrace`, `search`, `searchTags`, `traceQLMetrics`, …) sharing this client. */
  get query(): TempoQueryClient {
    this.#query ??= new TempoQueryClient(this);
    return this.#query;
  }

  /**
   * Readiness: `GET /ready` (no tenant needed). `ready` → healthy; HTTP 503 while Tempo starts →
   * `unhealthy` with `details.reason`; network errors → `unreachable`. Never throws for an
   * unhealthy or unreachable service (only `CANCELLED` and `CONFIG`).
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      try {
        const res = await this.#transport.get<string>("/ready", {
          operation: "health",
          responseType: "text",
          ...options,
        });
        const word = res.data.trim();
        if (word === "ready") return healthStatus("ok", { ready: word }, true);
        return healthStatus("unhealthy", { reason: word.slice(0, 300) }, false);
      } catch (err) {
        if (err instanceof TempoError && err.httpStatus !== undefined) {
          return healthStatus(
            "unhealthy",
            { httpStatus: err.httpStatus, reason: stripHttpSuffix(err.message, "health") },
            false,
          );
        }
        throw err;
      }
    });
  }

  /**
   * Tenant-aware round trip: `GET /api/v2/search/tags?scope=intrinsic`. Returns the latency in
   * milliseconds. A multi-tenant Tempo without a tenant → `AuthenticationError`.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    try {
      await this.#transport.get("/api/v2/search/tags", {
        operation: "ping",
        query: { scope: "intrinsic" },
        responseType: "none",
        ...options,
      });
    } catch (err) {
      throw mapTempoError(err, "ping", false);
    }
    return performance.now() - start;
  }

  /** Build information: `GET /api/status/buildinfo`. */
  buildInfo(options: CallOptions = {}): Promise<BuildInfo> {
    return this.query.buildInfo(options);
  }

  /** `GET /api/echo` → `"echo"`. */
  echo(options: CallOptions = {}): Promise<string> {
    return this.query.echo(options);
  }

  /** `GET /ready` → `true` when ready, `false` while starting; throws on network errors. */
  ready(options: CallOptions = {}): Promise<boolean> {
    return this.query.ready(options);
  }

  /**
   * Server version (`"3.1.1"`) from `/api/status/buildinfo`, falling back to the text of
   * `/status/version`. `UnsupportedError` when neither reports one.
   */
  async serverVersion(options: CallOptions = {}): Promise<string> {
    let version = "";
    try {
      version = (await this.buildInfo(options)).version;
    } catch (err) {
      if (!(err instanceof UnsupportedError)) throw err;
    }
    if (version === "") {
      const res = await this.#transport.get<string | undefined>("/status/version", {
        operation: "serverVersion",
        responseType: "text",
        allowStatus: [404],
        ...options,
      });
      const match = res.status === 404 ? null : VERSION_TEXT.exec(res.data ?? "");
      if (match?.[1] === undefined) {
        throw new UnsupportedError("serverVersion: the service does not report a version");
      }
      version = match[1];
    }
    return version.replace(/^v/, "");
  }

  /** Releases connections. Idempotent. */
  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    await this.#transport.close();
  }
}
