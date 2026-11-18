package tempo

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// Default OTLP endpoints of Tempo.
const (
	DefaultOTLPHTTPEndpoint = "http://127.0.0.1:4318"
	DefaultOTLPGRPCEndpoint = "http://127.0.0.1:4317"
	// DefaultTracingProtocol is the export protocol when none is configured.
	DefaultTracingProtocol = ProtocolHTTPProtobuf
)

// InitTracing starts OpenTelemetry tracing that exports to Tempo over OTLP. Unset
// fields of cfg are filled from the environment (see TracingConfigFromEnvWith), then
// Tempo defaults apply: protocol http/protobuf (ProtocolNative also means OTLP over
// HTTP, Tempo has no other protocol), endpoint http://127.0.0.1:4318 (4317 for gRPC)
// and the X-Scope-OrgID header from the tenant. Shut the handle down to flush.
//
// A multi-tenant Tempo rejects spans without a tenant with HTTP 503 / gRPC
// Unavailable, which the exporter retries until its timeout: set the tenant
// (WithTenant or GROW_GRAFANA_TEMPO_TENANT_ID) for such servers.
func InitTracing(ctx context.Context, cfg TracingConfig) (*TracingHandle, error) {
	resolved, err := TracingConfigFromEnvWith(cfg, nil)
	if err != nil {
		return nil, err
	}
	return InitTracingWith(ctx, resolved, NewExporter)
}

// NewExporter is the Tempo ExporterFactory: it applies the Tempo defaults (protocol,
// endpoint) and builds the official OTLP exporter (NewOTLPExporter).
func NewExporter(ctx context.Context, cfg TracingConfig) (sdktrace.SpanExporter, error) {
	return NewOTLPExporter(ctx, tempoTracingDefaults(cfg))
}

// WithTenant returns a copy of cfg that sends tenant as the X-Scope-OrgID header (gRPC
// metadata x-scope-orgid) with every export. An empty tenant removes the header.
func WithTenant(cfg TracingConfig, tenant string) TracingConfig {
	headers := make(map[string]string, len(cfg.Headers)+1)
	for k, v := range cfg.Headers {
		if !strings.EqualFold(k, TenantHeader) {
			headers[k] = v
		}
	}
	if tenant != "" {
		headers[TenantHeader] = tenant
	}
	cfg.Headers = headers
	return cfg
}

// tempoTracingDefaults fills the protocol and endpoint.
func tempoTracingDefaults(cfg TracingConfig) TracingConfig {
	if cfg.Protocol == "" || cfg.Protocol == ProtocolNative {
		cfg.Protocol = DefaultTracingProtocol
	}
	if cfg.Endpoint == "" {
		cfg.Endpoint = DefaultOTLPHTTPEndpoint
		if cfg.Protocol == ProtocolGRPC {
			cfg.Endpoint = DefaultOTLPGRPCEndpoint
		}
	}
	return cfg
}

