package tempo

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestClientConfig(t *testing.T) {
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			EnvPrefix + "URL": "http://grow:1", "TEMPO_URL": "http://vendor:2", "GROW_TEMPO_URL": "http://alias:3",
			"TEMPO_TENANT_ID": "vendor-tenant", "GROW_TEMPO_TOKEN": "alias-token",
		}
		cfg, err := ConfigFromEnvWith(Config{}, env)
		if err != nil || cfg.URL != "http://grow:1" || cfg.TenantID != "vendor-tenant" || cfg.Token.Reveal() != "alias-token" {
			t.Fatalf("env: %v %s", err, cfg)
		}
		delete(env, EnvPrefix+"URL")
		if cfg, _ := ConfigFromEnvWith(Config{}, env); cfg.URL != "http://vendor:2" {
			t.Fatalf("TEMPO_URL must beat GROW_TEMPO_URL: %s", cfg.URL)
		}
		delete(env, "TEMPO_URL")
		if cfg, _ := ConfigFromEnvWith(Config{}, env); cfg.URL != "http://alias:3" {
			t.Fatalf("alias: %s", cfg.URL)
		}
		env[EnvPrefix+"TENANT_ID"] = "grow-tenant"
		env[EnvPrefix+"TOKEN"] = "grow-token"
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.TenantID != "grow-tenant" || cfg.Token.Reveal() != "grow-token" {
			t.Fatal("GROW_* must beat the vendor variables")
		}
		cfg, _ = ConfigFromEnvWith(Config{CommonConfig: CommonConfig{URL: "https://explicit"}, TenantID: "explicit", Token: NewSecret("explicit")}, env)
		if cfg.URL != "https://explicit" || cfg.Token.Reveal() != "explicit" || cfg.TenantID != "explicit" {
			t.Fatal("explicit must win")
		}
		cfg, _ = ConfigFromEnvWith(Config{}, map[string]string{})
		if cfg.URL != DefaultURL || cfg.RequestTimeout != DefaultRequestTimeout || cfg.TenantID != "" {
			t.Fatalf("defaults: %s", cfg)
		}
		_, err = ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "TOTAL_TIMEOUT": "forever-xyz"})
		if !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), EnvPrefix+"TOTAL_TIMEOUT") || strings.Contains(err.Error(), "forever-xyz") {
			t.Fatalf("invalid value: %v", err)
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "URL": "ftp://nope"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("invalid url: %v", err)
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{EnvPrefix + "TENANT_ID": "bad/tenant"}); !errors.Is(err, ErrConfig) {
			t.Fatalf("invalid tenant: %v", err)
		}
		aliases := map[string]string{
			"GROW_TEMPO_CONNECT_TIMEOUT": "2s", "GROW_TEMPO_REQUEST_TIMEOUT": "3s",
			"GROW_TEMPO_TOTAL_TIMEOUT": "9s", "GROW_TEMPO_MAX_RETRIES": "4",
		}
		cfg, err = ConfigFromEnvWith(Config{}, aliases)
		if err != nil || cfg.ConnectTimeout != 2*time.Second || cfg.RequestTimeout != 3*time.Second ||
			cfg.TotalTimeout != 9*time.Second || cfg.Retry == nil || cfg.Retry.MaxRetries != 4 {
			t.Fatalf("timeout/retry aliases: %v %s", err, cfg)
		}
		aliases[EnvPrefix+"REQUEST_TIMEOUT"] = "5s"
		aliases[EnvPrefix+"MAX_RETRIES"] = "1"
		if cfg, _ := ConfigFromEnvWith(Config{}, aliases); cfg.RequestTimeout != 5*time.Second || cfg.Retry.MaxRetries != 1 {
			t.Fatalf("GROW_GRAFANA_TEMPO_* must beat the aliases: %s", cfg)
		}
		for _, bad := range []string{"GROW_TEMPO_CONNECT_TIMEOUT", "GROW_TEMPO_MAX_RETRIES"} {
			if _, err := ConfigFromEnvWith(Config{}, map[string]string{bad: "nope-xyz"}); !errors.Is(err, ErrConfig) || strings.Contains(err.Error(), "nope-xyz") {
				t.Fatalf("invalid alias %s: %v", bad, err)
			}
		}
		t.Setenv(EnvPrefix+"URL", "http://process-env:9")
		if cfg, err := ConfigFromEnv(); err != nil || cfg.URL != "http://process-env:9" {
			t.Fatalf("process env: %v %s", err, cfg.URL)
		}
	})

	t.Run("tenant validation", func(t *testing.T) {
		for _, ok := range []string{"", "tenant-a", "a|b", "A1!-_.*'()"} {
			if err := (Config{TenantID: ok}).Validate(); err != nil {
				t.Errorf("%q: %v", ok, err)
			}
		}
		for _, bad := range []string{"bad/tenant", "a||b", "..", strings.Repeat("x", 151), "sp ace"} {
			if err := (Config{TenantID: bad}).Validate(); !errors.Is(err, ErrConfig) {
				t.Errorf("%q: %v", bad, err)
			}
		}
	})

	t.Run("core.redaction.config", func(t *testing.T) {
		cfg := Config{
			CommonConfig: CommonConfig{URL: "https://h", Headers: map[string]string{"X-Api-Key": "hdr-xyz"}},
			Token:        NewSecret("tok-xyz"), TenantID: "tenant-a",
		}
		forms := fmt.Sprintf("%v|%+v|%#v|%s", cfg, cfg, cfg, cfg.String())
		if strings.Contains(forms, "tok-xyz") || strings.Contains(forms, "hdr-xyz") || !strings.Contains(forms, "Token:"+Redacted) ||
			!strings.Contains(forms, `TenantID:"tenant-a"`) {
			t.Fatalf("forms: %s", forms)
		}
	})

	t.Run("invalid config", func(t *testing.T) {
		if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: "mailto:x"}}); !errors.Is(err, ErrConfig) {
			t.Fatalf("NewClient: %v", err)
		}
		if _, err := NewClient(Config{CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad TLS: %v", err)
		}
	})
}

