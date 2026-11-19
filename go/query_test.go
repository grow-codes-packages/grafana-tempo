package tempo

import (
	"context"
	"errors"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestGetTrace(t *testing.T) {
	const id = "00f74bc1b058a1a8b85f9ae0231e4001"

	t.Run("v2 normalizes the OTLP JSON", func(t *testing.T) {
		ts := fixtureServer(t, "getTrace.v2-ok", "getTrace.seed-v2")
		q := newTempo(t, ts.URL, nil).Query()
		tr, err := q.GetTrace(t.Context(), "F74BC1B058A1A8B85F9AE0231E4001") // upper case, trimmed
		if err != nil || tr == nil {
			t.Fatalf("trace %v %v", tr, err)
		}
		if tr.TraceID != id || len(tr.Spans) != 4 || ts.request(0).URL.Path != "/api/v2/traces/"+id {
			t.Fatalf("trace %+v", tr)
		}
		root := tr.Spans[0]
		if root.SpanID != "a000000000000001" || root.ParentSpanID != "" || root.Kind != SpanKindServer ||
			root.Status.Code != SpanStatusOK || root.DurationMicros != 450000 || root.StartMicros != 1795035005729668 ||
			root.ServiceName != "grow-sdk-verify-179503498127460-api" || root.TraceID != id {
			t.Fatalf("root %+v", root)
		}
		if root.Attributes["http.response.status_code"] != int64(200) || root.Attributes["grow.note"] != "a \"quoted\" \\ value ü" ||
			root.Attributes["grow weird.key"] != "x y" || len(root.Events) != 1 || root.Events[0].Name != "cache-miss" {
			t.Fatalf("root attrs %+v", root)
		}
		if tr.Spans[2].ServiceName != "grow-sdk-verify-179503498127460-db" || tr.Spans[2].ParentSpanID != "a000000000000002" {
			t.Fatalf("sorted spans %+v", tr.Spans[2])
		}
		tree := tr.Tree()
		if len(tree) != 1 || len(tree[0].Children) != 2 {
			t.Fatalf("tree %+v", tree)
		}

		seed, err := q.GetTrace(t.Context(), "5b8efff798038103d269b633813fc60c")
		if err != nil || len(seed.Spans) != 3 {
			t.Fatalf("seed %v %v", seed, err)
		}
		link := seed.Spans[1].Links
		if seed.Spans[1].Kind != SpanKindClient || len(link) != 1 || link[0].TraceID != "0af7651916cd43dd8448eb211c80319c" ||
			link[0].SpanID != "2000000000000001" || link[0].Attributes["link.kind"] != "seed" {
			t.Fatalf("link %+v", seed.Spans[1])
		}
		if s := seed.Spans[2]; s.Status.Code != SpanStatusError || s.Status.Message != "seed failure" {
			t.Fatalf("error status %+v", s.Status)
		}
		if seed.Spans[0].Attributes["grow.seed"] != true || seed.Spans[0].Events[0].Attributes["n"] != int64(1) {
			t.Fatalf("typed attributes %+v", seed.Spans[0])
		}
	})

	t.Run("range params", func(t *testing.T) {
		ts := fixtureServer(t, "getTrace.v2-ok")
		q := newTempo(t, ts.URL, nil).Query()
		start := time.Unix(1795034947, 0)
		end := time.Unix(1795035067, 500)
		if _, err := q.GetTraceWith(t.Context(), id, GetTraceOptions{Start: start, End: end}); err != nil {
			t.Fatal(err)
		}
		if v := ts.request(0).URL.Query(); v.Get("start") != "1795034947" || v.Get("end") != "1795035068" {
			t.Fatalf("query %v", v)
		}
		if _, err := q.GetTraceWith(t.Context(), id, GetTraceOptions{Start: end, End: start}); !errors.Is(err, ErrValidation) {
			t.Fatalf("end before start: %v", err)
		}
	})

	t.Run("v2 miss is nil", func(t *testing.T) {
		ts := fixtureServer(t, "getTrace.v2-not-found")
		tr, err := newTempo(t, ts.URL, nil).Query().GetTrace(t.Context(), "1")
		if err != nil || tr != nil || ts.calls.Load() != 1 {
			t.Fatalf("miss %v %v", tr, err)
		}
	})

	t.Run("v1 fallback when the v2 route is missing", func(t *testing.T) {
		ts := fixtureServer(t, "getTrace.v1-ok")
		tr, err := newTempo(t, ts.URL, nil).Query().GetTrace(t.Context(), id)
		if err != nil || tr == nil || len(tr.Spans) != 4 || tr.Spans[0].SpanID != "a000000000000001" {
			t.Fatalf("v1 %+v %v", tr, err)
		}
		if ts.request(0).URL.Path != "/api/v2/traces/"+id || ts.request(1).URL.Path != "/api/traces/"+id {
			t.Fatal("expected v2 then v1")
		}
		miss := fixtureServer(t, "getTrace.v1-not-found")
		tr, err = newTempo(t, miss.URL, nil).Query().GetTrace(t.Context(), "00000000000000000000000000000001")
		if err != nil || tr != nil {
			t.Fatalf("v1 miss %v %v", tr, err)
		}
	})

	t.Run("errors", func(t *testing.T) {
		q := newTempo(t, "http://127.0.0.1:1", nil).Query()
		for _, bad := range []string{"", "nothex", strings.Repeat("a", 33), "0000"} {
			if _, err := q.GetTrace(t.Context(), bad); !errors.Is(err, ErrValidation) {
				t.Errorf("%q: %v", bad, err)
			}
		}
		mt := fixtureServer(t, "getTrace.mt-no-tenant-401")
		mtID := loadFixture(t, "getTrace.mt-no-tenant-401").Request.Path[len("/api/v2/traces/"):]
		if _, err := newTempo(t, mt.URL, nil).Query().GetTrace(t.Context(), mtID); !errors.Is(err, ErrAuthentication) ||
			!strings.Contains(err.Error(), "tenant required") {
			t.Fatalf("mt: %v", err)
		}
		v1err := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if strings.HasPrefix(r.URL.Path, "/api/v2/") {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			w.WriteHeader(http.StatusForbidden)
		})
		if _, err := newTempo(t, v1err.URL, nil).Query().GetTrace(t.Context(), id); !errors.Is(err, ErrPermissionDenied) {
			t.Fatalf("v1 403: %v", err)
		}
		garbage := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if strings.HasPrefix(r.URL.Path, "/api/v2/") && r.URL.Query().Get("v1") == "" {
				_, _ = w.Write([]byte("not json"))
				return
			}
		})
		if _, err := newTempo(t, garbage.URL, nil).Query().GetTrace(t.Context(), id); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("v2 garbage: %v", err)
		}
		v1garbage := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if strings.HasPrefix(r.URL.Path, "/api/v2/") {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			_, _ = w.Write([]byte("not json"))
		})
		if _, err := newTempo(t, v1garbage.URL, nil).Query().GetTrace(t.Context(), id); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("v1 garbage: %v", err)
		}
	})
}

