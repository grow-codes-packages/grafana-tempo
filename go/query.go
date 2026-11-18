package tempo

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// QueryClient reads traces from the Tempo HTTP query API: trace by id, TraceQL search,
// tag names and values, TraceQL metrics and the server probes. It implements
// TraceQueryClient. Get it from Client.Query; it shares the client's transport,
// tenant and lifetime.
type QueryClient struct {
	c *Client
}

var _ TraceQueryClient = (*QueryClient)(nil)

// GetTraceOptions bound a trace-by-id lookup. Tempo treats the range as a hint for
// its backend blocks: recent traces are returned even outside it.
type GetTraceOptions struct {
	// Start and End are sent as Unix seconds when set.
	Start time.Time
	End   time.Time
}

// GetTrace returns the trace with the given hex id (upper case and short ids are
// accepted) or nil when Tempo has no such trace. It uses /api/v2/traces/{id} and falls
// back to /api/traces/{id} when the v2 route is missing.
func (q *QueryClient) GetTrace(ctx context.Context, traceID string) (*Trace, error) {
	return q.GetTraceWith(ctx, traceID, GetTraceOptions{})
}

// GetTraceWith is GetTrace with a time range.
func (q *QueryClient) GetTraceWith(ctx context.Context, traceID string, opts GetTraceOptions) (*Trace, error) {
	const op = "traces.getTrace"
	id, err := NormalizeTraceID(traceID)
	if err != nil {
		return nil, err
	}
	params, err := rangeParams(op, opts.Start, opts.End)
	if err != nil {
		return nil, err
	}
	res, err := q.c.get(ctx, op, "/api/v2/traces/"+id, params)
	if err == nil {
		var body otlpTraceV2
		if err := res.decodeJSON(op, &body); err != nil {
			return nil, err
		}
		return normalizeOTLP(id, append(body.Trace.ResourceSpans, body.Trace.Batches...)), nil
	}
	if CodeOf(err) != ErrorCodeNotFound {
		return nil, err
	}
	// The v2 route answers a miss with 200 {"trace":{}}: a 404 means the route is
	// missing (older Tempo or a gateway), so use the v1 route, where 404 is a miss.
	res, err = q.c.get(ctx, op, "/api/traces/"+id, params)
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return nil, nil
		}
		return nil, err
	}
	var body otlpTraceV1
	if err := res.decodeJSON(op, &body); err != nil {
		return nil, err
	}
	return normalizeOTLP(id, append(body.Batches, body.ResourceSpans...)), nil
}

// SearchRequest is a TraceQL search. Zero values mean the server default.
type SearchRequest struct {
	// TraceQL is the query, for example `{ resource.service.name = "api" }` or the
	// output of the TraceQL builder. Empty searches recent traces without a filter.
	TraceQL string
	// Start and End bound the search (sent as Unix seconds).
	Start time.Time
	End   time.Time
	// Limit caps the number of traces (server default 20).
	Limit int
	// SpansPerSpanSet caps the spans returned per span set (spss, server default 3).
	SpansPerSpanSet int
}

// SearchResult is the answer of Search.
type SearchResult struct {
	Traces  []SearchTrace `json:"traces"`
	Metrics SearchMetrics `json:"metrics"`
}

// SearchTrace is one trace found by Search.
type SearchTrace struct {
	// TraceID is 32 lower-case hex characters (Tempo trims leading zeros; they are restored).
	TraceID         string                  `json:"traceId"`
	RootServiceName string                  `json:"rootServiceName"`
	RootTraceName   string                  `json:"rootTraceName"`
	Start           time.Time               `json:"start"`
	Duration        time.Duration           `json:"duration"`
	SpanSets        []SpanSet               `json:"spanSets"`
	ServiceStats    map[string]ServiceStats `json:"serviceStats"`
}

// SpanSet is a set of matching spans of one trace. Matched is the full match count,
// Spans holds at most SpansPerSpanSet of them.
type SpanSet struct {
	Spans   []SpanSetSpan `json:"spans"`
	Matched int           `json:"matched"`
}

// SpanSetSpan is a span inside a SpanSet. Name is empty unless the query selects it
// (`| select(name)`); Attributes hold service.name plus the selected attributes.
type SpanSetSpan struct {
	SpanID     string         `json:"spanId"`
	Name       string         `json:"name,omitempty"`
	Start      time.Time      `json:"start"`
	Duration   time.Duration  `json:"duration"`
	Attributes map[string]any `json:"attributes"`
}

