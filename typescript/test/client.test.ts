import { inspect } from "node:util";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  PACKAGE_KEY,
  REDACTED,
  RetryPolicy,
  TENANT_HEADER,
  TempoClient,
  TempoConfig,
  TempoError,
  TempoQueryClient,
  VENDOR_ENV,
  validateTenantId,
} from "../src/index.js";
import { type MockServer, closedPort, startServer } from "./helpers/server.js";
import { TEMPO_ROUTES, fx, parse, tempoServer } from "./support/tempo.js";

const servers: MockServer[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

async function serve(...args: Parameters<typeof tempoServer>): Promise<MockServer> {
  const s = await tempoServer(...args);
  servers.push(s);
  return s;
}

const fast = { retry: { maxRetries: 1, initialBackoffMs: 1 } };

describe("TempoConfig", () => {
  it("uses defaults and freezes", () => {
    const c = new TempoConfig();
    expect(c.url).toBe("http://127.0.0.1:3200");
    expect(c.url).toBe(TempoConfig.DEFAULT_URL);
    expect(c.requestTimeoutMs).toBe(30_000);
    expect(c.retry).toBeInstanceOf(RetryPolicy);
    expect(c.tenantId).toBeUndefined();
    expect(c.tenantHeaders()).toEqual({});
    expect(c.knownSecrets()).toEqual([]);
    expect(Object.isFrozen(c)).toBe(true);
    expect(TempoConfig.from(c)).toBe(c);
    expect(TempoConfig.from().url).toBe(c.url);
    const prefix = `GROW_${PACKAGE_KEY.toUpperCase().replaceAll("-", "_")}_`;
    expect(TempoConfig.ENV_PREFIX).toBe(prefix);
    expect(VENDOR_ENV.url).toEqual(["TEMPO_URL", "GROW_TEMPO_URL"]);
    expect(VENDOR_ENV.tenantId).toEqual(["TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"]);
    expect(VENDOR_ENV.token).toEqual(["GROW_TEMPO_TOKEN"]);
    expect(c.token).toBeUndefined();
    expect(c.requestHeaders()).toEqual({});
  });

  it("keeps optional settings and the tenant", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const c = new TempoConfig({
      proxy: "http://p:1",
      userAgentSuffix: "s",
      hooks,
      logger,
      tenantId: "tenant-b|tenant-a",
    });
    expect(c.proxy).toBe("http://p:1");
    expect(c.userAgentSuffix).toBe("s");
    expect(c.hooks).toBe(hooks);
    expect(c.logger).toBe(logger);
    expect(c.tenantHeaders()).toEqual({ [TENANT_HEADER]: "tenant-b|tenant-a" });
    expect(c.toJSON().tenantId).toBe("tenant-b|tenant-a");
  });

  it("validate() raises ConfigError", () => {
    expect(() => new TempoConfig({ url: "ftp://x" })).toThrow(ConfigError);
    expect(() => new TempoConfig({ tenantId: "bad/tenant" })).toThrow(ConfigError);
    expect(() => new TempoConfig({ tenantId: "" })).toThrow(ConfigError);
    expect(() => validateTenantId("a b", "X")).toThrow(/X must be/);
    expect(() => validateTenantId(undefined)).not.toThrow();
  });

  it("core.redaction.config: toString/JSON/inspect hide credential headers", () => {
    const c = new TempoConfig({
      headers: { authorization: "Bearer proxy-secret", "x-api-key": "hdr-secret" },
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c)]) {
      expect(form).not.toContain("proxy-secret");
      expect(form).not.toContain("hdr-secret");
    }
    expect(String(c)).toContain(REDACTED);
    expect(String(c)).toMatch(/^TempoConfig\(/);
    expect(new TempoConfig().toJSON().tenantId).toBeNull();
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const prefix = TempoConfig.ENV_PREFIX;
    const env = {
      [`${prefix}URL`]: "http://grow:1",
      TEMPO_URL: "http://vendor:2",
      [`${prefix}TENANT_ID`]: "grow-tenant",
      TEMPO_TENANT_ID: "vendor-tenant",
      [`${prefix}REQUEST_TIMEOUT`]: "5s",
      [`${prefix}MAX_RETRIES`]: "7",
    };
    const fromEnv = TempoConfig.fromEnv({}, env);
    expect(fromEnv.url).toBe("http://grow:1");
    expect(fromEnv.tenantId).toBe("grow-tenant");
    expect(fromEnv.requestTimeoutMs).toBe(5000);
    expect(fromEnv.retry.maxRetries).toBe(7);
    expect(fromEnv.connectTimeoutMs).toBe(10_000);

    const vendorOnly = TempoConfig.fromEnv(
      {},
      { TEMPO_URL: "http://vendor:2", TEMPO_TENANT_ID: "vendor-tenant" },
    );
    expect(vendorOnly.url).toBe("http://vendor:2");
    expect(vendorOnly.tenantId).toBe("vendor-tenant");

    const explicit = TempoConfig.fromEnv(
      { url: "http://explicit:3", tenantId: "explicit", retry: { initialBackoffMs: 1 } },
      env,
    );
    expect(explicit.url).toBe("http://explicit:3");
    expect(explicit.tenantId).toBe("explicit");
    expect(explicit.retry.maxRetries).toBe(7);
    expect(explicit.retry.initialBackoffMs).toBe(1);

    expect(TempoConfig.fromEnv({}, {}).url).toBe(TempoConfig.DEFAULT_URL);
    expect(TempoConfig.fromEnv({}, { TEMPO_URL: "" }).url).toBe(TempoConfig.DEFAULT_URL);
    expect(TempoConfig.fromEnv().url).toBeTypeOf("string");

    let err: unknown;
    try {
      TempoConfig.fromEnv({}, { [`${prefix}TOTAL_TIMEOUT`]: "forever-and-secret" });
    } catch (e) {
      err = e;
    }
    expect(err).toBeInstanceOf(ConfigError);
    expect((err as Error).message).toContain(`${prefix}TOTAL_TIMEOUT`);
    expect((err as Error).message).not.toContain("forever-and-secret");
  });
});

