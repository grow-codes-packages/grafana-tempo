import { describe, expect, it } from "vitest";
import {
  TRACEQL_OPERATORS,
  TRACEQL_SCOPES,
  TraceQL,
  ValidationError,
  checkTraceQL,
  traceQLText,
  type TraceQLOperator,
} from "../src/index.js";
import { fixture } from "./support/tempo.js";

describe("TraceQL builder", () => {
  it("renders the ticket example", () => {
    const q = TraceQL.span({ "resource.service.name": "api" })
      .and("duration", ">", "200ms")
      .select("span.http.status_code");
    expect(q.toString()).toBe(
      '{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)',
    );
    expect(JSON.stringify({ q })).toBe(JSON.stringify({ q: q.toString() }));
  });

  it("matches the queries verified against the real Tempo", () => {
    const svc = "grow-sdk-verify-179503498127460-api";
    const cases: [string, TraceQL][] = [
      [
        "search.escaped-string-value",
        TraceQL.span({ "resource.service.name": svc, "span.grow.note": 'a "quoted" \\ value ü' }),
      ],
      [
        "search.quoted-attribute-name",
        TraceQL.span({ "resource.service.name": svc }).and("span.grow weird.key", "=", "x y"),
      ],
      [
        "search.typed-int-compare",
        TraceQL.span({ "resource.service.name": svc }).and(
          "span.http.response.status_code",
          ">=",
          500,
        ),
      ],
      [
        "search.status-error",
        TraceQL.span({ "resource.service.name": svc }).and("status", "=", "error"),
      ],
      [
        "search.select-name-status",
        TraceQL.span({ "resource.service.name": svc }).select("name", "status"),
      ],
      [
        "searchTraces.duration-excludes",
        TraceQL.span({ "resource.service.name": svc }).and("trace:duration", ">", "1s"),
      ],
    ];
    for (const [name, q] of cases) {
      expect(q.toString(), name).toBe(fixture(name).request.query?.q);
    }
  });

  it("is immutable and supports || and typed values", () => {
    const base = TraceQL.span();
    expect(base.toString()).toBe("{}");
    const a = base.and("kind", "=", "server").or("span.ok", "!=", true);
    expect(base.toString()).toBe("{}");
    expect(a.toString()).toBe("{ kind = server || span.ok != true }");
    expect(TraceQL.span({ ".x": false, "event.n": 1.5 }).toString()).toBe(
      "{ .x = false && event.n = 1.5 }",
    );
    expect(
      TraceQL.span({ status: "weird", kind: "other", "span:duration": "long" }).toString(),
    ).toBe('{ status = "weird" && kind = "other" && span:duration = "long" }');
    expect(TraceQL.span().and("name", "=~", "GET .*").toString()).toBe('{ name =~ "GET .*" }');
    expect(TraceQL.span().select(".b").select("span:id").toString()).toBe(
      "{} | select(.b, span:id)",
    );
  });

  it("formats field names", () => {
    expect(TraceQL.field(" duration ")).toBe("span:duration");
    expect(TraceQL.field("trace:rootService")).toBe("trace:rootService");
    expect(TraceQL.field("rootServiceName")).toBe("rootServiceName");
    expect(TraceQL.field("link.kind")).toBe("link.kind");
    expect(TraceQL.field("instrumentation.x-y/z")).toBe("instrumentation.x-y/z");
    expect(TraceQL.field('span."already quoted"')).toBe('span."already quoted"');
    expect(TraceQL.field('span.a"b')).toBe('span."a\\"b"');
    expect(TraceQL.field("span.trailing.")).toBe('span."trailing."');
    expect(TraceQL.quote('a"b\\c')).toBe('"a\\"b\\\\c"');
    expect(TRACEQL_SCOPES).toContain("resource");
    expect(TRACEQL_OPERATORS).toHaveLength(8);
  });

  it("rejects unknown scopes, operators and values", () => {
    for (const bad of ["", "service.name", "foo", "bogus:thing", 'span."a"b"']) {
      expect(() => TraceQL.field(bad), bad).toThrow(ValidationError);
    }
    expect(() => TraceQL.span().and("name", "==" as TraceQLOperator, "x")).toThrow(/operator/);
    expect(() => TraceQL.span().and("span.n", ">", Number.NaN)).toThrow(/finite/);
    expect(() => TraceQL.span().and("span.n", "=", null as unknown as string)).toThrow(
      ValidationError,
    );
    expect(() => TraceQL.span().select()).toThrow(/select/);
  });
});

describe("checkTraceQL / traceQLText", () => {
  it("accepts balanced queries and rejects broken ones", () => {
    expect(checkTraceQL('  { name = "a}" } | select(name) ', "op")).toBe(
      '{ name = "a}" } | select(name)',
    );
    expect(checkTraceQL('{ span.x = "a\\"b" } >> { }', "op")).toBe('{ span.x = "a\\"b" } >> { }');
    for (const bad of ["name = 1", "{ bad", "{ a ) }", '{ name = "x }', "{ } )", "{ ( }"]) {
      expect(() => checkTraceQL(bad, "op"), bad).toThrow(ValidationError);
    }
    expect(traceQLText("x")).toBe("x");
    expect(traceQLText(TraceQL.span())).toBe("{}");
  });
});
