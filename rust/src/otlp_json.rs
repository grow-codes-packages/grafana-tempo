//! Decoding of the OTLP/JSON trace model that Tempo returns from its trace-by-id endpoints
//! (OpenTelemetry proto JSON, Apache-2.0). No Tempo code is used.
//!
//! Ids are base64 (protobuf JSON bytes) and become lower-case hex; 64-bit timestamps are
//! decimal strings in nanoseconds and become microseconds; enums are names
//! (`SPAN_KIND_SERVER`, `STATUS_CODE_ERROR`).

use serde_json::{Map, Number, Value};

use crate::tracing::{
    AttrValue, Attributes, Span, SpanEvent, SpanKind, SpanLink, SpanStatus, StatusCode, Trace,
};

/// Converts an OTLP `AnyValue` object to a JSON value: string, integer (`intValue` strings),
/// bool, float, array, object (`kvlistValue`); bytes stay base64 strings; empty is `null`.
pub fn from_any_value(value: &Value) -> AttrValue {
    let Some(obj) = value.as_object() else {
        return Value::Null;
    };
    if let Some(v) = obj.get("stringValue") {
        return Value::String(v.as_str().unwrap_or_default().to_owned());
    }
    if let Some(v) = obj.get("intValue") {
        return parse_i64(v).map(Value::from).unwrap_or(Value::Null);
    }
    if let Some(v) = obj.get("boolValue") {
        return Value::Bool(v.as_bool().unwrap_or(false));
    }
    if let Some(v) = obj.get("doubleValue") {
        return v
            .as_f64()
            .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
            .and_then(Number::from_f64)
            .map(Value::Number)
            .unwrap_or(Value::Null);
    }
    if let Some(v) = obj.get("bytesValue") {
        return Value::String(v.as_str().unwrap_or_default().to_owned());
    }
    if let Some(v) = obj.get("arrayValue") {
        return Value::Array(array(v.get("values")).iter().map(from_any_value).collect());
    }
    if let Some(v) = obj.get("kvlistValue") {
        let map: Map<String, Value> = from_key_values(v.get("values")).into_iter().collect();
        return Value::Object(map);
    }
    Value::Null
}

/// Converts an OTLP `KeyValue` list (`[{key, value}]`) to an attribute map.
pub fn from_key_values(list: Option<&Value>) -> Attributes {
    array(list)
        .iter()
        .filter_map(|kv| {
            let key = kv.get("key")?.as_str()?;
            Some((
                key.to_owned(),
                kv.get("value").map(from_any_value).unwrap_or(Value::Null),
            ))
        })
        .collect()
}

fn array(v: Option<&Value>) -> &[Value] {
    v.and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

fn parse_i64(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => {
            let s = s.trim();
            s.parse::<i64>()
                .ok()
                .or_else(|| s.parse::<f64>().ok().map(|f| f as i64))
        }
        _ => None,
    }
}

/// Reads a protobuf-JSON 64-bit integer (decimal string or number); 0 when absent or invalid.
pub(crate) fn int_of(v: Option<&Value>) -> i64 {
    v.and_then(parse_i64).unwrap_or(0)
}

/// Nanoseconds (decimal string or number) to microseconds (integer division; negative → 0).
pub fn nanos_to_micros(v: Option<&Value>) -> u64 {
    u64::try_from(int_of(v) / 1000).unwrap_or(0)
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn base64_decode(s: &str) -> Option<Vec<u8>> {
    let s = s.trim_end_matches('=');
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let mut buf = 0u32;
    let mut bits = 0u32;
    for c in s.bytes() {
        let v = match c {
            b'-' => 62,
            b'_' => 63,
            _ => B64.iter().position(|&b| b == c)? as u32,
        };
        buf = (buf << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
            buf &= (1 << bits) - 1;
        }
    }
    Some(out)
}

/// Converts a protobuf-JSON id (base64, or hex from some gateways) to lower-case hex of `size`
/// bytes; empty when the id is empty or not recognised.
pub fn otlp_id_to_hex(id: &str, size: usize) -> String {
    if id.is_empty() {
        return String::new();
    }
    if let Some(bytes) = base64_decode(id) {
        if bytes.len() == size {
            return bytes.iter().map(|b| format!("{b:02x}")).collect();
        }
    }
    let h = id.to_ascii_lowercase();
    if h.len() <= size * 2 && h.bytes().all(|b| b.is_ascii_hexdigit()) {
        return format!("{h:0>width$}", width = size * 2);
    }
    String::new()
}

fn text(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    }
}