func TestOTLPDecoding(t *testing.T) {
	if got := otlpID("", 8); got != "" {
		t.Fatal(got)
	}
	if got := otlpID("A000000000000001", 8); got != "a000000000000001" {
		t.Fatalf("hex id: %s", got)
	}
	if got := otlpID("oAAAAAAAAAE", 8); got != "a000000000000001" {
		t.Fatalf("unpadded base64: %s", got)
	}
	if got := otlpID("!!", 8); got != "" {
		t.Fatalf("garbage: %s", got)
	}
	if otlpKind([]byte("4")) != SpanKindProducer || otlpKind([]byte(`"SPAN_KIND_CONSUMER"`)) != SpanKindConsumer ||
		otlpKind(nil) != SpanKindUnspecified {
		t.Fatal("kinds")
	}
	if otlpStatus([]byte("2"), "x").Code != SpanStatusError || otlpStatus([]byte("1"), "").Code != SpanStatusOK {
		t.Fatal("status")
	}
	if n, ok := parseInt64([]byte(`1.5e3`)); !ok || n != 1500 {
		t.Fatalf("float: %d", n)
	}
	if _, ok := parseInt64([]byte(`"x"`)); ok {
		t.Fatal("garbage int")
	}
	if _, ok := parseInt64([]byte(`null`)); ok {
		t.Fatal("null int")
	}
	s, d, b, i := "s", 1.5, true, []byte(`"7"`)
	bytesVal := "AQI="
	v := otlpAnyValue{ArrayValue: &struct {
		Values []otlpAnyValue `json:"values"`
	}{Values: []otlpAnyValue{{StringValue: &s}, {DoubleValue: &d}, {BoolValue: &b}, {IntValue: i}, {BytesValue: &bytesVal}, {}}}}
	want := []any{"s", 1.5, true, int64(7), "AQI=", nil}
	if got := v.goValue(); !reflect.DeepEqual(got, want) {
		t.Fatalf("array %#v", got)
	}
	kv := otlpAnyValue{KvlistValue: &struct {
		Values []otlpKeyValue `json:"values"`
	}{Values: []otlpKeyValue{{Key: "k", Value: otlpAnyValue{StringValue: &s}}}}}
	if got := kv.goValue(); !reflect.DeepEqual(got, map[string]any{"k": "s"}) {
		t.Fatalf("kvlist %#v", got)
	}
	// Legacy instrumentationLibrarySpans and a missing span trace id.
	tr := normalizeOTLP("0000000000000000000000000000000a", []otlpResourceSpans{{
		InstrumentationLibrarySpans: []otlpScopeSpans{{Spans: []otlpSpan{{SpanID: "AAAAAAAAAAE=", Name: "x"}}}},
	}})
	if tr == nil || tr.Spans[0].TraceID != "0000000000000000000000000000000a" || tr.Spans[0].SpanID != "0000000000000001" {
		t.Fatalf("legacy %+v", tr)
	}
}