// ServiceStats counts the spans of one service in a SearchTrace.
type ServiceStats struct {
	SpanCount  int `json:"spanCount"`
	ErrorCount int `json:"errorCount"`
}

// SearchMetrics is Tempo's work report for a search.
type SearchMetrics struct {
	InspectedTraces int64 `json:"inspectedTraces"`
	InspectedBytes  int64 `json:"inspectedBytes"`
	CompletedJobs   int64 `json:"completedJobs"`
	TotalJobs       int64 `json:"totalJobs"`
}

type wireSearch struct {
	Traces []struct {
		TraceID         string          `json:"traceID"`
		RootServiceName string          `json:"rootServiceName"`
		RootTraceName   string          `json:"rootTraceName"`
		Start           json.RawMessage `json:"startTimeUnixNano"`
		DurationMs      json.RawMessage `json:"durationMs"`
		SpanSet         *wireSpanSet    `json:"spanSet"`
		SpanSets        []wireSpanSet   `json:"spanSets"`
		ServiceStats    map[string]struct {
			SpanCount  int `json:"spanCount"`
			ErrorCount int `json:"errorCount"`
		} `json:"serviceStats"`
	} `json:"traces"`
	Metrics struct {
		InspectedTraces json.RawMessage `json:"inspectedTraces"`
		InspectedBytes  json.RawMessage `json:"inspectedBytes"`
		CompletedJobs   json.RawMessage `json:"completedJobs"`
		TotalJobs       json.RawMessage `json:"totalJobs"`
	} `json:"metrics"`
}

type wireSpanSet struct {
	Spans []struct {
		SpanID        string          `json:"spanID"`
		Name          string          `json:"name"`
		Start         json.RawMessage `json:"startTimeUnixNano"`
		DurationNanos json.RawMessage `json:"durationNanos"`
		Attributes    []otlpKeyValue  `json:"attributes"`
	} `json:"spans"`
	Matched int `json:"matched"`
}

func int64Of(raw json.RawMessage) int64 {
	n, _ := parseInt64(raw)
	return n
}

// Search runs a TraceQL search (GET /api/search). Search results appear about 30 s
// after ingest (trace by id is immediate).
func (q *QueryClient) Search(ctx context.Context, req SearchRequest) (*SearchResult, error) {
	const op = "traces.search"
	if req.Limit < 0 || req.SpansPerSpanSet < 0 {
		return nil, ValidationError(op, "limit and spansPerSpanSet must not be negative")
	}
	params, err := rangeParams(op, req.Start, req.End)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(req.TraceQL) != "" {
		params.Set("q", req.TraceQL)
	}
	if req.Limit > 0 {
		params.Set("limit", strconv.Itoa(req.Limit))
	}
	if req.SpansPerSpanSet > 0 {
		params.Set("spss", strconv.Itoa(req.SpansPerSpanSet))
	}
	res, err := q.c.get(ctx, op, "/api/search", params)
	if err != nil {
		return nil, err
	}
	var w wireSearch
	if err := res.decodeJSON(op, &w); err != nil {
		return nil, err
	}
	out := &SearchResult{
		Traces: make([]SearchTrace, 0, len(w.Traces)),
		Metrics: SearchMetrics{
			InspectedTraces: int64Of(w.Metrics.InspectedTraces), InspectedBytes: int64Of(w.Metrics.InspectedBytes),
			CompletedJobs: int64Of(w.Metrics.CompletedJobs), TotalJobs: int64Of(w.Metrics.TotalJobs),
		},
	}
	for _, t := range w.Traces {
		st := SearchTrace{
			TraceID:         padHex(t.TraceID, 32),
			RootServiceName: t.RootServiceName,
			RootTraceName:   t.RootTraceName,
			Start:           time.Unix(0, int64Of(t.Start)).UTC(),
			Duration:        time.Duration(int64Of(t.DurationMs)) * time.Millisecond,
			SpanSets:        make([]SpanSet, 0, len(t.SpanSets)),
			ServiceStats:    make(map[string]ServiceStats, len(t.ServiceStats)),
		}
		sets := t.SpanSets
		if len(sets) == 0 && t.SpanSet != nil {
			sets = []wireSpanSet{*t.SpanSet}
		}
		for _, ws := range sets {
			set := SpanSet{Matched: ws.Matched, Spans: make([]SpanSetSpan, 0, len(ws.Spans))}
			for _, s := range ws.Spans {
				set.Spans = append(set.Spans, SpanSetSpan{
					SpanID:     padHex(s.SpanID, 16),
					Name:       s.Name,
					Start:      time.Unix(0, int64Of(s.Start)).UTC(),
					Duration:   time.Duration(int64Of(s.DurationNanos)),
					Attributes: otlpAttributes(s.Attributes),
				})
			}
			st.SpanSets = append(st.SpanSets, set)
		}
		for svc, s := range t.ServiceStats {
			st.ServiceStats[svc] = ServiceStats(s)
		}
		out.Traces = append(out.Traces, st)
	}
	return out, nil
}

