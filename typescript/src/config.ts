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
  REDACTED,
  Secret,
  describeCommonConfig,
  mergeOptions,
  readCommonEnv,
  resolveCommonConfig,
  secretValues,
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
  /**
   * Bearer token for an authenticating reverse proxy in front of Tempo, sent as
   * `Authorization: Bearer <token>` (env `GROW_GRAFANA_TEMPO_TOKEN`, then `GROW_TEMPO_TOKEN`).
   * Tempo itself has no authentication. Redacted in every string form.
   */
  token?: string | Secret;
}

/** Vendor environment variables for the settings that only Tempo has. */
interface TempoVendorEnv extends CommonVendorEnv {
  tenantId: readonly string[];
  token: readonly string[];
  connectTimeout: readonly string[];
  requestTimeout: readonly string[];
  totalTimeout: readonly string[];
  maxRetries: readonly string[];
}

/**
 * Vendor environment variables read after `GROW_GRAFANA_TEMPO_*`, in order: the ticket's
 * `TEMPO_URL` / `TEMPO_TENANT_ID`, then the short `GROW_TEMPO_<NAME>` alias of every setting.
 */
export const VENDOR_ENV: TempoVendorEnv = {
  url: ["TEMPO_URL", "GROW_TEMPO_URL"],
  tenantId: ["TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"],
  token: ["GROW_TEMPO_TOKEN"],
  connectTimeout: ["GROW_TEMPO_CONNECT_TIMEOUT"],
  requestTimeout: ["GROW_TEMPO_REQUEST_TIMEOUT"],
  totalTimeout: ["GROW_TEMPO_TOTAL_TIMEOUT"],
  maxRetries: ["GROW_TEMPO_MAX_RETRIES"],
  caCert: ["GROW_TEMPO_CA_CERT"],
  clientCert: ["GROW_TEMPO_CLIENT_CERT"],
  clientKey: ["GROW_TEMPO_CLIENT_KEY"],
  insecureSkipVerify: ["GROW_TEMPO_INSECURE_SKIP_VERIFY"],
  proxy: ["GROW_TEMPO_PROXY"],
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
 * and pass its bearer token as `token` (or other credentials through `headers`); both are
 * redacted everywhere.
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
  /** Bearer token for an authenticating proxy (redacted in every string form), or undefined. */
  readonly token?: Secret;

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
    const token = Secret.from(options.token);
    if (token !== undefined) this.token = token;
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
   * `TEMPO_TENANT_ID`) > the `GROW_TEMPO_<NAME>` aliases > defaults.
   */
  static fromEnv(overrides: TempoConfigOptions = {}, env: EnvMap = process.env): TempoConfig {
    const reader = new EnvReader(TempoConfig.ENV_PREFIX, env);
    const fromEnv: TempoConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    // The generated reader has no vendor names for timeouts and retries; add the aliases here.
    const connect = reader.duration("CONNECT_TIMEOUT", VENDOR_ENV.connectTimeout);
    if (connect !== undefined) fromEnv.connectTimeoutMs = connect;
    const request = reader.duration("REQUEST_TIMEOUT", VENDOR_ENV.requestTimeout);
    if (request !== undefined) fromEnv.requestTimeoutMs = request;
    const total = reader.duration("TOTAL_TIMEOUT", VENDOR_ENV.totalTimeout);
    if (total !== undefined) fromEnv.totalTimeoutMs = total;
    const maxRetries = reader.int("MAX_RETRIES", VENDOR_ENV.maxRetries);
    if (maxRetries !== undefined) fromEnv.retry = { maxRetries };
    const tenantId = reader.string("TENANT_ID", VENDOR_ENV.tenantId);
    if (tenantId !== undefined) fromEnv.tenantId = tenantId.trim();
    const token = reader.secret("TOKEN", VENDOR_ENV.token);
    if (token !== undefined) fromEnv.token = token;
    const merged = mergeOptions<TempoConfigOptions>(fromEnv, overrides);
    return new TempoConfig(merged);
  }

  /** Throws {@link ConfigError} when a setting is invalid. */
  validate(): void {
    validateCommonConfig(this);
    validateTenantId(this.tenantId);
    if (this.token?.reveal().trim() === "")
      throw new ConfigError("invalid configuration: token must not be blank");
  }

  /** Credential values to redact from error messages and logs (the proxy token, if any). */
  knownSecrets(): string[] {
    return secretValues(this.token);
  }

  /** `X-Scope-OrgID` when a tenant is set, else `{}`. */
  tenantHeaders(): Record<string, string> {
    return this.tenantId === undefined ? {} : { [TENANT_HEADER]: this.tenantId };
  }

  /** Headers added to every query call: the tenant and, when set, `Authorization: Bearer`. */
  requestHeaders(): Record<string, string> {
    const headers = this.tenantHeaders();
    if (this.token !== undefined) headers.Authorization = `Bearer ${this.token.reveal()}`;
    return headers;
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    return {
      ...describeCommonConfig(this),
      tenantId: this.tenantId ?? null,
      token: this.token === undefined ? null : REDACTED,
    };
  }

  toString(): string {
    return `TempoConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
