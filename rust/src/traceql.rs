//! TraceQL builder with escaping (service module, owned by this repository).
//!
//! ```
//! use grow_grafana_tempo::TraceQL;
//!
//! let q = TraceQL::span([("resource.service.name", "api")])
//!     .and("duration", ">", "200ms")
//!     .select(["span.http.status_code"])
//!     .build()
//!     .unwrap();
//! assert_eq!(
//!     q,
//!     r#"{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)"#
//! );
//! ```

use std::fmt;
use std::time::Duration;

use crate::core::{Error, Result};

/// Attribute scopes a TraceQL attribute name may start with (`.` = span or resource).
pub const TRACEQL_SCOPES: &[&str] = &[
    "resource.",
    "span.",
    "event.",
    "link.",
    "instrumentation.",
    ".",
];

/// Comparison operators accepted by [`TraceQL::and`] / [`TraceQL::or`].
pub const TRACEQL_OPERATORS: &[&str] = &["=", "!=", ">", ">=", "<", "<=", "=~", "!~"];

/// Span-set operators accepted by [`TraceQL::structural`].
pub const SPANSET_OPERATORS: &[&str] = &[">", ">>", "<", "<<", "~", "&&", "||"];

const INTRINSICS: &[&str] = &[
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
];
const STATUSES: &[&str] = &["error", "ok", "unset"];
const KINDS: &[&str] = &[
    "unspecified",
    "internal",
    "server",
    "client",
    "producer",
    "consumer",
];

/// A value in a TraceQL condition.
#[derive(Debug, Clone, PartialEq)]
pub enum TraceQLValue {
    /// A string: quoted and escaped, except duration literals for duration intrinsics
    /// (`"200ms"`) and status/kind keywords (`"error"`, `"server"`).
    Str(String),
    /// A signed integer.
    Int(i64),
    /// An unsigned integer.
    UInt(u64),
    /// A finite float (rendered with a decimal point).
    Float(f64),
    /// `true` / `false`.
    Bool(bool),
    /// A duration (`1h`, `90s`, `200ms`, `1500us`, `7ns`).
    Duration(Duration),
    /// Text written into the query as is (no escaping).
    Raw(String),
}

impl TraceQLValue {
    /// A value written into the query as is.
    pub fn raw(text: impl Into<String>) -> Self {
        TraceQLValue::Raw(text.into())
    }
}

