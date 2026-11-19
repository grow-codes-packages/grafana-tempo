import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  DEFAULT_OTLP_GRPC_ENDPOINT,
  DEFAULT_OTLP_HTTP_ENDPOINT,
  createTempoExporter,
  initTracing,
  otlpHttpTracesUrl,
  parseKeyValueList,
  resolveTracingConfig,
  tempoTracingConfig,
  withSpan,
} from "../src/index.js";
import { type MockServer, startServer } from "./helpers/server.js";

const servers: MockServer[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

describe("tempoTracingConfig", () => {
  it("defaults to OTLP/HTTP protobuf on 4318", () => {
    const c = tempoTracingConfig({ serviceName: "svc" }, {});
    expect(c).toEqual({
      serviceName: "svc",
      protocol: "http/protobuf",
      endpoint: DEFAULT_OTLP_HTTP_ENDPOINT,
      headers: {},
    });
    expect(tempoTracingConfig({ serviceName: "svc", protocol: "grpc" }, {}).endpoint).toBe(
      DEFAULT_OTLP_GRPC_ENDPOINT,
    );
  });

  it("follows explicit > GROW_* > OTEL_*_TRACES_* > OTEL_* > default", () => {
    const env = {
      GROW_GRAFANA_TEMPO_OTLP_ENDPOINT: "http://grow:4318",
      GROW_GRAFANA_TEMPO_OTLP_PROTOCOL: "grpc",
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://traces:4318/custom",
      OTEL_EXPORTER_OTLP_TRACES_PROTOCOL: "http/protobuf",
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:4318/",
      OTEL_EXPORTER_OTLP_PROTOCOL: "native",
      OTEL_EXPORTER_OTLP_TRACES_HEADERS: "authorization=Bearer%20abc,x-a=1",
      OTEL_EXPORTER_OTLP_HEADERS: "x-ignored=1",
      OTEL_SERVICE_NAME: "env-svc",
      OTEL_RESOURCE_ATTRIBUTES: "service.name=res-svc,team=core",
      GROW_GRAFANA_TEMPO_TENANT_ID: "grow-tenant",
      TEMPO_TENANT_ID: "vendor-tenant",
    };
    const c = tempoTracingConfig({}, env);
    expect(c).toMatchObject({
      serviceName: "env-svc",
      protocol: "grpc",
      endpoint: "http://grow:4318",
      tenantId: "grow-tenant",
      resourceAttributes: { team: "core" },
      headers: { authorization: "Bearer abc", "x-a": "1", "X-Scope-OrgID": "grow-tenant" },
    });
    const explicit = tempoTracingConfig(
      {
        serviceName: "x",
        protocol: "http/protobuf",
        endpoint: "http://explicit:1",
        tenantId: "t-explicit",
        headers: { "x-scope-orgid": "overridden", "x-a": "2" },
        resourceAttributes: { team: "web" },
      },
      env,
    );
    expect(explicit).toMatchObject({
      serviceName: "x",
      protocol: "http/protobuf",
      endpoint: "http://explicit:1",
      tenantId: "t-explicit",
      resourceAttributes: { team: "web" },
      headers: { authorization: "Bearer abc", "x-a": "2", "X-Scope-OrgID": "t-explicit" },
    });
    expect(explicit.headers?.["x-scope-orgid"]).toBeUndefined();

    const traces = tempoTracingConfig(
      {},
      {
        OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://traces:4318/custom",
        OTEL_EXPORTER_OTLP_TRACES_PROTOCOL: "http/protobuf",
        OTEL_EXPORTER_OTLP_PROTOCOL: "grpc",
        OTEL_EXPORTER_OTLP_HEADERS: "x-b=2",
        OTEL_RESOURCE_ATTRIBUTES: "service.name=res-svc",
        TEMPO_TENANT_ID: "vendor-tenant",
      },
    );
    expect(traces).toMatchObject({
      serviceName: "res-svc",
      protocol: "http/protobuf",
      endpoint: "http://traces:4318/custom",
      tenantId: "vendor-tenant",
      headers: { "x-b": "2", "X-Scope-OrgID": "vendor-tenant" },
    });
    expect(traces.resourceAttributes).toBeUndefined();

    const base = tempoTracingConfig(
      { serviceName: "s" },
      { OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:4318/", OTEL_EXPORTER_OTLP_PROTOCOL: "native" },
    );
    expect(base).toMatchObject({ protocol: "native", endpoint: "http://base:4318/v1/traces" });
    const grpcBase = tempoTracingConfig(
      { serviceName: "s", protocol: "grpc" },
      { OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:4317" },
    );
    expect(grpcBase.endpoint).toBe("http://base:4317");
    expect(tempoTracingConfig({}, { OTEL_SERVICE_NAME: " " }).serviceName).toBe("");
  });

  it("raises ConfigError naming the variable, never its value", () => {
    const bad = (env: Record<string, string>, name: string): void => {
      let err: unknown;
      try {
        tempoTracingConfig({ serviceName: "s" }, env);
      } catch (e) {
        err = e;
      }
      expect(err).toBeInstanceOf(ConfigError);
      expect((err as Error).message).toContain(name);
      for (const v of Object.values(env)) expect((err as Error).message).not.toContain(v);
    };
    bad({ OTEL_EXPORTER_OTLP_PROTOCOL: "http/json-secret" }, "OTEL_EXPORTER_OTLP_PROTOCOL");
    bad({ GROW_GRAFANA_TEMPO_OTLP_PROTOCOL: "zzz-secret" }, "GROW_GRAFANA_TEMPO_OTLP_PROTOCOL");
    bad({ OTEL_EXPORTER_OTLP_HEADERS: "novalue-secret" }, "OTEL_EXPORTER_OTLP_HEADERS");
    bad(
      { OTEL_EXPORTER_OTLP_TRACES_HEADERS: "k=%E0%A4%A-secret" },
      "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
    );
    bad({ OTEL_EXPORTER_OTLP_COMPRESSION: "brotli-secret" }, "OTEL_EXPORTER_OTLP_COMPRESSION");
    bad({ OTEL_TRACES_SAMPLER: "jaeger_remote-secret" }, "OTEL_TRACES_SAMPLER");
    bad({ OTEL_TRACES_SAMPLER_ARG: "half-secret" }, "OTEL_TRACES_SAMPLER_ARG");
    expect(() => tempoTracingConfig({ tenantId: "bad tenant" }, {})).toThrow(ConfigError);
  });

  it("reads compression, sampler and the GROW_TEMPO_TENANT_ID alias from the environment", () => {
    const c = tempoTracingConfig(
      {},
      {
        GROW_GRAFANA_TEMPO_OTLP_COMPRESSION: "NONE",
        OTEL_EXPORTER_OTLP_COMPRESSION: "gzip",
        OTEL_TRACES_SAMPLER: "traceidratio",
        OTEL_TRACES_SAMPLER_ARG: "0.25",
        GROW_TEMPO_TENANT_ID: "alias-tenant",
      },
    );
    expect(c.compression).toBe("none");
    expect(c.sampler).toBe("traceidratio");
    expect(c.samplerRatio).toBe(0.25);
    expect(c.tenantId).toBe("alias-tenant");
    expect(c.headers?.["X-Scope-OrgID"]).toBe("alias-tenant");
    const traces = tempoTracingConfig({}, { OTEL_EXPORTER_OTLP_TRACES_COMPRESSION: "gzip" });
    expect(traces.compression).toBe("gzip");
    const explicit = tempoTracingConfig(
      { compression: "gzip", sampler: "always_off", samplerRatio: 1 },
      {
        OTEL_EXPORTER_OTLP_COMPRESSION: "none",
        OTEL_TRACES_SAMPLER: "always_on",
        OTEL_TRACES_SAMPLER_ARG: "0.5",
        TEMPO_TENANT_ID: "vendor",
        GROW_TEMPO_TENANT_ID: "alias",
      },
    );
    expect(explicit.compression).toBe("gzip");
    expect(explicit.sampler).toBe("always_off");
    expect(explicit.samplerRatio).toBe(1);
    expect(explicit.tenantId).toBe("vendor");
  });

  it("parses key=value lists", () => {
    expect(parseKeyValueList("a=1, b = x%3Dy ,,", "V")).toEqual({ a: "1", b: "x=y" });
    expect(() => parseKeyValueList("=1", "V")).toThrow(/invalid V/);
  });
});

describe("exporters", () => {
  it("otlpHttpTracesUrl appends /v1/traces to a bare endpoint only", () => {
    expect(otlpHttpTracesUrl("http://h:4318")).toBe("http://h:4318/v1/traces");
    expect(otlpHttpTracesUrl("http://h:4318/")).toBe("http://h:4318/v1/traces");
    expect(otlpHttpTracesUrl("http://h:4318/otlp/v1/traces")).toBe("http://h:4318/otlp/v1/traces");
  });

  it("builds gRPC and HTTP exporters with TLS options", async () => {
    const dir = mkdtempSync(join(tmpdir(), "tempo-ts-"));
    const ca = join(dir, "ca.pem");
    writeFileSync(ca, "not a real certificate");
    const configs = [
      resolveTracingConfig({
        serviceName: "s",
        protocol: "grpc",
        headers: { "X-Scope-OrgID": "t" },
      }),
      resolveTracingConfig({
        serviceName: "s",
        protocol: "grpc",
        endpoint: "https://h:4317",
        tls: { insecureSkipVerify: true },
      }),
      resolveTracingConfig({ serviceName: "s", protocol: "grpc", endpoint: "https://h:4317" }),
      resolveTracingConfig({ serviceName: "s", tls: { caCertPath: ca } }),
      resolveTracingConfig({
        serviceName: "s",
        endpoint: "https://h:4318",
        tls: { caCertPem: "pem", clientCertPath: ca, clientKeyPath: ca, insecureSkipVerify: true },
      }),
      resolveTracingConfig({ serviceName: "s", tls: { insecureSkipVerify: true } }),
      resolveTracingConfig({ serviceName: "s", protocol: "native", compression: "none" }),
    ];
    for (const config of configs) {
      const exporter = createTempoExporter(config);
      expect(typeof exporter.export).toBe("function");
      await exporter.shutdown();
    }
    expect(() =>
      createTempoExporter(
        resolveTracingConfig({ serviceName: "s", tls: { caCertPath: join(dir, "missing.pem") } }),
      ),
    ).toThrow(/cannot read tls.caCertPath/);
  });

  it("initTracing exports OTLP/HTTP protobuf with the tenant header", async () => {
    const s = await startServer([{ status: 200, body: {} }]);
    servers.push(s);
    const tracing = initTracing(
      {
        serviceName: "unit-svc",
        tenantId: "tenant-a",
        headers: { "x-extra": "1" },
        setGlobal: false,
        compression: "none",
      },
      { env: { GROW_GRAFANA_TEMPO_OTLP_ENDPOINT: s.url } },
    );
    expect(tracing.config.headers).toMatchObject({ "X-Scope-OrgID": "tenant-a" });
    withSpan("unit-span", () => 1, { tracer: tracing.tracer("unit") });
    await tracing.forceFlush(10_000);
    await tracing.shutdown();
    await tracing.shutdown();
    expect(s.requests.length).toBeGreaterThan(0);
    const req = s.requests[0]!;
    expect(req.method).toBe("POST");
    expect(req.url).toBe("/v1/traces");
    expect(req.headers["content-type"]).toBe("application/x-protobuf");
    expect(req.headers["x-scope-orgid"]).toBe("tenant-a");
    expect(req.headers["x-extra"]).toBe("1");
    expect(req.body).toContain("unit-span");
    expect(req.body).toContain("unit-svc");
  });

  it("initTracing reads process.env by default and needs a service name", () => {
    expect(() => initTracing({ setGlobal: false }, { env: {} })).toThrow(/serviceName/);
    const tracing = initTracing({ serviceName: "p", setGlobal: false });
    expect(tracing.config.serviceName).toBe("p");
    return tracing.shutdown();
  });
});
