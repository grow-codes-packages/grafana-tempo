// Service-owned: generated once by sdk-kit, then maintained in this repository.

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  type RetryPolicy,
  type TlsOptions,
  describeCommonConfig,
  mergeOptions,
  readCommonEnv,
  resolveCommonConfig,
  validateCommonConfig,
} from "./core/index.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** Port text from the stack catalog; the default URL uses its first number. */
const CATALOG_PORT = "3200";

/** Header that selects the tenant on a multi-tenant Tempo (query and OTLP ingest). */
export const TENANT_HEADER = "X-Scope-OrgID";

/**
 * Characters Tempo accepts in a tenant id (Cortex rules). `|` joins several tenants for a
 * federated query (`tenant-b|tenant-a`).
 */
const TENANT_PATTERN = /^[A-Za-z0-9!\-_.*'()|]{1,150}$/;

/** Options accepted by {@link TempoConfig} (all optional). */
export interface TempoConfigOptions extends CommonConfigOptions {
  /**
   * Tenant sent as `X-Scope-OrgID` on every query call (env `GROW_GRAFANA_TEMPO_TENANT_ID`, then
   * `TEMPO_TENANT_ID`). Required when Tempo runs with `multitenancy_enabled: true`; ignored by a
   * single-tenant Tempo. Several tenants joined with `|` run a federated query.
   */
  tenantId?: string;
}

/**
 * Vendor environment variables read after `GROW_GRAFANA_TEMPO_*`, in order.
 */
export const VENDOR_ENV: CommonVendorEnv & { tenantId: readonly string[] } = {
  url: ["TEMPO_URL"],
  tenantId: ["TEMPO_TENANT_ID"],
};

/** Throws {@link ConfigError} when `tenantId` is not a valid Tempo tenant id. */
export function validateTenantId(tenantId: string | undefined, what = "tenantId"): void {
  if (tenantId === undefined) return;
  if (!TENANT_PATTERN.test(tenantId)) {
    throw new ConfigError(
      `invalid configuration: ${what} must be 1-150 characters of A-Z a-z 0-9 ! - _ . * ' ( ) |`,
    );
  }
}

/**
 * Immutable configuration of {@link TempoClient} (query API, port 3200).
 *
 * Tempo has no authentication of its own: put an authenticating reverse proxy in front of it
 * and pass its credentials through `headers` (sensitive headers are redacted everywhere).
 */
export class TempoConfig implements CommonConfig {
  /** Default endpoint of the query API. */
  static readonly DEFAULT_URL = `http://127.0.0.1:${/\d+/.exec(CATALOG_PORT)?.[0] ?? "8080"}`;
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_GRAFANA_TEMPO_";

  readonly url: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** Tenant sent as `X-Scope-OrgID`, or undefined (no header). */
  readonly tenantId?: string;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: TempoConfigOptions = {}) {
    const common = resolveCommonConfig(options, TempoConfig.DEFAULT_URL);
    this.url = common.url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    if (options.tenantId !== undefined) this.tenantId = options.tenantId;
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: TempoConfigOptions = {}): TempoConfig {
    if (options instanceof TempoConfig) return options;
    return new TempoConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence: explicit `overrides` >
   * `GROW_GRAFANA_TEMPO_<NAME>` > vendor variables ({@link VENDOR_ENV}: `TEMPO_URL`,
   * `TEMPO_TENANT_ID`) > defaults.
   */
  static fromEnv(overrides: TempoConfigOptions = {}, env: EnvMap = process.env): TempoConfig {
    const reader = new EnvReader(TempoConfig.ENV_PREFIX, env);
    const fromEnv: TempoConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    const tenantId = reader.string("TENANT_ID", VENDOR_ENV.tenantId);
    if (tenantId !== undefined) fromEnv.tenantId = tenantId;
    const merged = mergeOptions<TempoConfigOptions>(fromEnv, overrides);
    return new TempoConfig(merged);
  }

  /** Throws {@link ConfigError} when a setting is invalid. */
  validate(): void {
    validateCommonConfig(this);
    validateTenantId(this.tenantId);
  }

  /** Credential values to redact from error messages and logs (Tempo has none of its own). */
  knownSecrets(): string[] {
    return [];
  }

  /** Headers added to every query call (`X-Scope-OrgID` when a tenant is set). */
  tenantHeaders(): Record<string, string> {
    return this.tenantId === undefined ? {} : { [TENANT_HEADER]: this.tenantId };
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    return { ...describeCommonConfig(this), tenantId: this.tenantId ?? null };
  }

  toString(): string {
    return `TempoConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
