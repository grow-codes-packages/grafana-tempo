// Service-owned: TraceQL builder for Grafana Tempo.

import { ValidationError } from "./core/index.js";

/** Comparison operators accepted by {@link TraceQL.and} / {@link TraceQL.or}. */
export type TraceQLOperator = "=" | "!=" | ">" | ">=" | "<" | "<=" | "=~" | "!~";

/** A value in a TraceQL condition. Strings are quoted and escaped unless the field is typed. */
export type TraceQLValue = string | number | boolean;

/** Every operator the builder accepts (anything else is a `ValidationError`). */
export const TRACEQL_OPERATORS: readonly TraceQLOperator[] = Object.freeze([
  "=",
  "!=",
  ">",
  ">=",
  "<",
  "<=",
  "=~",
  "!~",
]);

/** Attribute scopes (`resource.`, `span.`, …; a leading `.` means "span or resource"). */
export const TRACEQL_SCOPES: readonly string[] = Object.freeze([
  "resource",
  "span",
  "event",
  "link",
  "instrumentation",
]);

/** Unscoped intrinsics. `duration` is written as `span:duration`. */
const BARE_INTRINSICS: ReadonlySet<string> = new Set([
  "name",
  "status",
  "statusMessage",
  "kind",
  "duration",
  "rootName",
  "rootServiceName",
  "traceDuration",
]);

const SCOPED_INTRINSIC = /^(span|trace|event|link|instrumentation):[A-Za-z][A-Za-z0-9]*$/;
const SCOPED_ATTRIBUTE = /^(resource|span|event|link|instrumentation)?\.(.+)$/s;
const PLAIN_ATTRIBUTE = /^[A-Za-z0-9_][A-Za-z0-9_.\-/]*$/;
const DURATION_LITERAL = /^\d+(\.\d+)?(ns|us|µs|ms|s|m|h)$/;

const DURATION_FIELDS: ReadonlySet<string> = new Set([
  "span:duration",
  "trace:duration",
  "traceDuration",
]);
const STATUS_FIELDS: ReadonlySet<string> = new Set(["status", "span:status"]);
const STATUS_WORDS: ReadonlySet<string> = new Set(["ok", "error", "unset"]);
const KIND_FIELDS: ReadonlySet<string> = new Set(["kind", "span:kind"]);
const KIND_WORDS: ReadonlySet<string> = new Set([
  "unspecified",
  "internal",
  "server",
  "client",
  "producer",
  "consumer",
]);