func TestSearch(t *testing.T) {
	t.Run("traceql search", func(t *testing.T) {
		ts := fixtureServer(t, "search.traceql-service")
		q := newTempo(t, ts.URL, func(c *Config) { c.TenantID = "tenant-a" }).Query()
		f := loadFixture(t, "search.traceql-service")
		res, err := q.Search(t.Context(), SearchRequest{
			TraceQL: f.Request.Query["q"], Start: time.Unix(1795034947, 0), End: time.Unix(1795035067, 0), Limit: 20, SpansPerSpanSet: 3,
		})
		if err != nil {
			t.Fatal(err)
		}
		v := ts.request(0).URL.Query()
		for k, want := range f.Request.Query {
			if v.Get(k) != want {
				t.Errorf("param %s = %q, want %q", k, v.Get(k), want)
			}
		}
		if !strings.Contains(ts.request(0).URL.RawQuery, "%20") && !strings.Contains(ts.request(0).URL.RawQuery, "+") {
			t.Fatalf("q not encoded: %s", ts.request(0).URL.RawQuery)
		}
		if len(res.Traces) < 2 {
			t.Fatalf("traces %+v", res)
		}
		first := res.Traces[0]
		if first.TraceID != "e9b7d20a606bce1c1c3f347ef020c799" || first.RootTraceName != "GET /health" || first.Duration != 5*time.Millisecond ||
			first.Start.UnixNano() != 1795035006729668338 || len(first.SpanSets) != 1 || first.SpanSets[0].Matched != 1 ||
			first.SpanSets[0].Spans[0].SpanID != "b000000000000001" || first.SpanSets[0].Spans[0].Duration != 5*time.Millisecond ||
			first.ServiceStats["grow-sdk-verify-179503498127460-api"].ErrorCount != 1 {
			t.Fatalf("first %+v", first)
		}
		if res.Traces[1].TraceID != "00f74bc1b058a1a8b85f9ae0231e4001" {
			t.Fatalf("padded id %s", res.Traces[1].TraceID)
		}
		if res.Metrics.InspectedBytes <= 0 {
			t.Fatalf("metrics %+v", res.Metrics)
		}
	})

	t.Run("select returns names and legacy spanSet", func(t *testing.T) {
		ts := fixtureServer(t, "search.select-name-status")
		res, err := newTempo(t, ts.URL, nil).Query().Search(t.Context(), SearchRequest{})
		if err != nil || len(res.Traces) == 0 || res.Traces[0].SpanSets[0].Spans[0].Name == "" {
			t.Fatalf("select %+v %v", res, err)
		}
		if raw := ts.request(0).URL.RawQuery; raw != "" {
			t.Fatalf("empty request sent params: %s", raw)
		}
		legacy := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			_, _ = w.Write([]byte(`{"traces":[{"traceID":"1","spanSet":{"spans":[{"spanID":"1"}],"matched":1}}],"metrics":{}}`))
		})
		res, err = newTempo(t, legacy.URL, nil).Query().Search(t.Context(), SearchRequest{})
		if err != nil || len(res.Traces[0].SpanSets) != 1 || res.Traces[0].SpanSets[0].Spans[0].SpanID != "0000000000000001" {
			t.Fatalf("legacy %+v %v", res, err)
		}
	})

	t.Run("errors", func(t *testing.T) {
		bad := fixtureServer(t, "search.invalid-traceql-400")
		q := newTempo(t, bad.URL, nil).Query()
		_, err := q.Search(t.Context(), SearchRequest{TraceQL: "{ bad"})
		if !errors.Is(err, ErrValidation) || !strings.Contains(err.Error(), "invalid TraceQL query") {
			t.Fatalf("400: %v", err)
		}
		if _, err := q.Search(t.Context(), SearchRequest{Limit: -1}); !errors.Is(err, ErrValidation) {
			t.Fatalf("limit: %v", err)
		}
		if _, err := q.Search(t.Context(), SearchRequest{Start: time.Unix(10, 0), End: time.Unix(5, 0)}); !errors.Is(err, ErrValidation) {
			t.Fatalf("range: %v", err)
		}
		garbage := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("x")) })
		if _, err := newTempo(t, garbage.URL, nil).Query().Search(t.Context(), SearchRequest{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("garbage: %v", err)
		}
	})
}

