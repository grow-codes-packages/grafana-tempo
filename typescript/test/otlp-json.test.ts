import { describe, expect, it } from "vitest";
import {
  fromAnyValue,
  fromKeyValues,
  nanosToMicros,
  otlpIdToHex,
  spansFromResourceSpans,
  type OtlpResourceSpans,
} from "../src/index.js";
import { fixture } from "./support/tempo.js";

describe("OTLP JSON conversion", () => {
  it("converts ids", () => {
    expect(otlpIdToHex("APdLwbBYoai4X5rgIx5AAQ==", 32)).toBe("00f74bc1b058a1a8b85f9ae0231e4001");
    expect(otlpIdToHex("oAAAAAAAAAE=", 16)).toBe("a000000000000001");
    expect(otlpIdToHex("A000000000000001", 16)).toBe("a000000000000001");
    expect(otlpIdToHex(undefined, 16)).toBeUndefined();
    expect(otlpIdToHex("", 16)).toBeUndefined();
    expect(otlpIdToHex("AAAAAAAAAAA=", 16)).toBeUndefined();
    expect(otlpIdToHex("AQ==", 16)).toBe("0000000000000001");
  });

  it("converts nanoseconds losslessly", () => {
    expect(nanosToMicros("1795035005729668338")).toBe(1795035005729668);
    expect(nanosToMicros(5_000_999)).toBe(5000);
    expect(nanosToMicros(undefined)).toBe(0);
    expect(nanosToMicros("")).toBe(0);
    expect(nanosToMicros("not-a-number")).toBe(0);
  });

  it("converts every AnyValue kind", () => {
    expect(fromAnyValue({ stringValue: "s" })).toBe("s");
    expect(fromAnyValue({ boolValue: false })).toBe(false);
    expect(fromAnyValue({ intValue: "42" })).toBe(42);
    expect(fromAnyValue({ doubleValue: "1.5" })).toBe(1.5);
    expect(fromAnyValue({ bytesValue: "AQI=" })).toBe("AQI=");
    expect(
      fromAnyValue({ arrayValue: { values: [{ intValue: 1 }, {}, { stringValue: "b" }] } }),
    ).toEqual([1, "b"]);
    expect(fromAnyValue({ arrayValue: {} })).toEqual([]);
    expect(
      fromAnyValue({ kvlistValue: { values: [{ key: "k", value: { boolValue: true } }] } }),
    ).toEqual({ k: true });
    expect(fromAnyValue({ kvlistValue: {} })).toEqual({});
    expect(fromAnyValue({})).toBeUndefined();
    expect(fromAnyValue(undefined)).toBeUndefined();
    expect(fromKeyValues([{ value: { stringValue: "no key" } }, { key: "e" }])).toEqual({});
    expect(fromKeyValues(undefined)).toEqual({});
  });

  it("flattens the recorded seed trace (links, error status, events)", () => {
    const body = fixture("getTrace.seed-v2").response.body as {
      trace: { resourceSpans: OtlpResourceSpans[] };
    };
    const spans = spansFromResourceSpans(body.trace.resourceSpans);
    expect(spans).toHaveLength(3);
    expect(new Set(spans.map((s) => s.traceId))).toEqual(
      new Set(["5b8efff798038103d269b633813fc60c"]),
    );
    expect(spans.some((s) => s.status.code === "error" && s.status.message !== undefined)).toBe(
      true,
    );
    expect(spans.some((s) => s.links.length > 0)).toBe(true);
    expect(spans.some((s) => s.events.length > 0)).toBe(true);
  });

  it("handles numeric enums, legacy scopes and broken spans", () => {
    const spans = spansFromResourceSpans([
      {
        instrumentationLibrarySpans: [
          {
            spans: [
              {
                traceId: "AQ==",
                spanId: "Ag==",
                kind: 3,
                status: { code: 2, message: "" },
                links: [{ traceId: "", spanId: "Aw==" }],
                events: [{}],
              },
              { traceId: "AQ==", spanId: "Aw==", kind: 9, status: { code: 1 } },
              { traceId: "AQ==", spanId: "BA==", kind: "SPAN_KIND_WEIRD" },
              { traceId: "AQ==" },
            ],
          },
        ],
      },
      {
        resource: { attributes: [{ key: "service.name", value: { intValue: 1 } }] },
        scopeSpans: [{}],
      },
      {},
    ]);
    expect(spans).toHaveLength(3);
    expect(spans[0]).toMatchObject({
      kind: "client",
      status: { code: "error" },
      serviceName: "",
      name: "",
      links: [],
      events: [{ name: "", timeMicros: 0, attributes: {} }],
    });
    expect(spans[0]!.status.message).toBeUndefined();
    expect(spans[1]).toMatchObject({ kind: "unspecified", status: { code: "ok" } });
    expect(spans[2]).toMatchObject({ kind: "unspecified", status: { code: "unset" } });
  });
});