// TagScope is a TraceQL attribute scope for SearchTags.
type TagScope string

// Tag scopes. The empty scope means all scopes.
const (
	TagScopeAll             TagScope = ""
	TagScopeResource        TagScope = "resource"
	TagScopeSpan            TagScope = "span"
	TagScopeIntrinsic       TagScope = "intrinsic"
	TagScopeEvent           TagScope = "event"
	TagScopeLink            TagScope = "link"
	TagScopeInstrumentation TagScope = "instrumentation"
)

// SearchTagsOptions narrow SearchTags. Zero values mean "all".
type SearchTagsOptions struct {
	// Scope limits the answer to one scope.
	Scope TagScope
	// Query is TraceQL; only tags present on matching spans are returned.
	Query string
	// Start and End bound the data that is inspected.
	Start time.Time
	End   time.Time
}

// SearchTags lists attribute names per scope (GET /api/v2/search/tags), for example
// {"resource": ["service.name"], "span": ["http.request.method"]}. Scopes without
// data are absent.
func (q *QueryClient) SearchTags(ctx context.Context, opts SearchTagsOptions) (map[string][]string, error) {
	const op = "traces.searchTags"
	switch opts.Scope {
	case TagScopeAll, TagScopeResource, TagScopeSpan, TagScopeIntrinsic, TagScopeEvent, TagScopeLink, TagScopeInstrumentation:
	default:
		return nil, ValidationError(op, "unknown scope "+strconv.Quote(string(opts.Scope)))
	}
	params, err := rangeParams(op, opts.Start, opts.End)
	if err != nil {
		return nil, err
	}
	if opts.Scope != TagScopeAll {
		params.Set("scope", string(opts.Scope))
	}
	if err := setTraceQLParam(op, params, opts.Query); err != nil {
		return nil, err
	}
	res, err := q.c.get(ctx, op, "/api/v2/search/tags", params)
	if err != nil {
		return nil, err
	}
	var body struct {
		Scopes []struct {
			Name string   `json:"name"`
			Tags []string `json:"tags"`
		} `json:"scopes"`
	}
	if err := res.decodeJSON(op, &body); err != nil {
		return nil, err
	}
	out := make(map[string][]string, len(body.Scopes))
	for _, s := range body.Scopes {
		out[s.Name] = append(out[s.Name], s.Tags...)
	}
	return out, nil
}

// TagValue is one value of a tag. Value is always a string; Type ("string", "int",
// "float", "bool", "duration", "keyword", ...) tells how to read it.
type TagValue struct {
	Type  string `json:"type"`
	Value string `json:"value"`
}

// SearchTagValuesOptions narrow SearchTagValues.
type SearchTagValuesOptions struct {
	// Query is TraceQL; only values on matching spans are returned. Tempo ignores an
	// invalid query, so it is checked client side (VALIDATION error).
	Query string
	// Start and End bound the data that is inspected.
	Start time.Time
	End   time.Time
}