/** Quotes a string as a TraceQL string literal: `"…"` with `\` and `"` escaped. */
function quote(text: string): string {
  return `"${text.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

function attributeName(name: string, field: string): string {
  if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
    const inner = name.slice(1, -1);
    if (/(^|[^\\])"/.test(inner)) {
      throw new ValidationError(`traceql: invalid quoted attribute name in "${field}"`);
    }
    return name;
  }
  if (PLAIN_ATTRIBUTE.test(name) && !name.endsWith(".")) return name;
  return quote(name);
}

/** One rendered condition plus how it joins the previous one. */
interface Condition {
  readonly join: "&&" | "||";
  readonly text: string;
}

/**
 * Immutable TraceQL spanset builder with escaping.
 *
 * ```ts
 * TraceQL.span({ "resource.service.name": "api" })
 *   .and("duration", ">", "200ms")
 *   .select("span.http.status_code")
 *   .toString();
 * // { resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)
 * ```
 *
 * Field names are validated (unknown scopes or intrinsics throw `ValidationError`, because some
 * Tempo endpoints silently ignore an invalid query); attribute names with spaces or special
 * characters are quoted after the scope (`span."grow weird.key"`). String values are quoted and
 * escaped, except duration literals on duration fields (`200ms`), `ok|error|unset` on `status`
 * and span kinds on `kind`. Numbers and booleans are written as literals.
 */
export class TraceQL {
  readonly #conditions: readonly Condition[];
  readonly #selects: readonly string[];

  private constructor(conditions: readonly Condition[], selects: readonly string[]) {
    this.#conditions = conditions;
    this.#selects = selects;
  }

  /**
   * Starts a spanset filter. Each `filters` entry becomes `<field> = <value>`, joined with `&&`
   * in insertion order. `TraceQL.span()` alone matches every span (`{}`).
   */
  static span(filters: Readonly<Record<string, TraceQLValue>> = {}): TraceQL {
    let q = new TraceQL([], []);
    for (const [field, value] of Object.entries(filters)) q = q.and(field, "=", value);
    return q;
  }

  /** Renders a field name: validates the scope or intrinsic and quotes odd attribute names. */
  static field(name: string): string {
    const field = name.trim();
    if (field === "") throw new ValidationError("traceql: field name must not be empty");
    if (field === "duration") return "span:duration";
    if (BARE_INTRINSICS.has(field) || SCOPED_INTRINSIC.test(field)) return field;
    const match = SCOPED_ATTRIBUTE.exec(field);
    if (match !== null) {
      const scope = match[1] ?? "";
      return `${scope}.${attributeName(match[2] ?? "", field)}`;
    }
    throw new ValidationError(
      `traceql: unknown scope or intrinsic in "${field}" (use resource., span., event., link., instrumentation., . or an intrinsic)`,
    );
  }

  /** Quotes and escapes a string as a TraceQL string literal. */
  static quote(text: string): string {
    return quote(text);
  }

  /** Renders a value for `field` (already rendered by {@link TraceQL.field}). */
  static value(field: string, value: TraceQLValue): string {
    if (typeof value === "number") {
      if (!Number.isFinite(value))
        throw new ValidationError("traceql: numeric values must be finite");
      return String(value);
    }
    if (typeof value === "boolean") return value ? "true" : "false";
    if (typeof value !== "string")
      throw new ValidationError("traceql: values must be strings, numbers or booleans");
    if (DURATION_FIELDS.has(field) && DURATION_LITERAL.test(value)) return value;
    if (STATUS_FIELDS.has(field) && STATUS_WORDS.has(value)) return value;
    if (KIND_FIELDS.has(field) && KIND_WORDS.has(value)) return value;
    return quote(value);
  }

  #add(join: "&&" | "||", field: string, op: TraceQLOperator, value: TraceQLValue): TraceQL {
    if (!TRACEQL_OPERATORS.includes(op)) {
      throw new ValidationError(`traceql: unsupported operator "${op}"`);
    }
    const name = TraceQL.field(field);
    const text = `${name} ${op} ${TraceQL.value(name, value)}`;
    return new TraceQL([...this.#conditions, { join, text }], this.#selects);
  }

  /** Adds `&& <field> <op> <value>` inside the spanset (returns a new builder). */
  and(field: string, op: TraceQLOperator, value: TraceQLValue): TraceQL {
    return this.#add("&&", field, op, value);
  }

  /** Adds `|| <field> <op> <value>` inside the spanset (returns a new builder). */
  or(field: string, op: TraceQLOperator, value: TraceQLValue): TraceQL {
    return this.#add("||", field, op, value);
  }

  /** Adds fields to `| select(…)` so search returns them on each matched span. */
  select(...fields: string[]): TraceQL {
    if (fields.length === 0) throw new ValidationError("traceql: select() needs a field");
    return new TraceQL(this.#conditions, [
      ...this.#selects,
      ...fields.map((f) => TraceQL.field(f)),
    ]);
  }

  /** The TraceQL text. */
  toString(): string {
    let body = "";
    this.#conditions.forEach((c, i) => {
      body += i === 0 ? c.text : ` ${c.join} ${c.text}`;
    });
    const spanset = body === "" ? "{}" : `{ ${body} }`;
    return this.#selects.length === 0
      ? spanset
      : `${spanset} | select(${this.#selects.join(", ")})`;
  }

  /** Same as {@link toString} (so a builder serialises as its query text). */
  toJSON(): string {
    return this.toString();
  }
}

/**
 * Light client-side check of a TraceQL text: it must start with `{`, and quotes, braces and
 * parentheses must balance. Tempo's tag endpoints ignore an invalid `q` and return unfiltered
 * values, so the SDK refuses obviously broken queries there (`ValidationError`).
 */
export function checkTraceQL(query: string, operation: string): string {
  const text = query.trim();
  if (!text.startsWith("{")) {
    throw new ValidationError(`${operation}: TraceQL query must start with "{"`);
  }
  const stack: string[] = [];
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (ch === "\\") i++;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{" || ch === "(") stack.push(ch === "{" ? "}" : ")");
    else if (ch === "}" || ch === ")") {
      if (stack.pop() !== ch) throw new ValidationError(`${operation}: unbalanced TraceQL query`);
    }
  }
  if (inString || stack.length > 0) {
    throw new ValidationError(`${operation}: unbalanced TraceQL query`);
  }
  return text;
}

/** Renders a query argument (`string` or {@link TraceQL}) to text. */
export function traceQLText(query: string | TraceQL): string {
  return typeof query === "string" ? query : query.toString();
}