// TracingConfigFromEnvWith fills the unset fields of explicit from the environment
// and applies the Tempo defaults. Precedence: explicit > GROW_GRAFANA_TEMPO_OTLP_* >
// OTEL_EXPORTER_OTLP_TRACES_* > OTEL_EXPORTER_OTLP_* > default.
//
//   - Endpoint: GROW_GRAFANA_TEMPO_OTLP_ENDPOINT, OTEL_EXPORTER_OTLP_TRACES_ENDPOINT (used
//     as is), OTEL_EXPORTER_OTLP_ENDPOINT ("/v1/traces" appended for HTTP).
//   - Protocol: GROW_GRAFANA_TEMPO_OTLP_PROTOCOL, OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
//     OTEL_EXPORTER_OTLP_PROTOCOL.
//   - Headers: OTEL_EXPORTER_OTLP_TRACES_HEADERS or OTEL_EXPORTER_OTLP_HEADERS
//     (k=v,k2=v2) for keys not set explicitly, plus X-Scope-OrgID from
//     GROW_GRAFANA_TEMPO_TENANT_ID, TEMPO_TENANT_ID, GROW_TEMPO_TENANT_ID.
//   - Compression: GROW_GRAFANA_TEMPO_OTLP_COMPRESSION, OTEL_EXPORTER_OTLP_TRACES_COMPRESSION,
//     OTEL_EXPORTER_OTLP_COMPRESSION.
//   - ServiceName: OTEL_SERVICE_NAME, then service.name in OTEL_RESOURCE_ATTRIBUTES; the
//     other OTEL_RESOURCE_ATTRIBUTES fill unset resource attributes.
//   - Sampler: OTEL_TRACES_SAMPLER and OTEL_TRACES_SAMPLER_ARG.
//
// env replaces the process environment when non-nil. Invalid values give a CONFIG
// error naming the variable, never its value.
func TracingConfigFromEnvWith(explicit TracingConfig, env map[string]string) (TracingConfig, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if c.Headers != nil {
		c.Headers = copyStringMap(c.Headers)
	}
	if c.ResourceAttributes != nil {
		c.ResourceAttributes = copyAnyMap(c.ResourceAttributes)
	}

	if c.Protocol == "" {
		if v, ok := r.String("OTLP_PROTOCOL", "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"); ok {
			c.Protocol = Protocol(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	if c.Protocol == "" || c.Protocol == ProtocolNative {
		c.Protocol = DefaultTracingProtocol
	}
	if c.Endpoint == "" {
		if v, ok := r.String("OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"); ok {
			c.Endpoint = strings.TrimSpace(v)
		} else if v, ok := r.String("", "OTEL_EXPORTER_OTLP_ENDPOINT"); ok {
			c.Endpoint = strings.TrimSpace(v)
			if c.Protocol != ProtocolGRPC {
				c.Endpoint = appendTracesPath(c.Endpoint)
			}
		}
	}
	if raw, variable, ok := r.find("", []string{"OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS"}); ok {
		parsed, err := parseOTLPHeaders(raw)
		if err != nil {
			return TracingConfig{}, ConfigError(variable + " is not a valid header list")
		}
		for k, v := range parsed {
			if !hasHeader(c.Headers, k) {
				if c.Headers == nil {
					c.Headers = map[string]string{}
				}
				c.Headers[k] = v
			}
		}
	}
	if !hasHeader(c.Headers, TenantHeader) {
		if v, ok := r.String("TENANT_ID", vendorTenantEnv...); ok {
			c = WithTenant(c, strings.TrimSpace(v))
		}
	}
	if c.Compression == "" {
		if v, ok := r.String("OTLP_COMPRESSION", "OTEL_EXPORTER_OTLP_TRACES_COMPRESSION", "OTEL_EXPORTER_OTLP_COMPRESSION"); ok {
			c.Compression = strings.ToLower(strings.TrimSpace(v))
		}
	}
	resourceServiceName := ""
	if raw, variable, ok := r.find("", []string{"OTEL_RESOURCE_ATTRIBUTES"}); ok {
		attrs, err := parseOTLPHeaders(raw)
		if err != nil {
			return TracingConfig{}, ConfigError(variable + " is not a valid attribute list")
		}
		for k, v := range attrs {
			if k == "service.name" {
				continue
			}
			if _, set := c.ResourceAttributes[k]; !set {
				if c.ResourceAttributes == nil {
					c.ResourceAttributes = map[string]any{}
				}
				c.ResourceAttributes[k] = v
			}
		}
		resourceServiceName = attrs["service.name"]
	}
	if c.ServiceName == "" {
		if v, ok := r.String("", "OTEL_SERVICE_NAME"); ok {
			c.ServiceName = strings.TrimSpace(v)
		} else {
			c.ServiceName = resourceServiceName
		}
	}
	if c.Sampler == "" {
		if v, ok := r.String("", "OTEL_TRACES_SAMPLER"); ok {
			c.Sampler = Sampler(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	if c.SamplerRatio == 0 {
		if v, variable, ok := r.find("", []string{"OTEL_TRACES_SAMPLER_ARG"}); ok {
			f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
			if err != nil {
				return TracingConfig{}, ConfigError(variable + " is not a valid number")
			}
			c.SamplerRatio = f
		}
	}
	c = tempoTracingDefaults(c)
	if err := c.Validate(); err != nil {
		return TracingConfig{}, err
	}
	for k, v := range c.Headers {
		if strings.EqualFold(k, TenantHeader) {
			if err := validateTenantID(v); err != nil {
				return TracingConfig{}, err
			}
		}
	}
	return c, nil
}

// TracingConfigFromEnv is TracingConfigFromEnvWith over the process environment.
func TracingConfigFromEnv(explicit TracingConfig) (TracingConfig, error) {
	return TracingConfigFromEnvWith(explicit, nil)
}

// appendTracesPath appends /v1/traces to a base OTLP/HTTP endpoint (OTel spec rule for
// OTEL_EXPORTER_OTLP_ENDPOINT).
func appendTracesPath(endpoint string) string {
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" {
		return endpoint
	}
	u.Path = strings.TrimSuffix(u.Path, "/") + "/v1/traces"
	return u.String()
}

// parseOTLPHeaders parses the OTel list format "k1=v1,k2=v2" (URL-encoded values).
func parseOTLPHeaders(raw string) (map[string]string, error) {
	out := map[string]string{}
	for _, item := range strings.Split(raw, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		k, v, ok := strings.Cut(item, "=")
		k = strings.TrimSpace(k)
		if !ok || k == "" {
			return nil, errInvalidList
		}
		dk, err1 := url.PathUnescape(k)
		dv, err2 := url.PathUnescape(strings.TrimSpace(v))
		if err1 != nil || err2 != nil {
			return nil, errInvalidList
		}
		out[dk] = dv
	}
	return out, nil
}

var errInvalidList = ConfigError("invalid list")

func hasHeader(h map[string]string, name string) bool {
	for k := range h {
		if strings.EqualFold(k, name) {
			return true
		}
	}
	return false
}

func copyStringMap(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

func copyAnyMap(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}