// SearchTagValues lists the values of a tag (GET /api/v2/search/tag/{tag}/values). tag
// is a TraceQL identifier: "resource.service.name", "span.http.response.status_code",
// an intrinsic ("name", "status") or a quoted name (`span."my key"`). Unscoped tags
// ("service.name") are a VALIDATION error from Tempo; an unknown tag gives no values.
func (q *QueryClient) SearchTagValues(ctx context.Context, tag string, opts SearchTagValuesOptions) ([]TagValue, error) {
	const op = "traces.searchTagValues"
	tag = strings.TrimSpace(tag)
	if tag == "" || strings.Contains(tag, "/") {
		return nil, ValidationError(op, "tag must be a non-empty TraceQL identifier without '/'")
	}
	params, err := rangeParams(op, opts.Start, opts.End)
	if err != nil {
		return nil, err
	}
	if err := setTraceQLParam(op, params, opts.Query); err != nil {
		return nil, err
	}
	res, err := q.c.get(ctx, op, "/api/v2/search/tag/"+tag+"/values", params)
	if err != nil {
		return nil, err
	}
	var body struct {
		TagValues []TagValue `json:"tagValues"`
	}
	if err := res.decodeJSON(op, &body); err != nil {
		return nil, err
	}
	if body.TagValues == nil {
		return []TagValue{}, nil
	}
	return body.TagValues, nil
}

// MetricsRange bounds a TraceQL metrics query. Set Start/End or Since (the last
// Since until now); zero values mean the server default.
type MetricsRange struct {
	Start time.Time
	End   time.Time
	// Since is a relative range such as 15 * time.Minute (ignored when Start is set).
	Since time.Duration
	// Step is the sample interval (server default depends on the range).
	Step time.Duration
	// Exemplars is the maximum number of exemplars per series (0 = server default).
	Exemplars int
}

// MetricsResult is the answer of TraceQLMetrics.
type MetricsResult struct {
	Series []MetricSeries `json:"series"`
	// Step is the sample interval the server used.
	Step time.Duration `json:"step"`
}

// MetricSeries is one time series. Labels hold typed values (string, int64, float64,
// bool), for example {"__name__": "count_over_time"} or {"resource.service.name": "api"}.
type MetricSeries struct {
	Labels    map[string]any   `json:"labels"`
	Samples   []MetricSample   `json:"samples"`
	Exemplars []MetricExemplar `json:"exemplars"`
}

// MetricSample is one sample (Tempo omits zero values; they read as 0).
type MetricSample struct {
	Timestamp time.Time `json:"timestamp"`
	Value     float64   `json:"value"`
}

// MetricExemplar links a sample to a trace (label "trace:id").
type MetricExemplar struct {
	Labels    map[string]any `json:"labels"`
	Value     float64        `json:"value"`
	Timestamp time.Time      `json:"timestamp"`
}

// TraceQLMetrics runs a TraceQL metrics query over a range (GET
// /api/metrics/query_range), for example
// `{ resource.service.name = "api" } | rate() by (span.http.route)`.
func (q *QueryClient) TraceQLMetrics(ctx context.Context, query string, r MetricsRange) (*MetricsResult, error) {
	const op = "traces.traceQLMetrics"
	if strings.TrimSpace(query) == "" {
		return nil, ValidationError(op, "query is required")
	}
	if r.Since < 0 || r.Step < 0 || r.Exemplars < 0 {
		return nil, ValidationError(op, "since, step and exemplars must not be negative")
	}
	params, err := rangeParams(op, r.Start, r.End)
	if err != nil {
		return nil, err
	}
	params.Set("q", query)
	if r.Start.IsZero() && r.Since > 0 {
		params.Set("since", traceQLDuration(r.Since))
	}
	if r.Step > 0 {
		params.Set("step", traceQLDuration(r.Step))
	}
	if r.Exemplars > 0 {
		params.Set("exemplars", strconv.Itoa(r.Exemplars))
	}
	res, err := q.c.get(ctx, op, "/api/metrics/query_range", params)
	if err != nil {
		return nil, err
	}
	var body struct {
		Series []struct {
			Labels  []otlpKeyValue `json:"labels"`
			Samples []struct {
				TimestampMs json.RawMessage `json:"timestampMs"`
				Value       float64         `json:"value"`
			} `json:"samples"`
			Exemplars []struct {
				Labels      []otlpKeyValue  `json:"labels"`
				Value       float64         `json:"value"`
				TimestampMs json.RawMessage `json:"timestampMs"`
			} `json:"exemplars"`
		} `json:"series"`
		Step json.RawMessage `json:"step"`
	}
	if err := res.decodeJSON(op, &body); err != nil {
		return nil, err
	}
	out := &MetricsResult{Series: make([]MetricSeries, 0, len(body.Series)), Step: time.Duration(int64Of(body.Step))}
	for _, s := range body.Series {
		ms := MetricSeries{
			Labels:    otlpAttributes(s.Labels),
			Samples:   make([]MetricSample, 0, len(s.Samples)),
			Exemplars: make([]MetricExemplar, 0, len(s.Exemplars)),
		}
		for _, p := range s.Samples {
			ms.Samples = append(ms.Samples, MetricSample{Timestamp: time.UnixMilli(int64Of(p.TimestampMs)).UTC(), Value: p.Value})
		}
		for _, e := range s.Exemplars {
			ms.Exemplars = append(ms.Exemplars, MetricExemplar{
				Labels: otlpAttributes(e.Labels), Value: e.Value, Timestamp: time.UnixMilli(int64Of(e.TimestampMs)).UTC(),
			})
		}
		out.Series = append(out.Series, ms)
	}
	return out, nil
}