func TestTags(t *testing.T) {
	t.Run("search tags", func(t *testing.T) {
		ts := fixtureServer(t, "searchTags.v2-all-scopes")
		q := newTempo(t, ts.URL, nil).Query()
		tags, err := q.SearchTags(t.Context(), SearchTagsOptions{
			Scope: TagScopeSpan, Query: `{ resource.service.name = "api" }`, Start: time.Unix(1, 0), End: time.Unix(2, 0),
		})
		if err != nil || len(tags["resource"]) != 3 || tags["link"][0] != "link.kind" {
			t.Fatalf("tags %v %v", tags, err)
		}
		v := ts.request(0).URL.Query()
		if v.Get("scope") != "span" || v.Get("q") != `{ resource.service.name = "api" }` || v.Get("start") != "1" || v.Get("end") != "2" {
			t.Fatalf("params %v", v)
		}
		if _, err := q.SearchTags(t.Context(), SearchTagsOptions{Scope: "nope"}); !errors.Is(err, ErrValidation) {
			t.Fatalf("scope: %v", err)
		}
		if _, err := q.SearchTags(t.Context(), SearchTagsOptions{Query: "{ bad"}); !errors.Is(err, ErrValidation) {
			t.Fatalf("query: %v", err)
		}
		if _, err := q.SearchTags(t.Context(), SearchTagsOptions{Start: time.Unix(3, 0), End: time.Unix(2, 0)}); !errors.Is(err, ErrValidation) {
			t.Fatalf("range: %v", err)
		}
		invalid := fixtureServer(t, "searchTags.v2-scope-invalid-400")
		if _, err := newTempo(t, invalid.URL, nil).Query().SearchTags(t.Context(), SearchTagsOptions{}); !errors.Is(err, ErrValidation) {
			t.Fatalf("server 400: %v", err)
		}
		garbage := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("x")) })
		if _, err := newTempo(t, garbage.URL, nil).Query().SearchTags(t.Context(), SearchTagsOptions{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("garbage: %v", err)
		}
	})

	t.Run("search tag values", func(t *testing.T) {
		ts := fixtureServer(t, "searchTagValues.v2-quoted-tag-path", "searchTagValues.v2-int-typed", "searchTagValues.v2-unknown-tag-empty")
		q := newTempo(t, ts.URL, nil).Query()
		vals, err := q.SearchTagValues(t.Context(), `span."grow weird.key"`, SearchTagValuesOptions{})
		if err != nil || len(vals) != 1 || vals[0] != (TagValue{Type: "string", Value: "x y"}) {
			t.Fatalf("quoted %v %v", vals, err)
		}
		if raw := ts.request(0).URL.EscapedPath(); raw != "/api/v2/search/tag/span.%22grow%20weird.key%22/values" {
			t.Fatalf("path %s", raw)
		}
		f := loadFixture(t, "searchTagValues.v2-int-typed")
		tag := strings.TrimSuffix(strings.TrimPrefix(f.Request.Path, "/api/v2/search/tag/"), "/values")
		vals, err = q.SearchTagValues(t.Context(), tag, SearchTagValuesOptions{Query: `{ status = error }`, Start: time.Unix(1, 0)})
		if err != nil || len(vals) == 0 || vals[0].Type != "int" {
			t.Fatalf("int %v %v", vals, err)
		}
		if ts.request(1).URL.Query().Get("q") != "{ status = error }" {
			t.Fatal("q not sent")
		}
		empty := loadFixture(t, "searchTagValues.v2-unknown-tag-empty")
		tag = strings.TrimSuffix(strings.TrimPrefix(empty.Request.Path, "/api/v2/search/tag/"), "/values")
		if vals, err := q.SearchTagValues(t.Context(), tag, SearchTagValuesOptions{}); err != nil || vals == nil || len(vals) != 0 {
			t.Fatalf("unknown %v %v", vals, err)
		}
		for _, bad := range []string{"", " ", "span.a/b"} {
			if _, err := q.SearchTagValues(t.Context(), bad, SearchTagValuesOptions{}); !errors.Is(err, ErrValidation) {
				t.Errorf("%q: %v", bad, err)
			}
		}
		if _, err := q.SearchTagValues(t.Context(), "name", SearchTagValuesOptions{Query: `{ name = "x }`}); !errors.Is(err, ErrValidation) {
			t.Fatalf("bad q: %v", err)
		}
		if _, err := q.SearchTagValues(t.Context(), "name", SearchTagValuesOptions{Start: time.Unix(3, 0), End: time.Unix(2, 0)}); !errors.Is(err, ErrValidation) {
			t.Fatalf("range: %v", err)
		}
		unscoped := fixtureServer(t, "searchTagValues.v2-invalid-tag-400")
		if _, err := newTempo(t, unscoped.URL, nil).Query().SearchTagValues(t.Context(), "{bad", SearchTagValuesOptions{}); !errors.Is(err, ErrValidation) {
			t.Fatalf("unscoped: %v", err)
		}
		garbage := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("x")) })
		if _, err := newTempo(t, garbage.URL, nil).Query().SearchTagValues(t.Context(), "name", SearchTagValuesOptions{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("garbage: %v", err)
		}
	})
}

