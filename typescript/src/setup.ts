// Service-owned: OpenTelemetry setup with the Grafana Tempo OTLP exporter preset.

import { readFileSync } from "node:fs";
import { Metadata, credentials, type ChannelCredentials } from "@grpc/grpc-js";
import { OTLPTraceExporter as OtlpGrpcExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPTraceExporter as OtlpProtoExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { CompressionAlgorithm } from "@opentelemetry/otlp-exporter-base";
import type { SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ConfigError, type EnvMap, EnvReader, type TlsOptions } from "./core/index.js";
import { TENANT_HEADER, TempoConfig, VENDOR_ENV, validateTenantId } from "./config.js";
import {
  initTracingWith,
  type InitTracingOptions,
  type ResolvedTracingConfig,
  type SamplerName,
  type SpanAttributes,
  type TracingConfig,
  type TracingHandle,
  type TracingProtocol,
} from "./tracing/index.js";

/** Default OTLP/HTTP endpoint (Tempo's OTLP HTTP receiver). */
export const DEFAULT_OTLP_HTTP_ENDPOINT = "http://127.0.0.1:4318";
/** Default OTLP/gRPC endpoint (Tempo's OTLP gRPC receiver). */
export const DEFAULT_OTLP_GRPC_ENDPOINT = "http://127.0.0.1:4317";

/** {@link TracingConfig} for Tempo: `serviceName` may come from the environment; adds `tenantId`. */
export interface TempoTracingConfig extends Omit<TracingConfig, "serviceName"> {
  /** `service.name`; default `OTEL_SERVICE_NAME`, then `service.name` in `OTEL_RESOURCE_ATTRIBUTES`. */
  serviceName?: string;
  /**
   * Tenant sent as `X-Scope-OrgID` header (HTTP) / `x-scope-orgid` metadata (gRPC) on export.
   * Default `GROW_GRAFANA_TEMPO_TENANT_ID`, then `TEMPO_TENANT_ID`, then `GROW_TEMPO_TENANT_ID`.
   * A multi-tenant Tempo answers export without a tenant with HTTP 503 / gRPC `Unavailable`,
   * which the exporter retries until it drops the spans, so set it whenever Tempo runs with
   * multi-tenancy.
   */
  tenantId?: string;
}

/** Extra options of {@link initTracing}. */
export interface TempoTracingOptions extends Omit<InitTracingOptions, "defaults"> {
  /** Environment used for defaults (tests); default `process.env`. */
  env?: EnvMap;
}

const PROTOCOLS: readonly string[] = ["grpc", "http/protobuf", "native"];
const SAMPLERS: readonly string[] = [
  "always_on",
  "always_off",
  "traceidratio",
  "parentbased_always_on",
  "parentbased_always_off",
  "parentbased_traceidratio",
];

function envValue(env: EnvMap, name: string): { name: string; value: string } | undefined {
  const value = env[name]?.trim();
  return value === undefined || value === "" ? undefined : { name, value };
}

function firstEnv(
  env: EnvMap,
  names: readonly string[],
): { name: string; value: string } | undefined {
  for (const name of names) {
    const hit = envValue(env, name);
    if (hit !== undefined) return hit;
  }
  return undefined;
}

/** Parses `key=value,key2=value2` (values URL-decoded), as in `OTEL_EXPORTER_OTLP_HEADERS`. */
export function parseKeyValueList(text: string, variable: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of text.split(",")) {
    if (part.trim() === "") continue;
    const eq = part.indexOf("=");
    const key = eq <= 0 ? "" : part.slice(0, eq).trim();
    if (key === "") throw new ConfigError(`invalid ${variable}: expected key=value pairs`);
    let value: string;
    try {
      value = decodeURIComponent(part.slice(eq + 1).trim());
    } catch {
      throw new ConfigError(`invalid ${variable}: bad percent-encoding`);
    }
    out[key] = value;
  }
  return out;
}

function appendTracesPath(endpoint: string): string {
  return `${endpoint.replace(/\/+$/, "")}/v1/traces`;
}

/**
 * Resolves the Tempo tracing settings from explicit values and the environment. Precedence per
 * field: explicit > `GROW_GRAFANA_TEMPO_OTLP_*` > `OTEL_EXPORTER_OTLP_TRACES_*` >
 * `OTEL_EXPORTER_OTLP_*` > default (`http/protobuf` to `http://127.0.0.1:4318`, or
 * `http://127.0.0.1:4317` for `grpc`). `OTEL_EXPORTER_OTLP_ENDPOINT` gets `/v1/traces`
 * appended for HTTP; `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` is used verbatim. Compression follows
 * the same order (`…_OTLP_COMPRESSION`, `gzip` or `none`); the sampler comes from
 * `OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG`. The tenant (`GROW_GRAFANA_TEMPO_TENANT_ID` >
 * `TEMPO_TENANT_ID` > `GROW_TEMPO_TENANT_ID`) becomes an `X-Scope-OrgID` header. Throws
 * `ConfigError` naming an invalid variable (never its value).
 */