impl From<&str> for TraceQLValue {
    fn from(v: &str) -> Self {
        TraceQLValue::Str(v.to_owned())
    }
}
impl From<String> for TraceQLValue {
    fn from(v: String) -> Self {
        TraceQLValue::Str(v)
    }
}
impl From<&String> for TraceQLValue {
    fn from(v: &String) -> Self {
        TraceQLValue::Str(v.clone())
    }
}
impl From<bool> for TraceQLValue {
    fn from(v: bool) -> Self {
        TraceQLValue::Bool(v)
    }
}
impl From<i32> for TraceQLValue {
    fn from(v: i32) -> Self {
        TraceQLValue::Int(v.into())
    }
}
impl From<i64> for TraceQLValue {
    fn from(v: i64) -> Self {
        TraceQLValue::Int(v)
    }
}
impl From<u32> for TraceQLValue {
    fn from(v: u32) -> Self {
        TraceQLValue::UInt(v.into())
    }
}
impl From<u64> for TraceQLValue {
    fn from(v: u64) -> Self {
        TraceQLValue::UInt(v)
    }
}
impl From<usize> for TraceQLValue {
    fn from(v: usize) -> Self {
        TraceQLValue::UInt(v as u64)
    }
}
impl From<f32> for TraceQLValue {
    fn from(v: f32) -> Self {
        TraceQLValue::Float(f64::from(v))
    }
}
impl From<f64> for TraceQLValue {
    fn from(v: f64) -> Self {
        TraceQLValue::Float(v)
    }
}
impl From<Duration> for TraceQLValue {
    fn from(v: Duration) -> Self {
        TraceQLValue::Duration(v)
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
struct Part {
    /// Span-set operator before this span set (empty for the first).
    op: String,
    conds: Vec<String>,
}

/// An immutable TraceQL query under construction: every method returns a new query, so a
/// query can be reused as a base.
///
/// Attribute names need a scope (`resource.`, `span.`, `event.`, `link.`, `instrumentation.`
/// or `.` for any) or must be an intrinsic (`name`, `status`, `kind`, `duration`,
/// `statusMessage`, `span:*`, `trace:*`, …). `duration` is written as `span:duration`. Names
/// with spaces or other special characters are quoted (`span."my key"`); string values are
/// quoted and escaped. Unknown operators and unscoped names are `VALIDATION` errors, reported
/// by [`TraceQL::build`].
#[derive(Debug, Clone, PartialEq)]
pub struct TraceQL {
    parts: Vec<Part>,
    selects: Vec<String>,
    err: Option<String>,
}

impl Default for TraceQL {
    fn default() -> Self {
        TraceQL::new()
    }
}

impl TraceQL {
    /// The query `{}` (all spans).
    pub fn new() -> Self {
        TraceQL {
            parts: vec![Part::default()],
            selects: Vec::new(),
            err: None,
        }
    }

    /// One span-set filter whose conditions are `attribute = value`, joined with `&&` in key
    /// order. An empty list gives `{}`.
    pub fn span<I, K, V>(conditions: I) -> Self
    where
        I: IntoIterator<Item = (K, V)>,
        K: Into<String>,
        V: Into<TraceQLValue>,
    {
        let mut conds: Vec<(String, TraceQLValue)> = conditions
            .into_iter()
            .map(|(k, v)| (k.into(), v.into()))
            .collect();
        conds.sort_by(|a, b| a.0.cmp(&b.0));
        conds
            .into_iter()
            .fold(TraceQL::new(), |q, (k, v)| q.and(&k, "=", v))
    }

    /// A query with one condition: `{ attr op value }`.
    pub fn where_(attr: &str, op: &str, value: impl Into<TraceQLValue>) -> Self {
        TraceQL::new().and(attr, op, value)
    }

    fn fail(&self, message: String) -> Self {
        let mut out = self.clone();
        if out.err.is_none() {
            out.err = Some(message);
        }
        out
    }

    fn add(&self, join: &str, attr: &str, op: &str, value: TraceQLValue) -> Self {
        let cond = match condition(attr, op, value) {
            Ok(c) => c,
            Err(e) => return self.fail(e),
        };
        let mut out = self.clone();
        let last = out.parts.last_mut().expect("at least one span set");
        if !last.conds.is_empty() {
            last.conds.push(join.to_owned());
        }
        last.conds.push(cond);
        out
    }

    /// Adds `&& attr op value` to the last span-set filter. Operators: `= != > >= < <= =~ !~`
    /// (the regex operators need a string value).
    pub fn and(&self, attr: &str, op: &str, value: impl Into<TraceQLValue>) -> Self {
        self.add("&&", attr, op, value.into())
    }

    /// Adds `|| attr op value` to the last span-set filter.
    pub fn or(&self, attr: &str, op: &str, value: impl Into<TraceQLValue>) -> Self {
        self.add("||", attr, op, value.into())
    }

    /// Joins `other`'s span sets with a span-set operator: `>` (child), `>>` (descendant), `<`
    /// (parent), `<<` (ancestor), `~` (sibling), `&&` or `||`. `other`'s `select()` is ignored.
    pub fn structural(&self, op: &str, other: &TraceQL) -> Self {
        if !SPANSET_OPERATORS.contains(&op) {
            return self.fail(format!("unknown span-set operator {op:?}"));
        }
        if let Some(e) = &other.err {
            return self.fail(e.clone());
        }
        let mut out = self.clone();
        for (i, p) in other.parts.iter().enumerate() {
            out.parts.push(Part {
                op: if i == 0 { op.to_owned() } else { p.op.clone() },
                conds: p.conds.clone(),
            });
        }
        out
    }

    /// `structural(">", other)`: spans of `other` that are direct children.
    pub fn child(&self, other: &TraceQL) -> Self {
        self.structural(">", other)
    }
    /// `structural(">>", other)`.
    pub fn descendant(&self, other: &TraceQL) -> Self {
        self.structural(">>", other)
    }
    /// `structural("<", other)`.
    pub fn parent(&self, other: &TraceQL) -> Self {
        self.structural("<", other)
    }
    /// `structural("<<", other)`.
    pub fn ancestor(&self, other: &TraceQL) -> Self {
        self.structural("<<", other)
    }
    /// `structural("~", other)`.
    pub fn sibling(&self, other: &TraceQL) -> Self {
        self.structural("~", other)
    }

    /// Appends `| select(attrs…)`: these attributes (and `name` / `status`) come back with every
    /// matching span.
    pub fn select<I, S>(&self, attrs: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let mut out = self.clone();
        for a in attrs {
            match traceql_field(a.as_ref()) {
                Ok(name) => out.selects.push(name),
                Err(e) => return self.fail(e.message().trim_start_matches("traceql: ").to_owned()),
            }
        }
        out
    }

    /// The first error recorded while building (`VALIDATION`), if any.
    pub fn err(&self) -> Option<Error> {
        self.err
            .as_ref()
            .map(|e| Error::validation(format!("traceql: {e}")))
    }

    /// Renders the query, or returns the first `VALIDATION` error.
    pub fn build(&self) -> Result<String> {
        if let Some(e) = self.err() {
            return Err(e);
        }
        let mut s = String::new();
        for (i, p) in self.parts.iter().enumerate() {
            if i > 0 {
                s.push(' ');
                s.push_str(&p.op);
                s.push(' ');
            }
            if p.conds.is_empty() {
                s.push_str("{}");
            } else {
                s.push_str("{ ");
                s.push_str(&p.conds.join(" "));
                s.push_str(" }");
            }
        }
        if !self.selects.is_empty() {
            s.push_str(" | select(");
            s.push_str(&self.selects.join(", "));
            s.push(')');
        }
        Ok(s)
    }
}

/// Renders the query; empty when it is invalid (see [`TraceQL::build`]).
impl fmt::Display for TraceQL {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.build().unwrap_or_default())
    }
}

