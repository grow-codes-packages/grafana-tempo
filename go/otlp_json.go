package tempo

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"strings"
)

// This file decodes the OTLP/JSON trace model that Tempo returns from the trace-by-id
// endpoints (OpenTelemetry proto, Apache-2.0). No Tempo code is used.

// otlpTraceV2 is the body of GET /api/v2/traces/{id}.
type otlpTraceV2 struct {
	Trace   otlpTraceV1 `json:"trace"`
	Status  string      `json:"status,omitempty"`
	Message string      `json:"message,omitempty"`
}

// otlpTraceV1 is the body of GET /api/traces/{id} (and the v2 "trace" field).
type otlpTraceV1 struct {
	Batches       []otlpResourceSpans `json:"batches"`
	ResourceSpans []otlpResourceSpans `json:"resourceSpans"`
}

type otlpResourceSpans struct {
	Resource struct {
		Attributes []otlpKeyValue `json:"attributes"`
	} `json:"resource"`
	ScopeSpans                  []otlpScopeSpans `json:"scopeSpans"`
	InstrumentationLibrarySpans []otlpScopeSpans `json:"instrumentationLibrarySpans"`
}

type otlpScopeSpans struct {
	Spans []otlpSpan `json:"spans"`
}

type otlpSpan struct {
	TraceID      string          `json:"traceId"`
	SpanID       string          `json:"spanId"`
	ParentSpanID string          `json:"parentSpanId"`
	Name         string          `json:"name"`
	Kind         json.RawMessage `json:"kind"`
	Start        json.RawMessage `json:"startTimeUnixNano"`
	End          json.RawMessage `json:"endTimeUnixNano"`
	Attributes   []otlpKeyValue  `json:"attributes"`
	Events       []struct {
		Time       json.RawMessage `json:"timeUnixNano"`
		Name       string          `json:"name"`
		Attributes []otlpKeyValue  `json:"attributes"`
	} `json:"events"`
	Links []struct {
		TraceID    string         `json:"traceId"`
		SpanID     string         `json:"spanId"`
		Attributes []otlpKeyValue `json:"attributes"`
	} `json:"links"`
	Status struct {
		Code    json.RawMessage `json:"code"`
		Message string          `json:"message"`
	} `json:"status"`
}

type otlpKeyValue struct {
	Key   string       `json:"key"`
	Value otlpAnyValue `json:"value"`
}

// otlpAnyValue is the OTLP AnyValue (exactly one field is set).
type otlpAnyValue struct {
	StringValue *string         `json:"stringValue"`
	IntValue    json.RawMessage `json:"intValue"`
	BoolValue   *bool           `json:"boolValue"`
	DoubleValue *float64        `json:"doubleValue"`
	BytesValue  *string         `json:"bytesValue"`
	ArrayValue  *struct {
		Values []otlpAnyValue `json:"values"`
	} `json:"arrayValue"`
	KvlistValue *struct {
		Values []otlpKeyValue `json:"values"`
	} `json:"kvlistValue"`
}

// goValue converts an AnyValue: string, int64, bool, float64, []any, map[string]any;
// bytes stay base64 strings; an empty value is nil.
func (v otlpAnyValue) goValue() any {
	switch {
	case v.StringValue != nil:
		return *v.StringValue
	case len(v.IntValue) > 0:
		n, _ := parseInt64(v.IntValue)
		return n
	case v.BoolValue != nil:
		return *v.BoolValue
	case v.DoubleValue != nil:
		return *v.DoubleValue
	case v.BytesValue != nil:
		return *v.BytesValue
	case v.ArrayValue != nil:
		out := make([]any, len(v.ArrayValue.Values))
		for i, e := range v.ArrayValue.Values {
			out[i] = e.goValue()
		}
		return out
	case v.KvlistValue != nil:
		return otlpAttributes(v.KvlistValue.Values)
	}
	return nil
}

func otlpAttributes(kvs []otlpKeyValue) map[string]any {
	out := make(map[string]any, len(kvs))
	for _, kv := range kvs {
		out[kv.Key] = kv.Value.goValue()
	}
	return out
}

// parseInt64 reads a protobuf-JSON 64-bit integer (decimal string or number).
func parseInt64(raw json.RawMessage) (int64, bool) {
	s := strings.Trim(strings.TrimSpace(string(raw)), `"`)
	if s == "" || s == "null" {
		return 0, false
	}
	if n, err := strconv.ParseInt(s, 10, 64); err == nil {
		return n, true
	}
	if f, err := strconv.ParseFloat(s, 64); err == nil {
		return int64(f), true
	}
	return 0, false
}

