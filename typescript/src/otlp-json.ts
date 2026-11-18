// Service-owned: conversion of Tempo's OTLP/JSON trace responses into the SPEC §5 model.

import {
  sortSpans,
  type Span,
  type SpanAttrValue,
  type SpanAttributes,
  type SpanKindName,
  type SpanStatusName,
} from "./tracing/index.js";

/** OTLP `AnyValue` as Tempo serialises it (protobuf JSON). */
export interface OtlpAnyValue {
  stringValue?: string;
  intValue?: string | number;
  boolValue?: boolean;
  doubleValue?: number | string;
  arrayValue?: { values?: OtlpAnyValue[] };
  kvlistValue?: { values?: OtlpKeyValue[] };
  bytesValue?: string;
}

/** OTLP `KeyValue`. */
export interface OtlpKeyValue {
  key?: string;
  value?: OtlpAnyValue | null;
}

/** OTLP span as Tempo serialises it (ids base64, times as decimal nanosecond strings). */
export interface OtlpSpan {
  traceId?: string;
  spanId?: string;
  parentSpanId?: string;
  name?: string;
  kind?: string | number;
  startTimeUnixNano?: string | number;
  endTimeUnixNano?: string | number;
  attributes?: OtlpKeyValue[];
  events?: { timeUnixNano?: string | number; name?: string; attributes?: OtlpKeyValue[] }[];
  links?: { traceId?: string; spanId?: string; attributes?: OtlpKeyValue[] }[];
  status?: { code?: string | number; message?: string };
}

/** OTLP `ResourceSpans` (v2 `trace.resourceSpans`, v1 `batches`). */
export interface OtlpResourceSpans {
  resource?: { attributes?: OtlpKeyValue[] };
  scopeSpans?: { spans?: OtlpSpan[] }[];
  /** Pre-1.0 OTLP name used by old Tempo versions. */
  instrumentationLibrarySpans?: { spans?: OtlpSpan[] }[];
}

const KINDS: readonly SpanKindName[] = [
  "unspecified",
  "internal",
  "server",
  "client",
  "producer",
  "consumer",
];

/**
 * Converts an id from Tempo's trace-by-id JSON (base64 protobuf bytes) to lower-case hex,
 * left-padded to `hexLength`. Ids that already are hex of that length are kept.
 */
export function otlpIdToHex(id: string | undefined, hexLength: number): string | undefined {
  if (id === undefined || id === "") return undefined;
  let hex: string;
  if (id.length === hexLength && /^[0-9a-fA-F]+$/.test(id)) hex = id.toLowerCase();
  else hex = Buffer.from(id, "base64").toString("hex");
  if (hex === "" || /^0+$/.test(hex)) return undefined;
  return hex.padStart(hexLength, "0");
}

/** Converts a decimal nanosecond value (string or number) to integer microseconds. */
export function nanosToMicros(value: string | number | undefined): number {
  if (value === undefined || value === "") return 0;
  if (typeof value === "number") return Math.floor(value / 1000);
  try {
    return Number(BigInt(value) / 1000n);
  } catch {
    return 0;
  }
}

/** Converts an OTLP `AnyValue` to a model value (`undefined` for an empty value). */
export function fromAnyValue(value: OtlpAnyValue | null | undefined): SpanAttrValue | undefined {
  if (value === undefined || value === null) return undefined;
  if (value.stringValue !== undefined) return value.stringValue;
  if (value.boolValue !== undefined) return value.boolValue;
  if (value.intValue !== undefined) return Number(value.intValue);
  if (value.doubleValue !== undefined) return Number(value.doubleValue);
  if (value.arrayValue !== undefined) {
    const out: SpanAttrValue[] = [];
    for (const v of value.arrayValue.values ?? []) {
      const converted = fromAnyValue(v);
      if (converted !== undefined) out.push(converted);
    }
    return out;
  }
  if (value.kvlistValue !== undefined) return fromKeyValues(value.kvlistValue.values);
  if (value.bytesValue !== undefined) return value.bytesValue;
  return undefined;
}

/** Converts an OTLP attribute list to a model attribute map (empty values are dropped). */
export function fromKeyValues(list: readonly OtlpKeyValue[] | undefined): SpanAttributes {
  const out: SpanAttributes = {};
  for (const kv of list ?? []) {
    if (kv.key === undefined) continue;
    const v = fromAnyValue(kv.value);
    if (v !== undefined) out[kv.key] = v;
  }
  return out;
}

function kindName(kind: string | number | undefined): SpanKindName {
  if (typeof kind === "number") return KINDS[kind] ?? "unspecified";
  if (kind === undefined) return "unspecified";
  const word = kind.replace(/^SPAN_KIND_/, "").toLowerCase();
  return (KINDS as readonly string[]).includes(word) ? (word as SpanKindName) : "unspecified";
}

function statusName(code: string | number | undefined): SpanStatusName {
  if (code === 1 || code === "STATUS_CODE_OK") return "ok";
  if (code === 2 || code === "STATUS_CODE_ERROR") return "error";
  return "unset";
}

function convertSpan(span: OtlpSpan, serviceName: string): Span | undefined {
  const traceId = otlpIdToHex(span.traceId, 32);
  const spanId = otlpIdToHex(span.spanId, 16);
  if (traceId === undefined || spanId === undefined) return undefined;
  const startMicros = nanosToMicros(span.startTimeUnixNano);
  const endMicros = nanosToMicros(span.endTimeUnixNano);
  const status: Span["status"] = { code: statusName(span.status?.code) };
  if (span.status?.message !== undefined && span.status.message !== "")
    status.message = span.status.message;
  const out: Span = {
    traceId,
    spanId,
    name: span.name ?? "",
    kind: kindName(span.kind),
    serviceName,
    startMicros,
    endMicros,
    durationMicros: Math.max(0, endMicros - startMicros),
    status,
    attributes: fromKeyValues(span.attributes),
    events: (span.events ?? []).map((e) => ({
      name: e.name ?? "",
      timeMicros: nanosToMicros(e.timeUnixNano),
      attributes: fromKeyValues(e.attributes),
    })),
    links: (span.links ?? []).flatMap((l) => {
      const linkTrace = otlpIdToHex(l.traceId, 32);
      const linkSpan = otlpIdToHex(l.spanId, 16);
      if (linkTrace === undefined || linkSpan === undefined) return [];
      return [{ traceId: linkTrace, spanId: linkSpan, attributes: fromKeyValues(l.attributes) }];
    }),
  };
  const parent = otlpIdToHex(span.parentSpanId, 16);
  if (parent !== undefined) out.parentSpanId = parent;
  return out;
}

/**
 * Flattens OTLP `resourceSpans` → `scopeSpans` → spans into model spans: ids base64 → hex,
 * nanoseconds → microseconds, enums → names, `serviceName` from the resource `service.name`.
 * Sorted by `startMicros`, then `spanId`.
 */
export function spansFromResourceSpans(resourceSpans: readonly OtlpResourceSpans[]): Span[] {
  const spans: Span[] = [];
  for (const rs of resourceSpans) {
    const resource = fromKeyValues(rs.resource?.attributes);
    const service = resource["service.name"];
    const serviceName = typeof service === "string" ? service : "";
    for (const scope of [...(rs.scopeSpans ?? []), ...(rs.instrumentationLibrarySpans ?? [])]) {
      for (const span of scope.spans ?? []) {
        const converted = convertSpan(span, serviceName);
        if (converted !== undefined) spans.push(converted);
      }
    }
  }
  return sortSpans(spans);
}