func TestTraceQLMetrics(t *testing.T) {
	ts := fixtureServer(t, "traceQLMetrics.quantile-exemplars")
	q := newTempo(t, ts.URL, nil).Query()
	query := `{ resource.service.name = "api" } | quantile_over_time(duration, .9)`
	res, err := q.TraceQLMetrics(t.Context(), query, MetricsRange{
		Start: time.Unix(1795034947, 0), End: time.Unix(1795035043, 0), Step: 5 * time.Second, Exemplars: 5,
	})
	if err != nil {
		t.Fatal(err)
	}
	v := ts.request(0).URL.Query()
	if v.Get("q") != query || v.Get("step") != "5s" || v.Get("exemplars") != "5" || v.Get("start") != "1795034947" || v.Has("since") {
		t.Fatalf("params %v", v)
	}
	s := res.Series[0]
	if s.Labels["p"] != 0.9 || s.Samples[0].Value != 0 || s.Samples[0].Timestamp.UnixMilli() != 1795034945000 ||
		s.Samples[len(s.Samples)-1].Value != 0.536870912 || len(s.Exemplars) == 0 || s.Exemplars[0].Labels["duration"] != "450ms" ||
		s.Exemplars[0].Value != 0.45 || res.Step != 5*time.Second {
		t.Fatalf("result %+v", res)
	}

	if _, err := q.TraceQLMetrics(t.Context(), query, MetricsRange{Since: 15 * time.Minute}); err != nil {
		t.Fatal(err)
	}
	if v := ts.request(1).URL.Query(); v.Get("since") != "15m" || v.Has("start") {
		t.Fatalf("since %v", v)
	}
	if _, err := q.TraceQLMetrics(t.Context(), " ", MetricsRange{}); !errors.Is(err, ErrValidation) {
		t.Fatalf("empty: %v", err)
	}
	if _, err := q.TraceQLMetrics(t.Context(), query, MetricsRange{Step: -1}); !errors.Is(err, ErrValidation) {
		t.Fatalf("negative: %v", err)
	}
	if _, err := q.TraceQLMetrics(t.Context(), query, MetricsRange{Start: time.Unix(3, 0), End: time.Unix(2, 0)}); !errors.Is(err, ErrValidation) {
		t.Fatalf("range: %v", err)
	}
	bad := fixtureServer(t, "traceQLMetrics.no-pipeline-400")
	if _, err := newTempo(t, bad.URL, nil).Query().TraceQLMetrics(t.Context(), "{}", MetricsRange{}); !errors.Is(err, ErrValidation) {
		t.Fatalf("400: %v", err)
	}
	garbage := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("x")) })
	if _, err := newTempo(t, garbage.URL, nil).Query().TraceQLMetrics(t.Context(), "{} | rate()", MetricsRange{}); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("garbage: %v", err)
	}
}