fn status(v: Option<&Value>) -> SpanStatus {
    let code = match text(v.and_then(|s| s.get("code"))).as_str() {
        "STATUS_CODE_OK" | "1" => StatusCode::Ok,
        "STATUS_CODE_ERROR" | "2" => StatusCode::Error,
        _ => StatusCode::Unset,
    };
    let message = text(v.and_then(|s| s.get("message")));
    SpanStatus {
        code,
        message: (!message.is_empty()).then_some(message),
    }
}

fn span_from(trace_id: &str, service: &str, s: &Value) -> Span {
    let start = nanos_to_micros(s.get("startTimeUnixNano"));
    let end = nanos_to_micros(s.get("endTimeUnixNano"));
    let mut tid = otlp_id_to_hex(&text(s.get("traceId")), 16);
    if tid.is_empty() {
        tid = trace_id.to_owned();
    }
    let parent = otlp_id_to_hex(&text(s.get("parentSpanId")), 8);
    Span {
        trace_id: tid,
        span_id: otlp_id_to_hex(&text(s.get("spanId")), 8),
        parent_span_id: (!parent.is_empty()).then_some(parent),
        name: text(s.get("name")),
        kind: SpanKind::parse(&text(s.get("kind"))),
        service_name: service.to_owned(),
        start_micros: start,
        end_micros: end,
        duration_micros: end.saturating_sub(start),
        status: status(s.get("status")),
        attributes: from_key_values(s.get("attributes")),
        events: array(s.get("events"))
            .iter()
            .map(|e| SpanEvent {
                name: text(e.get("name")),
                time_micros: nanos_to_micros(e.get("timeUnixNano")),
                attributes: from_key_values(e.get("attributes")),
            })
            .collect(),
        links: array(s.get("links"))
            .iter()
            .map(|l| SpanLink {
                trace_id: otlp_id_to_hex(&text(l.get("traceId")), 16),
                span_id: otlp_id_to_hex(&text(l.get("spanId")), 8),
                attributes: from_key_values(l.get("attributes")),
            })
            .collect(),
    }
}

/// Flattens OTLP `resourceSpans` (resource → scope → span) into SPEC spans, taking
/// `service.name` from the resource. Spans are not sorted (see [`Trace::new`]).
pub fn spans_from_resource_spans(trace_id: &str, resource_spans: &[Value]) -> Vec<Span> {
    let mut spans = Vec::new();
    for rs in resource_spans {
        let resource = from_key_values(rs.get("resource").and_then(|r| r.get("attributes")));
        let service = resource
            .get("service.name")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned();
        for key in ["scopeSpans", "instrumentationLibrarySpans"] {
            for ss in array(rs.get(key)) {
                for s in array(ss.get("spans")).iter().filter(|s| s.is_object()) {
                    spans.push(span_from(trace_id, &service, s));
                }
            }
        }
    }
    spans
}

/// Normalizes a trace-by-id body (v2 `{"trace": {...}}` or v1 `{"batches": [...]}`) into a
/// [`Trace`] sorted by start time then span id; `None` when it holds no spans.
pub(crate) fn trace_from_body(trace_id: &str, body: &Value) -> Option<Trace> {
    let holder = body.get("trace").unwrap_or(body);
    let mut batches: Vec<Value> = Vec::new();
    for key in ["resourceSpans", "batches"] {
        batches.extend(array(holder.get(key)).iter().cloned());
    }
    let spans = spans_from_resource_spans(trace_id, &batches);
    (!spans.is_empty()).then(|| Trace::new(trace_id, spans))
}