/// Quotes `s` as a TraceQL string literal: `\`, `"`, newline, tab and CR are escaped; other
/// characters (Unicode included) pass through.
pub fn escape_traceql_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn is_plain_name(rest: &str) -> bool {
    let mut chars = rest.chars();
    match chars.next() {
        Some(c) if c.is_ascii_alphanumeric() || c == '_' => {}
        _ => return false,
    }
    chars.all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.')
}

fn is_colon_scoped(name: &str) -> bool {
    let Some((scope, rest)) = name.split_once(':') else {
        return false;
    };
    if !matches!(
        scope,
        "span" | "trace" | "event" | "link" | "instrumentation"
    ) {
        return false;
    }
    let mut chars = rest.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_alphabetic())
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Validates and renders a TraceQL attribute name: intrinsics and `span:*` / `trace:*` names
/// as is, `duration` as `span:duration`, scoped names as is or with the part after the scope
/// quoted when it holds special characters (`span."my key"`). `VALIDATION` for unscoped names.
pub fn traceql_field(name: &str) -> Result<String> {
    let name = name.trim();
    if name == "duration" {
        return Ok("span:duration".to_owned());
    }
    if INTRINSICS.contains(&name) || is_colon_scoped(name) {
        return Ok(name.to_owned());
    }
    for scope in TRACEQL_SCOPES {
        let Some(rest) = name.strip_prefix(scope) else {
            continue;
        };
        if rest.starts_with('"') {
            if rest.len() >= 2 && rest.ends_with('"') && !rest.ends_with("\\\"") {
                return Ok(name.to_owned());
            }
        } else if is_plain_name(rest) {
            return Ok(name.to_owned());
        } else if !rest.is_empty() {
            return Ok(format!("{scope}{}", escape_traceql_string(rest)));
        }
        return Err(Error::validation(format!(
            "traceql: invalid attribute name {name:?}"
        )));
    }
    Err(Error::validation(format!(
        "traceql: attribute {name:?} needs a scope (resource., span., event., link., instrumentation. or .) or must be an intrinsic"
    )))
}

fn is_duration_attr(a: &str) -> bool {
    matches!(a, "span:duration" | "trace:duration" | "traceDuration")
}