func TestProbes(t *testing.T) {
	ts := fixtureServer(t, "echo.ok", "ready.ok", "buildInfo.ok")
	q := newTempo(t, ts.URL, nil).Query()
	if s, err := q.Echo(t.Context()); err != nil || s != "echo" {
		t.Fatalf("echo %q %v", s, err)
	}
	if ok, err := q.Ready(t.Context()); err != nil || !ok {
		t.Fatalf("ready %v %v", ok, err)
	}
	info, err := q.BuildInfo(t.Context())
	if err != nil || info != (BuildInfo{Version: "3.1.1", Revision: "9db7df79d", Branch: "HEAD", GoVersion: "go1.27.3"}) {
		t.Fatalf("buildinfo %+v %v", info, err)
	}
	c := newTempo(t, ts.URL, nil)
	if s, err := c.Echo(t.Context()); err != nil || s != "echo" {
		t.Fatalf("client echo %q %v", s, err)
	}
	if ok, err := c.Ready(t.Context()); err != nil || !ok {
		t.Fatalf("client ready %v %v", ok, err)
	}
	if ci, err := c.BuildInfo(t.Context()); err != nil || ci != info {
		t.Fatalf("client buildinfo %+v %v", ci, err)
	}

	starting := newTestServer(t, status(http.StatusServiceUnavailable))
	if ok, err := newTempo(t, starting.URL, nil).Query().Ready(t.Context()); err != nil || ok {
		t.Fatalf("503: %v %v", ok, err)
	}
	teapot := newTestServer(t, status(http.StatusTeapot))
	if _, err := newTempo(t, teapot.URL, nil).Query().Ready(t.Context()); !errors.Is(err, ErrValidation) {
		t.Fatalf("418: %v", err)
	}
	down := newTempo(t, "http://"+closedAddr(t), func(c *Config) { c.Retry = fastRetry(0) }).Query()
	if _, err := down.Ready(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("unreachable: %v", err)
	}
	if _, err := down.Echo(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("echo unreachable: %v", err)
	}
	if _, err := down.BuildInfo(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("buildinfo unreachable: %v", err)
	}
	garbage := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("x")) })
	if _, err := newTempo(t, garbage.URL, nil).Query().BuildInfo(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("garbage: %v", err)
	}
}