export function tempoTracingConfig(
  config: TempoTracingConfig = {},
  env: EnvMap = process.env,
): TracingConfig & { tenantId?: string } {
  const reader = new EnvReader(TempoConfig.ENV_PREFIX, env);
  const out: TracingConfig & { tenantId?: string } = { ...config, serviceName: "" };

  let protocol = config.protocol;
  if (protocol === undefined) {
    const growProtocol = reader.lookup("OTLP_PROTOCOL");
    const hit =
      growProtocol ??
      firstEnv(env, ["OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"]);
    if (hit !== undefined) {
      if (!PROTOCOLS.includes(hit.value))
        throw new ConfigError(`invalid ${hit.name}: expected grpc, http/protobuf or native`);
      protocol = hit.value as TracingProtocol;
    }
  }
  out.protocol = protocol ?? "http/protobuf";

  if (config.endpoint === undefined) {
    const grow = reader.string("OTLP_ENDPOINT");
    const traces = envValue(env, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")?.value;
    const base = envValue(env, "OTEL_EXPORTER_OTLP_ENDPOINT")?.value;
    if (grow !== undefined) out.endpoint = grow;
    else if (traces !== undefined) out.endpoint = traces;
    else if (base !== undefined)
      out.endpoint = out.protocol === "grpc" ? base : appendTracesPath(base);
    else
      out.endpoint =
        out.protocol === "grpc" ? DEFAULT_OTLP_GRPC_ENDPOINT : DEFAULT_OTLP_HTTP_ENDPOINT;
  }

  const headerVar = firstEnv(env, [
    "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
    "OTEL_EXPORTER_OTLP_HEADERS",
  ]);
  const headers: Record<string, string> = {
    ...(headerVar === undefined ? {} : parseKeyValueList(headerVar.value, headerVar.name)),
    ...config.headers,
  };

  const resourceVar = envValue(env, "OTEL_RESOURCE_ATTRIBUTES");
  const envResource =
    resourceVar === undefined ? {} : parseKeyValueList(resourceVar.value, resourceVar.name);
  const resourceAttributes: SpanAttributes = { ...envResource, ...config.resourceAttributes };
  delete resourceAttributes["service.name"];
  if (Object.keys(resourceAttributes).length > 0) out.resourceAttributes = resourceAttributes;
  out.serviceName =
    config.serviceName ??
    envValue(env, "OTEL_SERVICE_NAME")?.value ??
    envResource["service.name"] ??
    "";

  if (config.compression === undefined) {
    const hit =
      reader.lookup("OTLP_COMPRESSION") ??
      firstEnv(env, ["OTEL_EXPORTER_OTLP_TRACES_COMPRESSION", "OTEL_EXPORTER_OTLP_COMPRESSION"]);
    if (hit !== undefined) {
      const value = hit.value.trim().toLowerCase();
      if (value !== "gzip" && value !== "none")
        throw new ConfigError(`invalid ${hit.name}: expected gzip or none`);
      out.compression = value;
    }
  }
  if (config.sampler === undefined) {
    const hit = envValue(env, "OTEL_TRACES_SAMPLER");
    if (hit !== undefined) {
      const value = hit.value.trim().toLowerCase();
      if (!SAMPLERS.includes(value))
        throw new ConfigError(`invalid ${hit.name}: unsupported sampler`);
      out.sampler = value as SamplerName;
    }
  }
  if (config.samplerRatio === undefined) {
    const hit = envValue(env, "OTEL_TRACES_SAMPLER_ARG");
    if (hit !== undefined) {
      const ratio = Number(hit.value);
      if (!Number.isFinite(ratio)) throw new ConfigError(`invalid ${hit.name}: not a number`);
      out.samplerRatio = ratio;
    }
  }

  const tenantId = config.tenantId ?? reader.string("TENANT_ID", VENDOR_ENV.tenantId);
  if (tenantId !== undefined) {
    validateTenantId(tenantId);
    out.tenantId = tenantId;
    out.headers = {
      ...Object.fromEntries(
        Object.entries(headers).filter(([k]) => k.toLowerCase() !== TENANT_HEADER.toLowerCase()),
      ),
      [TENANT_HEADER]: tenantId,
    };
  } else {
    delete out.tenantId;
    out.headers = headers;
  }
  return out;
}

/** The OTLP/HTTP traces URL: a bare `scheme://host:port` endpoint gets `/v1/traces` appended. */
export function otlpHttpTracesUrl(endpoint: string): string {
  const url = new URL(endpoint);
  return url.pathname === "" || url.pathname === "/" ? appendTracesPath(endpoint) : endpoint;
}

function readPem(
  path: string | undefined,
  pem: string | undefined,
  what: string,
): Buffer | undefined {
  if (pem !== undefined) return Buffer.from(pem);
  if (path === undefined) return undefined;
  try {
    return readFileSync(path);
  } catch {
    throw new ConfigError(`tracing: cannot read ${what}`);
  }
}

function tlsMaterial(tls: Readonly<TlsOptions>): {
  ca?: Buffer;
  cert?: Buffer;
  key?: Buffer;
  insecure: boolean;
} {
  const ca = readPem(tls.caCertPath, tls.caCertPem, "tls.caCertPath");
  const cert = readPem(tls.clientCertPath, undefined, "tls.clientCertPath");
  const key = readPem(tls.clientKeyPath, undefined, "tls.clientKeyPath");
  return {
    ...(ca === undefined ? {} : { ca }),
    ...(cert === undefined ? {} : { cert }),
    ...(key === undefined ? {} : { key }),
    insecure: tls.insecureSkipVerify === true,
  };
}

type GrpcExporterConfig = NonNullable<ConstructorParameters<typeof OtlpGrpcExporter>[0]>;
type ProtoExporterConfig = NonNullable<ConstructorParameters<typeof OtlpProtoExporter>[0]>;

/**
 * Builds the official OpenTelemetry OTLP exporter for a resolved config: `grpc` → OTLP/gRPC
 * (`x-scope-orgid` metadata); `http/protobuf` and `native` → OTLP/HTTP protobuf to
 * `<endpoint>/v1/traces`. Applies headers, TLS, compression and the export timeout.
 */
export function createTempoExporter(config: ResolvedTracingConfig): SpanExporter {
  const tls = tlsMaterial(config.tls);
  const endpoint =
    config.endpoint ??
    (config.protocol === "grpc" ? DEFAULT_OTLP_GRPC_ENDPOINT : DEFAULT_OTLP_HTTP_ENDPOINT);
  const compression =
    config.compression === "gzip" ? CompressionAlgorithm.GZIP : CompressionAlgorithm.NONE;
  const timeoutMillis = config.batch.exportTimeoutMs;
  if (config.protocol === "grpc") {
    const metadata = new Metadata();
    for (const [k, v] of Object.entries(config.headers)) metadata.set(k.toLowerCase(), v);
    let channel: ChannelCredentials;
    if (new URL(endpoint).protocol === "https:") {
      channel = credentials.createSsl(tls.ca ?? null, tls.key ?? null, tls.cert ?? null, {
        ...(tls.insecure ? { rejectUnauthorized: false } : {}),
      });
    } else {
      channel = credentials.createInsecure();
    }
    const grpcConfig: GrpcExporterConfig = {
      url: endpoint,
      metadata,
      credentials: channel,
      compression,
      timeoutMillis,
    };
    return new OtlpGrpcExporter(grpcConfig);
  }
  const protoConfig: ProtoExporterConfig = {
    url: otlpHttpTracesUrl(endpoint),
    headers: { ...config.headers },
    compression,
    timeoutMillis,
  };
  if (tls.ca !== undefined || tls.cert !== undefined || tls.insecure) {
    protoConfig.httpAgentOptions = {
      ...(tls.ca === undefined ? {} : { ca: tls.ca }),
      ...(tls.cert === undefined ? {} : { cert: tls.cert }),
      ...(tls.key === undefined ? {} : { key: tls.key }),
      ...(tls.insecure ? { rejectUnauthorized: false } : {}),
    };
  }
  return new OtlpProtoExporter(protoConfig);
}

/**
 * Sets up OpenTelemetry tracing that exports to Grafana Tempo over OTLP (SPEC §5): resource,
 * sampler, batch span processor, propagators and (unless `setGlobal: false`) global
 * registration. Settings come from `config`, then the environment (see
 * {@link tempoTracingConfig}). Call `shutdown()` before the process exits.
 *
 * ```ts
 * const tracing = initTracing({ serviceName: "checkout", tenantId: "team-a" });
 * await withSpan("work", async () => doWork());
 * await tracing.shutdown();
 * ```
 */
export function initTracing(
  config: TempoTracingConfig = {},
  options: TempoTracingOptions = {},
): TracingHandle {
  const { env, ...rest } = options;
  const { tenantId: _tenant, ...resolved } = tempoTracingConfig(config, env ?? process.env);
  return initTracingWith(resolved, createTempoExporter, rest);
}