/// `^[0-9]+(\.[0-9]+)?(ns|us|µs|ms|s|m|h)$`
fn is_duration_literal(v: &str) -> bool {
    let unit_start = v
        .find(|c: char| !(c.is_ascii_digit() || c == '.'))
        .unwrap_or(v.len());
    let (num, unit) = v.split_at(unit_start);
    if !matches!(unit, "ns" | "us" | "µs" | "ms" | "s" | "m" | "h") {
        return false;
    }
    let mut it = num.splitn(2, '.');
    let int = it.next().unwrap_or("");
    let frac = it.next();
    !int.is_empty()
        && int.bytes().all(|b| b.is_ascii_digit())
        && frac.is_none_or(|f| !f.is_empty() && f.bytes().all(|b| b.is_ascii_digit()))
}

fn condition(attr: &str, op: &str, value: TraceQLValue) -> std::result::Result<String, String> {
    let name =
        traceql_field(attr).map_err(|e| e.message().trim_start_matches("traceql: ").to_owned())?;
    if !TRACEQL_OPERATORS.contains(&op) {
        return Err(format!("unknown operator {op:?}"));
    }
    if op == "=~" || op == "!~" {
        return match value {
            TraceQLValue::Str(s) => Ok(format!("{name} {op} {}", escape_traceql_string(&s))),
            _ => Err(format!("operator {op} needs a string value")),
        };
    }
    let v = render_value(&name, value)?;
    Ok(format!("{name} {op} {v}"))
}

fn render_value(name: &str, value: TraceQLValue) -> std::result::Result<String, String> {
    Ok(match value {
        TraceQLValue::Raw(s) => s,
        TraceQLValue::Str(s) => {
            let bare = (is_duration_attr(name) && is_duration_literal(&s))
                || (matches!(name, "status" | "span:status") && STATUSES.contains(&s.as_str()))
                || (matches!(name, "kind" | "span:kind") && KINDS.contains(&s.as_str()));
            if bare {
                s
            } else {
                escape_traceql_string(&s)
            }
        }
        TraceQLValue::Duration(d) => traceql_duration(d),
        TraceQLValue::Bool(b) => b.to_string(),
        TraceQLValue::Int(n) => n.to_string(),
        TraceQLValue::UInt(n) => n.to_string(),
        TraceQLValue::Float(f) => {
            if !f.is_finite() {
                return Err("value must be a finite number".to_owned());
            }
            let s = f.to_string();
            if s.contains('.') {
                s
            } else {
                format!("{s}.0")
            }
        }
    })
}

/// Renders a duration with the largest exact unit: `1h`, `90s`, `200ms`, `1500us`, `7ns`
/// (`0s` for zero).
pub fn traceql_duration(d: Duration) -> String {
    let ns = d.as_nanos();
    if ns == 0 {
        return "0s".to_owned();
    }
    const UNITS: &[(u128, &str)] = &[
        (3_600_000_000_000, "h"),
        (60_000_000_000, "m"),
        (1_000_000_000, "s"),
        (1_000_000, "ms"),
        (1_000, "us"),
    ];
    for (size, unit) in UNITS {
        if ns.is_multiple_of(*size) {
            return format!("{}{unit}", ns / size);
        }
    }
    format!("{ns}ns")
}

/// A light client-side TraceQL check (Tempo's tag endpoints ignore an invalid query instead of
/// failing): the query needs a span-set filter `{ … }`, balanced braces and parentheses and
/// closed string literals. `VALIDATION` naming `operation` otherwise.
pub fn check_traceql(query: &str, operation: &str) -> Result<()> {
    let fail = |m: &str| {
        Err(Error::validation(format!(
            "{operation}: invalid TraceQL: {m}"
        )))
    };
    let (mut braces, mut parens) = (0i64, 0i64);
    let (mut in_string, mut escaped) = (false, false);
    for c in query.chars() {
        if in_string {
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == '"' {
                in_string = false;
            }
            continue;
        }
        match c {
            '"' => in_string = true,
            '{' => braces += 1,
            '}' => braces -= 1,
            '(' => parens += 1,
            ')' => parens -= 1,
            _ => {}
        }
        if braces < 0 || parens < 0 {
            return fail("unbalanced braces or parentheses");
        }
    }
    if in_string {
        return fail("unterminated string");
    }
    if braces != 0 || parens != 0 {
        return fail("unbalanced braces or parentheses");
    }
    if !query.contains('{') {
        return fail("a span-set filter { ... } is required");
    }
    Ok(())
}