func TestTraceQueryClient(t *testing.T) {
	t.Run("search traces maps to TraceQL", func(t *testing.T) {
		ts := fixtureServer(t, "searchTraces.mapped-traceql")
		q := newTempo(t, ts.URL, nil).Query()
		got, err := q.SearchTraces(t.Context(), TraceSearchQuery{
			Service: "api", Operation: "GET /orders", Tags: map[string]string{"grow.run": "1", "a b": "x"},
			MinDuration: 400 * time.Millisecond, MaxDuration: 500 * time.Millisecond,
			Start: time.Unix(1795034947, 0), End: time.Unix(1795035067, 0),
		})
		if err != nil {
			t.Fatal(err)
		}
		v := ts.request(0).URL.Query()
		wantQ := `{ resource.service.name = "api" && name = "GET /orders" && ."a b" = "x" && .grow.run = "1" && trace:duration >= 400000us && trace:duration <= 500000us }`
		if v.Get("q") != wantQ || v.Get("limit") != "20" || v.Get("start") != "1795034947" {
			t.Fatalf("params %v", v)
		}
		want := TraceSummary{
			TraceID: "00f74bc1b058a1a8b85f9ae0231e4001", RootServiceName: "grow-sdk-verify-179503498127460-api", RootName: "GET /orders",
			StartMicros: 1795035005729668, DurationMicros: 450000, SpanCount: 4,
		}
		if len(got) != 1 || got[0] != want {
			t.Fatalf("summary %+v", got)
		}
		if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{MinDuration: -1}); !errors.Is(err, ErrValidation) {
			t.Fatalf("negative: %v", err)
		}
		if s, _ := searchTracesTraceQL(TraceSearchQuery{}); s != "{}" {
			t.Fatalf("empty query %q", s)
		}
		if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{Tags: map[string]string{"": "x"}}); !errors.Is(err, ErrValidation) {
			t.Fatalf("empty tag: %v", err)
		}
		bad := newTestServer(t, status(http.StatusBadRequest))
		if _, err := newTempo(t, bad.URL, nil).Query().SearchTraces(t.Context(), TraceSearchQuery{}); !errors.Is(err, ErrValidation) {
			t.Fatalf("400: %v", err)
		}
	})

	t.Run("services and operations", func(t *testing.T) {
		ts := fixtureServer(t, "searchTagValues.v2-service-name", "searchTagValues.v2-name-filtered")
		q := newTempo(t, ts.URL, nil).Query()
		svcs, err := q.ListServices(t.Context())
		if err != nil || len(svcs) != 6 || svcs[0] != "grow-sdk-seed-api" || ts.request(0).URL.Path != "/api/v2/search/tag/resource.service.name/values" {
			t.Fatalf("services %v %v", svcs, err)
		}
		ops, err := q.ListOperations(t.Context(), `my "svc"`)
		if err != nil || len(ops) == 0 {
			t.Fatalf("ops %v %v", ops, err)
		}
		r := ts.request(1)
		if r.URL.Path != "/api/v2/search/tag/name/values" || r.URL.Query().Get("q") != `{ resource.service.name = "my \"svc\"" }` {
			t.Fatalf("ops request %s", r.URL)
		}
		if _, err := q.ListOperations(t.Context(), " "); !errors.Is(err, ErrValidation) {
			t.Fatalf("empty service: %v", err)
		}
		down := newTempo(t, "http://"+closedAddr(t), func(c *Config) { c.Retry = fastRetry(0) }).Query()
		if _, err := down.ListServices(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("services down: %v", err)
		}
		if _, err := down.ListOperations(t.Context(), "x"); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("ops down: %v", err)
		}
		if got := sortedValues([]TagValue{{Value: "b"}, {Value: "a"}, {Value: "b"}}); !reflect.DeepEqual(got, []string{"a", "b"}) {
			t.Fatalf("dedupe %v", got)
		}
	})

	t.Run("dependencies are unsupported", func(t *testing.T) {
		q := newTempo(t, "http://127.0.0.1:1", nil).Query()
		if _, err := q.GetDependencies(t.Context(), DependencyOptions{}); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("deps: %v", err)
		}
	})

	t.Run("wait for trace", func(t *testing.T) {
		calls := 0
		f := loadFixture(t, "getTrace.v2-ok")
		miss := loadFixture(t, "getTrace.v2-not-found")
		ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			calls++
			if calls < 2 {
				miss.write(w)
				return
			}
			f.write(w)
		})
		q := newTempo(t, ts.URL, nil).Query()
		tr, err := q.WaitForTrace(t.Context(), "00f74bc1b058a1a8b85f9ae0231e4001", WaitForTraceOptions{Interval: 10 * time.Millisecond, MinSpans: 4})
		if err != nil || len(tr.Spans) != 4 {
			t.Fatalf("wait %v %v", tr, err)
		}
		ctx, cancel := context.WithTimeout(t.Context(), time.Second)
		defer cancel()
		empty := fixtureServer(t, "getTrace.v2-not-found")
		_, err = newTempo(t, empty.URL, nil).Query().WaitForTrace(ctx, "1", WaitForTraceOptions{Timeout: 50 * time.Millisecond, Interval: 10 * time.Millisecond})
		if !errors.Is(err, ErrTimeout) {
			t.Fatalf("timeout: %v", err)
		}
	})
}
