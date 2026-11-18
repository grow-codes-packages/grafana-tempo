package integration

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"slices"
	"strconv"
	"testing"
	"time"

	"go.opentelemetry.io/otel/trace"

	tempo "github.com/grow-codes-packages/grafana-tempo/go"
)

// setup loads /bootstrap/env and skips outside the Docker harness.
func setup(t *testing.T) {
	t.Helper()
	bootstrap := os.Getenv("GROW_BOOTSTRAP_ENV")
	if bootstrap == "" {
		bootstrap = "/bootstrap/env"
	}
	loadBootstrapEnv(t, bootstrap)
	if os.Getenv("GROW_GRAFANA_TEMPO_URL") == "" {
		t.Skip("GROW_GRAFANA_TEMPO_URL is not set; run through docker/compose.yml")
	}
}

func runID(t *testing.T) string {
	t.Helper()
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b)
}

func env(t *testing.T, name string) string {
	t.Helper()
	v := os.Getenv(name)
	if v == "" {
		t.Fatalf("%s missing from /bootstrap/env", name)
	}
	return v
}

func newClient(t *testing.T, cfg tempo.Config) *tempo.Client {
	t.Helper()
	c, err := tempo.NewClient(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// emit exports a two-span trace (GET /orders > load order, >200 ms) and returns its id.
func emit(t *testing.T, h *tempo.TracingHandle, run string) string {
	t.Helper()
	ctx := context.Background()
	tracer := h.Tracer("grow-sdk-it")
	traceID, err := tempo.WithSpan(ctx, "GET /orders", func(ctx context.Context) (string, error) {
		tempo.AddEvent(ctx, "cache-miss", map[string]any{"n": 1})
		err := tempo.Traced(ctx, "load order", func(ctx context.Context) error {
			time.Sleep(250 * time.Millisecond)
			return nil
		}, tempo.WithTracer(tracer), tempo.WithSpanAttributes(map[string]any{"grow.run": run}))
		if err != nil {
			return "", err
		}
		id, _ := tempo.CurrentTraceID(ctx)
		return id, nil
	}, tempo.WithTracer(tracer), tempo.WithSpanKind(trace.SpanKindServer),
		tempo.WithSpanAttributes(map[string]any{"grow.run": run, "http.response.status_code": 200, "grow weird.key": "x y"}))
	if err != nil {
		t.Fatal(err)
	}
	if err := h.ForceFlush(ctx); err != nil {
		t.Fatal(err)
	}
	return traceID
}

// eventually retries check until it passes or the search visibility window ends.
func eventually(t *testing.T, what string, check func() error) {
	t.Helper()
	deadline := time.Now().Add(searchVisibility)
	for {
		err := check()
		if err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s: %v", what, err)
		}
		time.Sleep(2 * time.Second)
	}
}

func TestTempo(t *testing.T) {
	setup(t)
	ctx := context.Background()
	run := runID(t)
	version := env(t, "GROW_GRAFANA_TEMPO_SERVER_VERSION")

	client, err := tempo.NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	q := client.Query()

	t.Run("client surface", func(t *testing.T) {
		hs, err := client.Health(ctx)
		if err != nil || !hs.Healthy || hs.Status != "ok" {
			t.Fatalf("health %+v %v", hs, err)
		}
		if d, err := client.Ping(ctx); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		if v, err := client.ServerVersion(ctx); err != nil || v != version {
			t.Fatalf("version %q want %q: %v", v, version, err)
		}
		if client.Config().URL != os.Getenv("GROW_GRAFANA_TEMPO_URL") {
			t.Fatal("Config()")
		}
	})

	t.Run("probes", func(t *testing.T) {
		if s, err := q.Echo(ctx); err != nil || s != "echo" {
			t.Fatalf("echo %q %v", s, err)
		}
		if ok, err := q.Ready(ctx); err != nil || !ok {
			t.Fatalf("ready %v %v", ok, err)
		}
		if info, err := q.BuildInfo(ctx); err != nil || info.Version != version || info.GoVersion == "" {
			t.Fatalf("buildinfo %+v %v", info, err)
		}
	})

	t.Run("seed trace by id", func(t *testing.T) {
		seedID := env(t, "GROW_GRAFANA_TEMPO_SEED_TRACE_ID")
		tr, err := q.GetTrace(ctx, seedID)
		if err != nil || tr == nil || len(tr.Spans) != 3 || tr.TraceID != seedID {
			t.Fatalf("seed %+v %v", tr, err)
		}
		root, db := tr.Spans[0], tr.Spans[2]
		if root.Name != "GET /seed" || root.Kind != tempo.SpanKindServer || root.Status.Code != tempo.SpanStatusOK ||
			root.ServiceName != env(t, "GROW_GRAFANA_TEMPO_SEED_SERVICE") || root.Attributes["http.response.status_code"] != int64(200) ||
			len(root.Events) != 1 || root.DurationMicros != 1_000_000 {
			t.Fatalf("root %+v", root)
		}
		if db.ServiceName != env(t, "GROW_GRAFANA_TEMPO_SEED_CHILD_SERVICE") || db.Status.Code != tempo.SpanStatusError ||
			db.Status.Message != "seed failure" || len(tr.Spans[1].Links) != 1 {
			t.Fatalf("children %+v", tr.Spans[1:])
		}
		start, _ := strconv.ParseInt(env(t, "GROW_GRAFANA_TEMPO_SEED_START"), 10, 64)
		end, _ := strconv.ParseInt(env(t, "GROW_GRAFANA_TEMPO_SEED_END"), 10, 64)
		ranged, err := q.GetTraceWith(ctx, seedID, tempo.GetTraceOptions{Start: time.Unix(start-60, 0), End: time.Unix(end+60, 0)})
		if err != nil || ranged == nil || len(ranged.Spans) != 3 {
			t.Fatalf("ranged %+v %v", ranged, err)
		}
		if miss, err := q.GetTrace(ctx, "0123456789abcdef0123456789abcdef"); err != nil || miss != nil {
			t.Fatalf("miss %+v %v", miss, err)
		}
		if _, err := q.GetTraceWith(ctx, seedID, tempo.GetTraceOptions{Start: time.Unix(end, 0), End: time.Unix(start, 0)}); !errors.Is(err, tempo.ErrValidation) {
			t.Fatalf("bad range: %v", err)
		}
	})

	// Export over OTLP/HTTP (default) and OTLP/gRPC, read back by id at once.
	service := "grow-sdk-go-it-" + run
	h, err := tempo.InitTracing(ctx, tempo.TracingConfig{ServiceName: service, DisableSetGlobal: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = h.Shutdown(context.Background()) })
	if h.Config().Endpoint != env(t, "GROW_GRAFANA_TEMPO_OTLP_ENDPOINT") || h.Config().Protocol != tempo.ProtocolHTTPProtobuf {
		t.Fatalf("tracing config %s", h.Config())
	}
	traceID := emit(t, h, run)

	t.Run("otlp http round trip", func(t *testing.T) {
		tr, err := q.WaitForTrace(ctx, traceID, tempo.WaitForTraceOptions{Timeout: 30 * time.Second, MinSpans: 2})
		if err != nil {
			t.Fatal(err)
		}
		root, child := tr.Spans[0], tr.Spans[1]
		if root.Name != "GET /orders" || child.Name != "load order" || child.ParentSpanID != root.SpanID ||
			root.ServiceName != service || root.Attributes["grow.run"] != run || root.Attributes["grow weird.key"] != "x y" ||
			len(root.Events) != 1 || root.Events[0].Name != "cache-miss" || child.DurationMicros < 250_000 {
			t.Fatalf("trace %+v", tr)
		}
	})

	t.Run("otlp grpc round trip", func(t *testing.T) {
		cfg, err := tempo.TracingConfigFromEnv(tempo.TracingConfig{
			ServiceName: service + "-grpc", Protocol: tempo.ProtocolGRPC,
			Endpoint: env(t, "GROW_GRAFANA_TEMPO_OTLP_GRPC_ENDPOINT"), DisableSetGlobal: true,
		})
		if err != nil {
			t.Fatal(err)
		}
		g, err := tempo.InitTracingWith(ctx, cfg, tempo.NewExporter)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = g.Shutdown(context.Background()) }()
		id := emit(t, g, run)
		tr, err := q.WaitForTrace(ctx, id, tempo.WaitForTraceOptions{Timeout: 30 * time.Second, MinSpans: 2})
		if err != nil || tr.Spans[0].ServiceName != service+"-grpc" {
			t.Fatalf("grpc %+v %v", tr, err)
		}
	})

	t.Run("multi-tenant", func(t *testing.T) {
		mtURL := env(t, "GROW_GRAFANA_TEMPO_MT_URL")
		tenantA, tenantB := env(t, "GROW_GRAFANA_TEMPO_TENANT_ID"), env(t, "GROW_GRAFANA_TEMPO_OTHER_TENANT_ID")
		a := newClient(t, tempo.Config{CommonConfig: tempo.CommonConfig{URL: mtURL}, TenantID: tenantA}).Query()
		b := newClient(t, tempo.Config{CommonConfig: tempo.CommonConfig{URL: mtURL}, TenantID: tenantB}).Query()
		none := newClient(t, tempo.Config{CommonConfig: tempo.CommonConfig{URL: mtURL}})
		if _, err := none.Ping(ctx); !errors.Is(err, tempo.ErrAuthentication) {
			t.Fatalf("no tenant ping: %v", err)
		}
		if seed, err := a.GetTrace(ctx, env(t, "GROW_GRAFANA_TEMPO_MT_SEED_TRACE_ID")); err != nil || seed == nil {
			t.Fatalf("mt seed %v %v", seed, err)
		}
		mh, err := tempo.InitTracing(ctx, tempo.WithTenant(tempo.TracingConfig{
			ServiceName: service + "-mt", Endpoint: env(t, "GROW_GRAFANA_TEMPO_MT_OTLP_ENDPOINT"), DisableSetGlobal: true,
		}, tenantA))
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = mh.Shutdown(context.Background()) }()
		id := emit(t, mh, run)
		if tr, err := a.WaitForTrace(ctx, id, tempo.WaitForTraceOptions{Timeout: 30 * time.Second, MinSpans: 2}); err != nil || tr.Spans[0].ServiceName != service+"-mt" {
			t.Fatalf("tenant a %+v %v", tr, err)
		}
		if tr, err := b.GetTrace(ctx, id); err != nil || tr != nil {
			t.Fatalf("tenant b must not see it: %+v %v", tr, err)
		}
	})

	t.Run("search, tags and metrics", func(t *testing.T) {
		windowStart := time.Now().Add(-10 * time.Minute)
		windowEnd := time.Now().Add(5 * time.Minute)
		query, err := tempo.TraceQL.Span(map[string]any{"resource.service.name": service}).
			And("duration", ">", "200ms").Select("span.grow.run").Build()
		if err != nil {
			t.Fatal(err)
		}
		eventually(t, "Search", func() error {
			res, err := q.Search(ctx, tempo.SearchRequest{TraceQL: query, Start: windowStart, End: windowEnd, Limit: 5, SpansPerSpanSet: 5})
			if err != nil {
				return err
			}
			for _, tr := range res.Traces {
				if tr.TraceID == traceID && len(tr.SpanSets) > 0 && tr.SpanSets[0].Matched == 2 &&
					tr.SpanSets[0].Spans[0].Attributes["grow.run"] == run && tr.RootServiceName == service {
					return nil
				}
			}
			return fmt.Errorf("trace %s not found in %+v", traceID, res.Traces)
		})
		eventually(t, "SearchTraces", func() error {
			got, err := q.SearchTraces(ctx, tempo.TraceSearchQuery{
				Service: service, Operation: "GET /orders", Tags: map[string]string{"grow.run": run},
				MinDuration: 200 * time.Millisecond, MaxDuration: time.Minute, Start: windowStart, End: windowEnd,
			})
			if err != nil {
				return err
			}
			if len(got) != 1 || got[0].TraceID != traceID || got[0].SpanCount != 2 || got[0].RootName != "GET /orders" {
				return fmt.Errorf("summaries %+v", got)
			}
			return nil
		})
		eventually(t, "SearchTags", func() error {
			tags, err := q.SearchTags(ctx, tempo.SearchTagsOptions{Scope: tempo.TagScopeSpan, Query: query})
			if err != nil {
				return err
			}
			if !slices.Contains(tags["span"], "grow.run") {
				return fmt.Errorf("tags %v", tags)
			}
			return nil
		})
		eventually(t, "SearchTagValues", func() error {
			vals, err := q.SearchTagValues(ctx, `span."grow weird.key"`, tempo.SearchTagValuesOptions{
				Query: `{ resource.service.name = ` + tempo.EscapeTraceQLString(service) + ` }`,
			})
			if err != nil {
				return err
			}
			if len(vals) != 1 || vals[0] != (tempo.TagValue{Type: "string", Value: "x y"}) {
				return fmt.Errorf("values %v", vals)
			}
			return nil
		})
		eventually(t, "ListServices", func() error {
			svcs, err := q.ListServices(ctx)
			if err != nil {
				return err
			}
			if !slices.Contains(svcs, service) || !slices.IsSorted(svcs) {
				return fmt.Errorf("services %v", svcs)
			}
			return nil
		})
		eventually(t, "ListOperations", func() error {
			ops, err := q.ListOperations(ctx, service)
			if err != nil {
				return err
			}
			// Tempo's filtered tag values occasionally omit child span names (observed once
			// with Tempo 3.1.1); the root operation must be there and nothing foreign.
			if !slices.Contains(ops, "GET /orders") || !slices.IsSorted(ops) {
				return fmt.Errorf("operations %v", ops)
			}
			for _, op := range ops {
				if op != "GET /orders" && op != "load order" {
					return fmt.Errorf("foreign operation %q in %v", op, ops)
				}
			}
			return nil
		})
		eventually(t, "TraceQLMetrics", func() error {
			res, err := q.TraceQLMetrics(ctx, `{ resource.service.name = `+tempo.EscapeTraceQLString(service)+` } | count_over_time()`,
				tempo.MetricsRange{Start: windowStart, End: windowEnd, Step: 30 * time.Second})
			if err != nil {
				return err
			}
			total := 0.0
			for _, s := range res.Series {
				for _, p := range s.Samples {
					total += p.Value
				}
			}
			if total != 2 || res.Step != 30*time.Second {
				return fmt.Errorf("count %v step %s in %+v", total, res.Step, res)
			}
			return nil
		})
	})

	t.Run("errors and unsupported", func(t *testing.T) {
		if _, err := q.Search(ctx, tempo.SearchRequest{TraceQL: "{ resource.service.name = }"}); !errors.Is(err, tempo.ErrValidation) {
			t.Fatalf("invalid TraceQL: %v", err)
		}
		if _, err := q.SearchTagValues(ctx, "service.name", tempo.SearchTagValuesOptions{}); !errors.Is(err, tempo.ErrValidation) {
			t.Fatalf("unscoped tag: %v", err)
		}
		if _, err := q.TraceQLMetrics(ctx, "{}", tempo.MetricsRange{Since: 15 * time.Minute}); !errors.Is(err, tempo.ErrValidation) {
			t.Fatalf("not a metrics query: %v", err)
		}
		if _, err := q.GetDependencies(ctx, tempo.DependencyOptions{}); !errors.Is(err, tempo.ErrUnsupported) {
			t.Fatalf("dependencies: %v", err)
		}
	})

	t.Run("close", func(t *testing.T) {
		if client.Close() != nil || client.Close() != nil {
			t.Fatal("close")
		}
		if _, err := client.Ping(ctx); !errors.Is(err, tempo.ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
	})
}