describe("TempoConfig proxy token and GROW_TEMPO_* aliases", () => {
  it("keeps the token redacted and sends it as Authorization: Bearer", () => {
    const c = new TempoConfig({ token: "proxy-token-123", tenantId: "t" });
    expect(c.token?.reveal()).toBe("proxy-token-123");
    expect(c.knownSecrets()).toEqual(["proxy-token-123"]);
    expect(c.requestHeaders()).toEqual({
      [TENANT_HEADER]: "t",
      Authorization: "Bearer proxy-token-123",
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c)]) {
      expect(form).not.toContain("proxy-token-123");
    }
    expect(c.toJSON().token).toBe(REDACTED);
    expect(new TempoConfig().toJSON().token).toBeNull();
    expect(() => new TempoConfig({ token: "  " })).toThrow(ConfigError);
  });

  it("env order: GROW_GRAFANA_TEMPO_* > TEMPO_* > GROW_TEMPO_*", () => {
    const aliases = {
      GROW_TEMPO_URL: "http://alias:1",
      GROW_TEMPO_TENANT_ID: " alias-tenant ",
      GROW_TEMPO_TOKEN: "alias-token",
      GROW_TEMPO_CONNECT_TIMEOUT: "2s",
      GROW_TEMPO_REQUEST_TIMEOUT: "3s",
      GROW_TEMPO_TOTAL_TIMEOUT: "9s",
      GROW_TEMPO_MAX_RETRIES: "4",
      GROW_TEMPO_PROXY: "http://proxy:3128",
      GROW_TEMPO_INSECURE_SKIP_VERIFY: "true",
    };
    const a = TempoConfig.fromEnv({}, aliases);
    expect(a.url).toBe("http://alias:1");
    expect(a.tenantId).toBe("alias-tenant");
    expect(a.token?.reveal()).toBe("alias-token");
    expect(a.connectTimeoutMs).toBe(2000);
    expect(a.requestTimeoutMs).toBe(3000);
    expect(a.totalTimeoutMs).toBe(9000);
    expect(a.retry.maxRetries).toBe(4);
    expect(a.proxy).toBe("http://proxy:3128");
    expect(a.tls.insecureSkipVerify).toBe(true);

    const vendor = TempoConfig.fromEnv(
      {},
      { ...aliases, TEMPO_URL: "http://vendor:2", TEMPO_TENANT_ID: "vendor-tenant" },
    );
    expect(vendor.url).toBe("http://vendor:2");
    expect(vendor.tenantId).toBe("vendor-tenant");

    const grow = TempoConfig.fromEnv(
      {},
      {
        ...aliases,
        TEMPO_URL: "http://vendor:2",
        GROW_GRAFANA_TEMPO_URL: "http://grow:3",
        GROW_GRAFANA_TEMPO_TOKEN: "grow-token",
        GROW_GRAFANA_TEMPO_REQUEST_TIMEOUT: "5s",
      },
    );
    expect(grow.url).toBe("http://grow:3");
    expect(grow.token?.reveal()).toBe("grow-token");
    expect(grow.requestTimeoutMs).toBe(5000);
  });

  it("the client sends the bearer token on every call", async () => {
    const s = await serve(TEMPO_ROUTES);
    const client = new TempoClient({ url: s.url, token: "proxy-token-xyz", ...fast });
    await client.ping();
    expect(s.requests[0]!.headers.authorization).toBe("Bearer proxy-token-xyz");
    expect(String(client.config)).not.toContain("proxy-token-xyz");
    await client.close();
  });
});

