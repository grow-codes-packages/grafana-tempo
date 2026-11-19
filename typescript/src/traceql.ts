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

/** Span-set operators accepted by {@link TraceQL.structural}. */
export type SpansetOperator = ">" | ">>" | "<" | "<<" | "~" | "&&" | "||";

/** Every span-set operator {@link TraceQL.structural} accepts. */
export const SPANSET_OPERATORS: readonly SpansetOperator[] = Object.freeze([
  ">",
  ">>",
  "<",
  "<<",
  "~",
  "&&",
  "||",
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
  "childCount",
  "nestedSetLeft",
  "nestedSetRight",
  "nestedSetParent",
]);

const SCOPED_INTRINSIC = /^(span|trace|event|link|instrumentation):[A-Za-z][A-Za-z0-9_]*$/;
const SCOPED_ATTRIBUTE = /^(resource|span|event|link|instrumentation)?\.(.+)$/s;
const PLAIN_ATTRIBUTE = /^[A-Za-z0-9_][A-Za-z0-9_.]*$/;
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

const ESCAPES: Readonly<Record<string, string>> = {
  "\\": "\\\\",
  '"': '\\"',
  "\n": "\\n",
  "\t": "\\t",
  "\r": "\\r",
};

/**
 * Quotes a string as a TraceQL string literal: `"…"` with `\`, `"`, newline, tab and carriage
 * return escaped (other characters, including Unicode, pass through).
 */
function quote(text: string): string {
  return `"${text.replace(/[\\"\n\t\r]/g, (ch) => ESCAPES[ch] ?? ch)}"`;
}

function attributeName(name: string, field: string): string {
  if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) {
    const inner = name.slice(1, -1);
    if (/(^|[^\\])"/.test(inner) || inner.endsWith("\\")) {
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

/** One span-set filter and the span-set operator joining it to the previous one ("" first). */
interface Part {
  readonly op: SpansetOperator | "";
  readonly conditions: readonly Condition[];
}

/**
 * Immutable TraceQL builder with escaping: span-set filters, structural operators and
 * `select()`.
 *
 * ```ts
 * TraceQL.span({ "resource.service.name": "api" })
 *   .and("duration", ">", "200ms")
 *   .select("span.http.status_code")
 *   .toString();
 * // { resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)
 *
 * TraceQL.span({ "resource.service.name": "api" }).child(TraceQL.span({ name: "db" })).toString();
 * // { resource.service.name = "api" } > { name = "db" }
 * ```
 *
 * Field names are validated (unknown scopes or intrinsics throw `ValidationError`, because some
 * Tempo endpoints silently ignore an invalid query); attribute names with anything outside
 * `[A-Za-z0-9_.]` are quoted after the scope (`span."grow weird-key"`). String values are quoted
 * and escaped (`\`, `"`, newline, tab, CR), except duration literals on duration fields
 * (`200ms`), `ok|error|unset` on `status` and span kinds on `kind`. Numbers and booleans are
 * written as literals; `=~` / `!~` need a string.
 */
export class TraceQL {
  readonly #parts: readonly Part[];
  readonly #selects: readonly string[];

  private constructor(parts: readonly Part[], selects: readonly string[]) {
    this.#parts = parts;
    this.#selects = selects;
  }

  /**
   * Starts a spanset filter. Each `filters` entry becomes `<field> = <value>`, joined with `&&`
   * in insertion order. `TraceQL.span()` alone matches every span (`{}`).
   */
  static span(filters: Readonly<Record<string, TraceQLValue>> = {}): TraceQL {
    let q = new TraceQL([{ op: "", conditions: [] }], []);
    for (const [field, value] of Object.entries(filters)) q = q.and(field, "=", value);
    return q;
  }

  /** Starts a spanset filter with one condition: `{ <field> <op> <value> }`. */
  static where(field: string, op: TraceQLOperator, value: TraceQLValue): TraceQL {
    return TraceQL.span().and(field, op, value);
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
    let rendered: string;
    if (op === "=~" || op === "!~") {
      if (typeof value !== "string")
        throw new ValidationError(`traceql: operator ${op} needs a string value`);
      rendered = quote(value);
    } else {
      rendered = TraceQL.value(name, value);
    }
    const last = this.#parts[this.#parts.length - 1] as Part;
    const parts: Part[] = [
      ...this.#parts.slice(0, -1),
      {
        op: last.op,
        conditions: [...last.conditions, { join, text: `${name} ${op} ${rendered}` }],
      },
    ];
    return new TraceQL(parts, this.#selects);
  }

  /** Adds `&& <field> <op> <value>` to the last spanset filter (returns a new builder). */
  and(field: string, op: TraceQLOperator, value: TraceQLValue): TraceQL {
    return this.#add("&&", field, op, value);
  }

  /** Adds `|| <field> <op> <value>` to the last spanset filter (returns a new builder). */
  or(field: string, op: TraceQLOperator, value: TraceQLValue): TraceQL {
    return this.#add("||", field, op, value);
  }

  /**
   * Joins `other`'s spanset filters with a span-set operator: `>` (child), `>>` (descendant),
   * `<` (parent), `<<` (ancestor), `~` (sibling), `&&` / `||` (both / either). `other`'s
   * `select()` is ignored.
   */
  structural(op: SpansetOperator, other: TraceQL): TraceQL {
    if (!SPANSET_OPERATORS.includes(op)) {
      throw new ValidationError(`traceql: unknown span-set operator "${op}"`);
    }
    if (!(other instanceof TraceQL)) {
      throw new ValidationError("traceql: the span-set operand must be a TraceQL builder");
    }
    const joined = other.#parts.map((p, i) => (i === 0 ? { op, conditions: p.conditions } : p));
    return new TraceQL([...this.#parts, ...joined], this.#selects);
  }

  /** `this > other`: spans of `other` that are direct children. */
  child(other: TraceQL): TraceQL {
    return this.structural(">", other);
  }

  /** `this >> other`: spans of `other` below `this` at any depth. */
  descendant(other: TraceQL): TraceQL {
    return this.structural(">>", other);
  }

  /** `this < other`: spans of `other` that are direct parents. */
  parent(other: TraceQL): TraceQL {
    return this.structural("<", other);
  }

  /** `this << other`: spans of `other` above `this` at any depth. */
  ancestor(other: TraceQL): TraceQL {
    return this.structural("<<", other);
  }

  /** `this ~ other`: spans of `other` with the same parent. */
  sibling(other: TraceQL): TraceQL {
    return this.structural("~", other);
  }

  /** Adds fields to `| select(…)` so search returns them on each matched span. */
  select(...fields: string[]): TraceQL {
    if (fields.length === 0) throw new ValidationError("traceql: select() needs a field");
    return new TraceQL(this.#parts, [...this.#selects, ...fields.map((f) => TraceQL.field(f))]);
  }

  /** The TraceQL text. */
  toString(): string {
    const out: string[] = [];
    for (const part of this.#parts) {
      let body = "";
      part.conditions.forEach((c, i) => {
        body += i === 0 ? c.text : ` ${c.join} ${c.text}`;
      });
      const spanset = body === "" ? "{}" : `{ ${body} }`;
      out.push(part.op === "" ? spanset : `${part.op} ${spanset}`);
    }
    const text = out.join(" ");
    return this.#selects.length === 0 ? text : `${text} | select(${this.#selects.join(", ")})`;
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