// Echo calls GET /api/echo and returns its body ("echo"). It needs no tenant.
func (q *QueryClient) Echo(ctx context.Context) (string, error) {
	res, err := q.c.get(ctx, "traces.echo", "/api/echo", nil)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(res.Body)), nil
}

// Ready reports whether Tempo answers GET /ready with "ready" (false while it starts
// and answers 503). Unlike Client.Health it returns network and other HTTP errors.
func (q *QueryClient) Ready(ctx context.Context) (bool, error) {
	const op = "traces.ready"
	res, err := q.c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/ready", Operation: op, RawStatus: true, NoRetry: true})
	if err != nil {
		return false, err
	}
	switch res.Status {
	case http.StatusOK:
		return strings.TrimSpace(string(res.Body)) == "ready", nil
	case http.StatusServiceUnavailable:
		return false, nil
	}
	return false, StatusError(op, res.Status, res.RequestID, vendorErrorText(res), q.c.transport.knownSecrets())
}

// BuildInfo describes the Tempo build (GET /api/status/buildinfo). Unset fields are
// empty strings.
type BuildInfo struct {
	Version   string `json:"version"`
	Revision  string `json:"revision"`
	Branch    string `json:"branch"`
	BuildUser string `json:"buildUser"`
	BuildDate string `json:"buildDate"`
	GoVersion string `json:"goVersion"`
}

// BuildInfo returns the Tempo build information.
func (q *QueryClient) BuildInfo(ctx context.Context) (BuildInfo, error) {
	const op = "traces.buildInfo"
	res, err := q.c.get(ctx, op, "/api/status/buildinfo", nil)
	if err != nil {
		return BuildInfo{}, err
	}
	var info BuildInfo
	if err := res.decodeJSON(op, &info); err != nil {
		return BuildInfo{}, err
	}
	return info, nil
}

// SearchTraces implements TraceQueryClient: the query becomes one TraceQL span-set
// filter (service → resource.service.name, operation → name, tags → unscoped
// attributes, durations → trace:duration) sent to Search. DurationMicros has
// millisecond precision and SpanCount sums the per-service span counts.
func (q *QueryClient) SearchTraces(ctx context.Context, query TraceSearchQuery) ([]TraceSummary, error) {
	query = query.WithDefaults()
	traceQL, err := searchTracesTraceQL(query)
	if err != nil {
		return nil, err
	}
	res, err := q.Search(ctx, SearchRequest{TraceQL: traceQL, Start: query.Start, End: query.End, Limit: query.Limit})
	if err != nil {
		return nil, err
	}
	out := make([]TraceSummary, 0, len(res.Traces))
	for _, t := range res.Traces {
		count := 0
		for _, s := range t.ServiceStats {
			count += s.SpanCount
		}
		out = append(out, TraceSummary{
			TraceID:         t.TraceID,
			RootServiceName: t.RootServiceName,
			RootName:        t.RootTraceName,
			StartMicros:     t.Start.UnixMicro(),
			DurationMicros:  t.Duration.Microseconds(),
			SpanCount:       count,
		})
	}
	return out, nil
}

