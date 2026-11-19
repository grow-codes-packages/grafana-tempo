package tempo

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Client is the Grafana Tempo client. It is safe for concurrent use.
//
// Health, Ping, ServerVersion and Close form the shared client surface; the query API
// (traces, search, tags, TraceQL metrics) is reached through Query.
type Client struct {
	cfg       Config
	transport *httpTransport
	query     *QueryClient
	closeOnce sync.Once
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg.CommonConfig = cfg.withDefaults(DefaultURL)
	t, err := newHTTPTransport(cfg.CommonConfig)
	if err != nil {
		return nil, err
	}
	if !cfg.Token.IsZero() {
		t.addSecret(cfg.Token.Reveal())
	}
	c := &Client{cfg: cfg, transport: t}
	c.query = &QueryClient{c: c}
	return c, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// Config returns the effective configuration.
func (c *Client) Config() Config { return c.cfg }

// Query returns the Tempo query client (it implements TraceQueryClient).
func (c *Client) Query() *QueryClient { return c.query }

// BuildInfo is Query().BuildInfo: GET /api/status/buildinfo.
func (c *Client) BuildInfo(ctx context.Context) (BuildInfo, error) { return c.query.BuildInfo(ctx) }

// Echo is Query().Echo: GET /api/echo (no tenant needed).
func (c *Client) Echo(ctx context.Context) (string, error) { return c.query.Echo(ctx) }

// Ready is Query().Ready: true when GET /ready answers "ready", false during start-up.
func (c *Client) Ready(ctx context.Context) (bool, error) { return c.query.Ready(ctx) }

// headers returns the per-request headers: tenant and optional bearer token.
func (c *Client) headers() http.Header {
	h := http.Header{}
	if c.cfg.TenantID != "" {
		h.Set(TenantHeader, c.cfg.TenantID)
	}
	if !c.cfg.Token.IsZero() {
		h.Set("Authorization", "Bearer "+c.cfg.Token.Reveal())
	}
	return h
}

// get sends a GET with the tenant header and maps Tempo's "no org id" 401.
func (c *Client) get(ctx context.Context, operation, path string, query url.Values) (*response, error) {
	res, err := c.transport.do(ctx, &request{
		Method: http.MethodGet, Path: path, Query: query, Header: c.headers(), Operation: operation,
	})
	if err != nil {
		return nil, mapTempoError(err)
	}
	return res, nil
}

// mapTempoError rewrites the 401 a multi-tenant Tempo returns without X-Scope-OrgID.
func mapTempoError(err error) error {
	var e *Error
	if errors.As(err, &e) && e.Code == ErrorCodeAuthentication && strings.Contains(e.Message, "no org id") {
		op, _, _ := strings.Cut(e.Message, ":")
		out := *e
		out.Message = op + ": tenant required (" + TenantHeader + ") (HTTP 401)"
		return &out
	}
	return err
}

// Health checks readiness with GET /ready (no tenant or credentials needed). A ready
// Tempo gives Healthy=true, Status "ok"; any other answer gives Status "unhealthy"
// with Details["reason"]; a network failure gives Status "unreachable". It fails only
// for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	res, err := c.transport.do(ctx, &request{
		Method: http.MethodGet, Path: "/ready", Operation: "health", NoRetry: true, RawStatus: true,
	})
	if err != nil {
		switch CodeOf(err) {
		case ErrorCodeCancelled, ErrorCodeConfig:
			return HealthStatus{}, err
		}
		return HealthStatus{Status: "unreachable", Details: map[string]string{"error": err.Error()}}, nil
	}
	body := truncate(strings.TrimSpace(c.transport.redact(string(res.Body))), maxVendorText)
	if res.Status == http.StatusOK && body == "ready" {
		return HealthStatus{Healthy: true, Status: "ok", Details: map[string]string{"ready": body}}, nil
	}
	return HealthStatus{Status: "unhealthy", Details: map[string]string{
		"httpStatus": strconv.Itoa(res.Status), "reason": body,
	}}, nil
}

// Ping makes a tenant-checked round trip (GET /api/v2/search/tags?scope=intrinsic) and
// returns its latency. A multi-tenant Tempo without a tenant gives an AUTHENTICATION
// error ("tenant required").
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	if _, err := c.get(ctx, "ping", "/api/v2/search/tags", url.Values{"scope": {"intrinsic"}}); err != nil {
		return 0, err
	}
	return max(time.Since(start), time.Nanosecond), nil
}

var statusVersionRe = regexp.MustCompile(`version\s+v?([0-9][^\s(]*)`)

// ServerVersion returns the Tempo version ("3.1.1") from /api/status/buildinfo, falling
// back to the text of /status/version. UNSUPPORTED when neither route exists.
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	info, err := c.query.BuildInfo(ctx)
	if err == nil && info.Version != "" {
		return strings.TrimPrefix(info.Version, "v"), nil
	}
	if err != nil && CodeOf(err) != ErrorCodeNotFound {
		return "", err
	}
	res, err := c.get(ctx, "serverVersion", "/status/version", nil)
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return "", UnsupportedError("serverVersion", "the server exposes no version")
		}
		return "", err
	}
	m := statusVersionRe.FindStringSubmatch(string(res.Body))
	if m == nil {
		return "", &Error{Code: ErrorCodeUnavailable, Message: "serverVersion: unrecognised version text", HTTPStatus: res.Status, Retryable: true}
	}
	return m[1], nil
}

// Close releases idle connections; later calls fail with CANCELLED. It is idempotent.
// The client owns no tracer provider: shut down a TracingHandle separately.
func (c *Client) Close() error {
	c.closeOnce.Do(c.transport.close)
	return nil
}