describe("TempoClient", () => {
  it("health, ping, serverVersion, buildInfo, echo and ready against recorded Tempo", async () => {
    const s = await serve(TEMPO_ROUTES);
    const client = new TempoClient({ url: s.url, ...fast });
    expect(await client.health()).toEqual({
      healthy: true,
      status: "ok",
      details: { ready: "ready" },
    });
    expect(await client.ping()).toBeGreaterThanOrEqual(0);
    const pingReq = s.requests.find((r) => r.url.startsWith("/api/v2/search/tags"))!;
    expect(parse(pingReq).query.get("scope")).toBe("intrinsic");
    expect(pingReq.headers["x-scope-orgid"]).toBeUndefined();
    expect(await client.serverVersion()).toBe("3.1.1");
    expect(await client.buildInfo()).toEqual({
      version: "3.1.1",
      revision: "9db7df79d",
      branch: "HEAD",
      buildUser: "",
      buildDate: "",
      goVersion: "go1.27.3",
    });
    expect(await client.echo()).toBe("echo");
    expect(await client.ready()).toBe(true);
    expect(client.query).toBe(client.query);
    expect(client.query).toBeInstanceOf(TempoQueryClient);
    expect(client.query.client).toBe(client);
    expect(client.transport.userAgent).toContain(`grow-codes-${PACKAGE_KEY}/`);
    await client.close();
  });

  it("sends X-Scope-OrgID when a tenant is configured", async () => {
    const s = await serve(TEMPO_ROUTES);
    const client = new TempoClient({ url: s.url, tenantId: "tenant-a", ...fast });
    await client.ping();
    expect(s.requests[0]!.headers["x-scope-orgid"]).toBe("tenant-a");
    await client.close();
  });

  it("ping without tenant on a multi-tenant Tempo raises AuthenticationError", async () => {
    const s = await serve({ "/api/v2/search/tags": "searchTags.mt-no-tenant-401" });
    const client = new TempoClient({ url: s.url, ...fast });
    const err = await client.ping().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(TempoError);
    expect(err).toMatchObject({ code: "AUTHENTICATION", httpStatus: 401 });
    expect((err as Error).message).toBe("ping: tenant required (X-Scope-OrgID) (HTTP 401)");
    await client.close();
  });

  it("ping maps other errors by status (403 from a reverse proxy)", async () => {
    const s = await serve({ "/api/v2/search/tags": { status: 403, body: "denied" } });
    const client = new TempoClient({ url: s.url, ...fast });
    await expect(client.ping()).rejects.toMatchObject({ code: "PERMISSION_DENIED" });
    await client.close();
  });

  it("serverVersion falls back to /status/version text", async () => {
    const s = await serve({ "/status/version": "serverVersion.status-version-text" });
    const client = new TempoClient({ url: s.url, ...fast });
    expect(await client.serverVersion()).toBe("3.1.1");
    const s2 = await serve({
      "/api/status/buildinfo": { status: 200, body: { version: "" } },
      "/status/version": { status: 200, body: "tempo, version v2.9.0 (branch: x)" },
    });
    const client2 = new TempoClient({ url: s2.url, ...fast });
    expect(await client2.serverVersion()).toBe("2.9.0");
    await client.close();
    await client2.close();
  });

  it("serverVersion raises UnsupportedError when absent", async () => {
    const s = await serve({});
    const client = new TempoClient({ url: s.url, ...fast });
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    const s2 = await serve({ "/status/version": { status: 200, body: "no numbers here" } });
    const client2 = new TempoClient({ url: s2.url, ...fast });
    await expect(client2.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    const s3 = await serve({ "/api/status/buildinfo": { status: 400, body: "bad" } });
    const client3 = new TempoClient({ url: s3.url, ...fast });
    await expect(client3.serverVersion()).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
    await client2.close();
    await client3.close();
  });

  it("health reports starting and odd answers as unhealthy", async () => {
    const starting = await serve({
      "/ready": {
        status: 503,
        headers: { "content-type": "text/plain" },
        body: "Ingester not ready: waiting for 15s after being ready",
      },
    });
    const c1 = new TempoClient({ url: starting.url, retry: { maxRetries: 0 } });
    expect(await c1.health()).toEqual({
      healthy: false,
      status: "unhealthy",
      details: {
        httpStatus: "503",
        reason: "Ingester not ready: waiting for 15s after being ready",
      },
    });
    expect(await c1.ready()).toBe(false);
    await c1.close();
    const odd = await serve({ "/ready": { status: 200, body: "not yet\n" } });
    const c2 = new TempoClient({ url: odd.url, ...fast });
    expect(await c2.health()).toEqual({
      healthy: false,
      status: "unhealthy",
      details: { reason: "not yet" },
    });
    expect(await c2.ready()).toBe(false);
    await c2.close();
  });

  it("core.health.unreachable: closed port -> healthy=false, status=unreachable", async () => {
    const port = await closedPort();
    const client = new TempoClient({ url: `http://127.0.0.1:${port}`, ...fast });
    expect(await client.health()).toEqual({
      healthy: false,
      status: "unreachable",
      details: { error: "UNAVAILABLE" },
    });
    await expect(client.ready()).rejects.toMatchObject({ code: "UNAVAILABLE" });
    await client.close();
  });

  it("health rethrows cancellation", async () => {
    const s = await serve(TEMPO_ROUTES);
    const client = new TempoClient({ url: s.url });
    await expect(client.health({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await client.close();
  });

  it("core.close.idempotent: close twice is safe", async () => {
    const s = await serve(TEMPO_ROUTES);
    const client = new TempoClient({ url: s.url });
    await client.health();
    await client.close();
    await client.close();
    await expect(client.ping()).rejects.toMatchObject({ code: "CANCELLED" });
  });

  it("fromEnv builds from GROW_* variables", async () => {
    const s = await serve(TEMPO_ROUTES);
    const key = `${TempoConfig.ENV_PREFIX}URL`;
    process.env[key] = s.url;
    try {
      const client = TempoClient.fromEnv({ tenantId: "t1" });
      expect(client.config.url).toBe(s.url);
      expect(await client.ping()).toBeGreaterThanOrEqual(0);
      expect(s.requests[0]!.headers["x-scope-orgid"]).toBe("t1");
      await client.close();
      const q = TempoQueryClient.fromEnv();
      expect(await q.echo()).toBe("echo");
      await q.close();
      await q.close();
    } finally {
      delete process.env[key];
    }
  });

  it("works with the raw fixture helper", async () => {
    const s = await startServer([fx("echo.ok")]);
    servers.push(s);
    const client = new TempoClient({ url: s.url });
    expect(await client.echo()).toBe("echo");
    await client.close();
  });
});
