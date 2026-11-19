package tempo

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestTracingConfigFromEnv(t *testing.T) {
	t.Run("defaults", func(t *testing.T) {
		c, err := TracingConfigFromEnvWith(TracingConfig{ServiceName: "svc"}, map[string]string{})
		if err != nil || c.Protocol != ProtocolHTTPProtobuf || c.Endpoint != DefaultOTLPHTTPEndpoint || len(c.Headers) != 0 {
			t.Fatalf("defaults %s %v", c, err)
		}
		c, _ = TracingConfigFromEnvWith(TracingConfig{ServiceName: "svc", Protocol: ProtocolGRPC}, map[string]string{})
		if c.Endpoint != DefaultOTLPGRPCEndpoint {
			t.Fatalf("grpc default %s", c.Endpoint)
		}
		c, _ = TracingConfigFromEnvWith(TracingConfig{ServiceName: "svc", Protocol: ProtocolNative}, map[string]string{})
		if c.Protocol != ProtocolHTTPProtobuf {
			t.Fatalf("native %s", c.Protocol)
		}
	})

	t.Run("precedence", func(t *testing.T) {
		env := map[string]string{
			EnvPrefix + "OTLP_ENDPOINT":             "http://grow:4318",
			"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT":    "http://traces:4318/custom",
			"OTEL_EXPORTER_OTLP_ENDPOINT":           "http://generic:4318/base/",
			EnvPrefix + "OTLP_PROTOCOL":             "GRPC",
			"OTEL_EXPORTER_OTLP_TRACES_PROTOCOL":    "http/protobuf",
			"OTEL_EXPORTER_OTLP_TRACES_HEADERS":     "authorization=Bearer%20abc,x-team=core",
			"OTEL_EXPORTER_OTLP_HEADERS":            "x-generic=1",
			"TEMPO_TENANT_ID":                       "tenant-a",
			"OTEL_EXPORTER_OTLP_TRACES_COMPRESSION": "none",
			"OTEL_SERVICE_NAME":                     "from-env",
			"OTEL_RESOURCE_ATTRIBUTES":              "service.name=from-attrs,team=core,region=eu",
			"OTEL_TRACES_SAMPLER":                   "traceidratio",
			"OTEL_TRACES_SAMPLER_ARG":               "0.25",
		}
		c, err := TracingConfigFromEnvWith(TracingConfig{ResourceAttributes: map[string]any{"team": "explicit"}, Headers: map[string]string{"X-Team": "explicit"}}, env)
		if err != nil {
			t.Fatal(err)
		}
		if c.Endpoint != "http://grow:4318" || c.Protocol != ProtocolGRPC || c.Compression != CompressionNone || c.ServiceName != "from-env" ||
			c.Sampler != SamplerTraceIDRatio || c.SamplerRatio != 0.25 || c.ResourceAttributes["team"] != "explicit" ||
			c.ResourceAttributes["region"] != "eu" || c.ResourceAttributes["service.name"] != nil {
			t.Fatalf("resolved %s %v", c, c.ResourceAttributes)
		}
		if c.Headers["authorization"] != "Bearer abc" || c.Headers["X-Team"] != "explicit" || c.Headers["x-team"] != "" ||
			c.Headers[TenantHeader] != "tenant-a" || c.Headers["x-generic"] != "" {
			t.Fatalf("headers %v", c.Headers)
		}
		if strings.Contains(c.String(), "abc") {
			t.Fatalf("String leaks: %s", c)
		}

		delete(env, EnvPrefix+"OTLP_ENDPOINT")
		delete(env, EnvPrefix+"OTLP_PROTOCOL")
		delete(env, "OTEL_SERVICE_NAME")
		c, _ = TracingConfigFromEnvWith(TracingConfig{}, env)
		if c.Endpoint != "http://traces:4318/custom" || c.Protocol != ProtocolHTTPProtobuf || c.ServiceName != "from-attrs" {
			t.Fatalf("traces vars %s", c)
		}
		delete(env, "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
		c, _ = TracingConfigFromEnvWith(TracingConfig{}, env)
		if c.Endpoint != "http://generic:4318/base/v1/traces" {
			t.Fatalf("generic endpoint %s", c.Endpoint)
		}
		env["OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"] = "grpc"
		c, _ = TracingConfigFromEnvWith(TracingConfig{}, env)
		if c.Endpoint != "http://generic:4318/base/" {
			t.Fatalf("generic grpc endpoint %s", c.Endpoint)
		}
		c, _ = TracingConfigFromEnvWith(TracingConfig{ServiceName: "x", Headers: map[string]string{"x-scope-orgid": "explicit"}}, env)
		if c.Headers["x-scope-orgid"] != "explicit" || c.Headers[TenantHeader] != "" {
			t.Fatalf("explicit tenant header %v", c.Headers)
		}
		c, _ = TracingConfigFromEnvWith(TracingConfig{ServiceName: "x"}, map[string]string{EnvPrefix + "TENANT_ID": "grow", "TEMPO_TENANT_ID": "vendor"})
		if c.Headers[TenantHeader] != "grow" {
			t.Fatalf("tenant precedence %v", c.Headers)
		}
	})

	t.Run("invalid", func(t *testing.T) {
		cases := []map[string]string{
			{"OTEL_EXPORTER_OTLP_HEADERS": "novalue"},
			{"OTEL_EXPORTER_OTLP_HEADERS": "k=%zz"},
			{"OTEL_RESOURCE_ATTRIBUTES": "=x"},
			{"OTEL_TRACES_SAMPLER_ARG": "lots-secret"},
			{"OTEL_EXPORTER_OTLP_PROTOCOL": "http/json"},
			{"TEMPO_TENANT_ID": "bad/tenant"},
			{},
		}
		for i, env := range cases {
			name := "x"
			if len(env) == 0 {
				name = ""
			}
			_, err := TracingConfigFromEnvWith(TracingConfig{ServiceName: name}, env)
			if !errors.Is(err, ErrConfig) || strings.Contains(err.Error(), "lots-secret") {
				t.Errorf("case %d: %v", i, err)
			}
		}
		if got := appendTracesPath("::bad"); got != "::bad" {
			t.Fatalf("bad url %s", got)
		}
		t.Setenv("OTEL_SERVICE_NAME", "process")
		if c, err := TracingConfigFromEnv(TracingConfig{}); err != nil || c.ServiceName != "process" {
			t.Fatalf("process env %s %v", c, err)
		}
	})

	t.Run("with tenant", func(t *testing.T) {
		base := TracingConfig{Headers: map[string]string{"x-scope-orgid": "old", "a": "b"}}
		c := WithTenant(base, "tenant-b")
		if c.Headers[TenantHeader] != "tenant-b" || c.Headers["x-scope-orgid"] != "" || c.Headers["a"] != "b" || base.Headers["x-scope-orgid"] != "old" {
			t.Fatalf("with tenant %v / base %v", c.Headers, base.Headers)
		}
		if c := WithTenant(c, ""); len(c.Headers) != 1 {
			t.Fatalf("remove %v", c.Headers)
		}
	})
}

func TestInitTracing(t *testing.T) {
	var mu sync.Mutex
	var got []*http.Request
	ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		_, _ = io.ReadAll(r.Body)
		mu.Lock()
		got = append(got, r)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/x-protobuf")
	})

	h, err := InitTracing(context.Background(), WithTenant(TracingConfig{
		ServiceName: "orders", Endpoint: ts.URL, DisableSetGlobal: true,
		Batch: TracingBatchOptions{ScheduledDelay: time.Hour},
	}, "tenant-a"))
	if err != nil {
		t.Fatal(err)
	}
	if h.Config().Protocol != ProtocolHTTPProtobuf {
		t.Fatalf("protocol %s", h.Config().Protocol)
	}
	_, span := h.Tracer("test").Start(context.Background(), "op")
	span.End()
	if err := h.ForceFlush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := h.Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(got) == 0 || got[0].URL.Path != "/v1/traces" || got[0].Header.Get(TenantHeader) != "tenant-a" ||
		got[0].Header.Get("Content-Type") != "application/x-protobuf" {
		t.Fatalf("export requests %d %v", len(got), got)
	}

	if _, err := InitTracing(context.Background(), TracingConfig{ServiceName: "x", Endpoint: "ftp://x"}); !errors.Is(err, ErrConfig) {
		t.Fatalf("bad endpoint: %v", err)
	}

	g, err := InitTracing(context.Background(), TracingConfig{ServiceName: "x", Protocol: ProtocolGRPC, DisableSetGlobal: true})
	if err != nil || g.Config().Endpoint != DefaultOTLPGRPCEndpoint {
		t.Fatalf("grpc %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = g.Shutdown(ctx)

	exp, err := NewExporter(context.Background(), TracingConfig{ServiceName: "x"})
	if err != nil || exp == nil {
		t.Fatalf("NewExporter: %v", err)
	}
	_ = exp.Shutdown(context.Background())
}