// searchTracesTraceQL maps a TraceSearchQuery to a TraceQL span-set filter.
func searchTracesTraceQL(query TraceSearchQuery) (string, error) {
	const op = "traces.searchTraces"
	if query.MinDuration < 0 || query.MaxDuration < 0 {
		return "", ValidationError(op, "durations must not be negative")
	}
	b := TraceQL.Span(nil)
	if query.Service != "" {
		b = b.And("resource.service.name", "=", query.Service)
	}
	if query.Operation != "" {
		b = b.And("name", "=", query.Operation)
	}
	keys := make([]string, 0, len(query.Tags))
	for k := range query.Tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		b = b.And("."+k, "=", query.Tags[k])
	}
	if query.MinDuration > 0 {
		b = b.And("trace:duration", ">=", rawTraceQL(strconv.FormatInt(query.MinDuration.Microseconds(), 10)+"us"))
	}
	if query.MaxDuration > 0 {
		b = b.And("trace:duration", "<=", rawTraceQL(strconv.FormatInt(query.MaxDuration.Microseconds(), 10)+"us"))
	}
	return b.Build()
}

// ListServices implements TraceQueryClient: the sorted values of
// resource.service.name in recent data.
func (q *QueryClient) ListServices(ctx context.Context) ([]string, error) {
	vals, err := q.SearchTagValues(ctx, "resource.service.name", SearchTagValuesOptions{})
	if err != nil {
		return nil, err
	}
	return sortedValues(vals), nil
}

// ListOperations implements TraceQueryClient: the sorted span names of one service.
func (q *QueryClient) ListOperations(ctx context.Context, service string) ([]string, error) {
	if strings.TrimSpace(service) == "" {
		return nil, ValidationError("traces.listOperations", "service is required")
	}
	filter, err := TraceQL.Span(map[string]any{"resource.service.name": service}).Build()
	if err != nil {
		return nil, err
	}
	vals, err := q.SearchTagValues(ctx, "name", SearchTagValuesOptions{Query: filter})
	if err != nil {
		return nil, err
	}
	return sortedValues(vals), nil
}

// GetDependencies implements TraceQueryClient. Tempo has no dependency API (service
// graphs need the metrics-generator and a Prometheus backend), so it is always
// UNSUPPORTED.
func (q *QueryClient) GetDependencies(context.Context, DependencyOptions) ([]DependencyLink, error) {
	return nil, UnsupportedError("traces.getDependencies", "Grafana Tempo has no dependency graph API")
}

// WaitForTrace implements TraceQueryClient: it polls GetTrace until the trace has at
// least opts.MinSpans spans (Tempo serves traces by id right after ingest).
func (q *QueryClient) WaitForTrace(ctx context.Context, traceID string, opts WaitForTraceOptions) (*Trace, error) {
	return WaitForTrace(ctx, q, traceID, opts)
}

func sortedValues(vals []TagValue) []string {
	seen := make(map[string]bool, len(vals))
	out := make([]string, 0, len(vals))
	for _, v := range vals {
		if !seen[v.Value] {
			seen[v.Value] = true
			out = append(out, v.Value)
		}
	}
	sort.Strings(out)
	return out
}

// rangeParams encodes start/end as Unix seconds (end rounded up) and checks the order.
func rangeParams(op string, start, end time.Time) (url.Values, error) {
	params := url.Values{}
	if !start.IsZero() && !end.IsZero() && end.Before(start) {
		return nil, ValidationError(op, "end must not be before start")
	}
	if !start.IsZero() {
		params.Set("start", strconv.FormatInt(start.Unix(), 10))
	}
	if !end.IsZero() {
		s := end.Unix()
		if end.Nanosecond() > 0 {
			s++
		}
		params.Set("end", strconv.FormatInt(s, 10))
	}
	return params, nil
}

// setTraceQLParam checks a TraceQL filter and sets it as q.
func setTraceQLParam(op string, params url.Values, query string) error {
	if strings.TrimSpace(query) == "" {
		return nil
	}
	if err := checkTraceQL(query); err != nil {
		return ValidationError(op, err.Error())
	}
	params.Set("q", query)
	return nil
}

// padHex lower-cases a hex id and restores trimmed leading zeros.
func padHex(id string, size int) string {
	id = strings.ToLower(id)
	if len(id) < size {
		id = strings.Repeat("0", size-len(id)) + id
	}
	return id
}