func nanosToMicros(raw json.RawMessage) int64 {
	n, _ := parseInt64(raw)
	return n / 1000
}

// otlpID converts a protobuf-JSON id (base64, or hex from some gateways) to lower-case
// hex of size bytes; "" when empty or unrecognised.
func otlpID(s string, size int) string {
	if s == "" {
		return ""
	}
	if b, err := base64.StdEncoding.DecodeString(s); err == nil && len(b) == size {
		return hex.EncodeToString(b)
	}
	if b, err := base64.RawStdEncoding.DecodeString(strings.TrimRight(s, "=")); err == nil && len(b) == size {
		return hex.EncodeToString(b)
	}
	h := strings.ToLower(s)
	if _, err := hex.DecodeString(h); err == nil && len(h) <= size*2 {
		return strings.Repeat("0", size*2-len(h)) + h
	}
	return ""
}

var otlpKinds = map[string]SpanKind{
	"SPAN_KIND_INTERNAL": SpanKindInternal, "1": SpanKindInternal,
	"SPAN_KIND_SERVER": SpanKindServer, "2": SpanKindServer,
	"SPAN_KIND_CLIENT": SpanKindClient, "3": SpanKindClient,
	"SPAN_KIND_PRODUCER": SpanKindProducer, "4": SpanKindProducer,
	"SPAN_KIND_CONSUMER": SpanKindConsumer, "5": SpanKindConsumer,
}

func otlpKind(raw json.RawMessage) SpanKind {
	if k, ok := otlpKinds[strings.Trim(string(raw), `" `)]; ok {
		return k
	}
	return SpanKindUnspecified
}

func otlpStatus(raw json.RawMessage, message string) SpanStatus {
	switch strings.Trim(string(raw), `" `) {
	case "STATUS_CODE_OK", "1":
		return SpanStatus{Code: SpanStatusOK, Message: message}
	case "STATUS_CODE_ERROR", "2":
		return SpanStatus{Code: SpanStatusError, Message: message}
	}
	return SpanStatus{Code: SpanStatusUnset, Message: message}
}

// normalizeOTLP flattens resource → scope → span into the SPEC Span model, sorted by
// start time then span id. It returns nil when there are no spans.
func normalizeOTLP(traceID string, batches []otlpResourceSpans) *Trace {
	var spans []Span
	for _, rs := range batches {
		resource := otlpAttributes(rs.Resource.Attributes)
		service, _ := resource["service.name"].(string)
		scopes := append(append([]otlpScopeSpans(nil), rs.ScopeSpans...), rs.InstrumentationLibrarySpans...)
		for _, ss := range scopes {
			for _, s := range ss.Spans {
				spans = append(spans, normalizeOTLPSpan(traceID, service, s))
			}
		}
	}
	if len(spans) == 0 {
		return nil
	}
	return &Trace{TraceID: traceID, Spans: SortSpans(spans)}
}

func normalizeOTLPSpan(traceID, service string, s otlpSpan) Span {
	start, end := nanosToMicros(s.Start), nanosToMicros(s.End)
	out := Span{
		TraceID:        otlpID(s.TraceID, 16),
		SpanID:         otlpID(s.SpanID, 8),
		ParentSpanID:   otlpID(s.ParentSpanID, 8),
		Name:           s.Name,
		Kind:           otlpKind(s.Kind),
		ServiceName:    service,
		StartMicros:    start,
		EndMicros:      end,
		DurationMicros: max(end-start, 0),
		Status:         otlpStatus(s.Status.Code, s.Status.Message),
		Attributes:     otlpAttributes(s.Attributes),
		Events:         make([]SpanEvent, 0, len(s.Events)),
		Links:          make([]SpanLink, 0, len(s.Links)),
	}
	if out.TraceID == "" {
		out.TraceID = traceID
	}
	for _, e := range s.Events {
		out.Events = append(out.Events, SpanEvent{Name: e.Name, TimeMicros: nanosToMicros(e.Time), Attributes: otlpAttributes(e.Attributes)})
	}
	for _, l := range s.Links {
		out.Links = append(out.Links, SpanLink{TraceID: otlpID(l.TraceID, 16), SpanID: otlpID(l.SpanID, 8), Attributes: otlpAttributes(l.Attributes)})
	}
	return out
}