func TestClientSurface(t *testing.T) {
	ts := fixtureServer(t, "ready.ok", "searchTags.v2-scope-intrinsic", "buildInfo.ok", "echo.ok")

	t.Run("health ping version", func(t *testing.T) {
		c := newTempo(t, ts.URL, func(cfg *Config) { cfg.TenantID = "tenant-a"; cfg.Token = NewSecret(testToken) })
		hs, err := c.Health(t.Context())
		if err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["ready"] != "ready" {
			t.Fatalf("health %+v %v", hs, err)
		}
		before := ts.calls.Load()
		if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		req := ts.request(int(before))
		if req.URL.Path != "/api/v2/search/tags" || req.URL.Query().Get("scope") != "intrinsic" ||
			req.Header.Get(TenantHeader) != "tenant-a" || req.Header.Get("Authorization") != "Bearer "+testToken ||
			req.Header.Get("User-Agent") != UserAgent {
			t.Fatalf("ping request %s %v", req.URL, req.Header)
		}
		if v, err := c.ServerVersion(t.Context()); err != nil || v != "3.1.1" {
			t.Fatalf("version %q %v", v, err)
		}
		if c.Config().URL != ts.URL || c.Query() == nil {
			t.Fatal("accessors")
		}
	})

	t.Run("no tenant header without tenant", func(t *testing.T) {
		c := newTempo(t, ts.URL, nil)
		before := ts.calls.Load()
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		if h := ts.request(int(before)).Header; h.Get(TenantHeader) != "" || h.Get("Authorization") != "" {
			t.Fatalf("headers %v", h)
		}
	})

	t.Run("missing tenant on a multi-tenant server", func(t *testing.T) {
		mt := fixtureServer(t, "searchTags.mt-no-tenant-401")
		_, err := newTempo(t, mt.URL, nil).Ping(t.Context())
		e := asError(t, err)
		if !errors.Is(err, ErrAuthentication) || e.HTTPStatus != 401 || e.Message != "ping: tenant required (X-Scope-OrgID) (HTTP 401)" {
			t.Fatalf("err %#v", e)
		}
		proxy := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			w.WriteHeader(http.StatusUnauthorized)
		})
		_, err = newTempo(t, proxy.URL, nil).Ping(t.Context())
		if !errors.Is(err, ErrAuthentication) || strings.Contains(err.Error(), "tenant") {
			t.Fatalf("proxy 401: %v", err)
		}
	})

	t.Run("health unhealthy and unreachable", func(t *testing.T) {
		starting := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte("Ingester not ready: waiting for 15s after being ready\n"))
		})
		hs, err := newTempo(t, starting.URL, nil).Health(t.Context())
		if err != nil || hs.Healthy || hs.Status != "unhealthy" || hs.Details["httpStatus"] != "503" ||
			hs.Details["reason"] != "Ingester not ready: waiting for 15s after being ready" {
			t.Fatalf("hs=%+v err=%v", hs, err)
		}
		if starting.calls.Load() != 1 {
			t.Fatal("health must not retry")
		}
	})

	t.Run("core.health.unreachable", func(t *testing.T) {
		c := newTempo(t, "http://"+closedAddr(t), func(cfg *Config) { cfg.ConnectTimeout = time.Second })
		if hs, err := c.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unreachable" {
			t.Fatalf("hs=%+v err=%v", hs, err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := c.Health(ctx); !errors.Is(err, ErrCancelled) {
			t.Fatalf("cancelled: %v", err)
		}
	})

	t.Run("server version fallbacks", func(t *testing.T) {
		text := fixtureServer(t, "serverVersion.status-version-text")
		if v, err := newTempo(t, text.URL, nil).ServerVersion(t.Context()); err != nil || v != "3.1.1" {
			t.Fatalf("status/version: %q %v", v, err)
		}
		empty := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if r.URL.Path == "/api/status/buildinfo" {
				_, _ = w.Write([]byte(`{"version":""}`))
				return
			}
			_, _ = w.Write([]byte("tempo, version v2.9.0 (branch: main)"))
		})
		if v, err := newTempo(t, empty.URL, nil).ServerVersion(t.Context()); err != nil || v != "2.9.0" {
			t.Fatalf("empty buildinfo: %q %v", v, err)
		}
		none := fixtureServer(t)
		if _, err := newTempo(t, none.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("no version: %v", err)
		}
		garbage := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if r.URL.Path == "/status/version" {
				_, _ = w.Write([]byte("hello"))
				return
			}
			w.WriteHeader(http.StatusNotFound)
		})
		if _, err := newTempo(t, garbage.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("garbage: %v", err)
		}
		broken := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			if r.URL.Path == "/status/version" {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			w.WriteHeader(http.StatusNotFound)
		})
		if _, err := newTempo(t, broken.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrPermissionDenied) {
			t.Fatalf("status/version 403: %v", err)
		}
		down := newTestServer(t, statusHandler(http.StatusInternalServerError))
		if _, err := newTempo(t, down.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) }).ServerVersion(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("500: %v", err)
		}
	})

	t.Run("core.close.idempotent", func(t *testing.T) {
		c := newTempo(t, ts.URL, nil)
		first, second := c.Close(), c.Close()
		if first != nil || second != nil {
			t.Fatal("close")
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
	})

	t.Run("from env", func(t *testing.T) {
		t.Setenv(EnvPrefix+"URL", ts.URL)
		t.Setenv(EnvPrefix+"TENANT_ID", "tenant-a")
		c, err := NewClientFromEnv()
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = c.Close() }()
		if _, err := c.Ping(t.Context()); err != nil || c.Config().TenantID != "tenant-a" {
			t.Fatal(err)
		}
		t.Setenv(EnvPrefix+"URL", "mailto:x")
		if _, err := NewClientFromEnv(); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad env: %v", err)
		}
	})
}
