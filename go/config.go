package tempo

import (
	"fmt"
	"strings"
	"time"
)

// DefaultURL is the default query API endpoint of Grafana Tempo (HTTP port 3200).
const DefaultURL = "http://127.0.0.1:3200"

// TenantHeader is the HTTP header (gRPC metadata key "x-scope-orgid") that selects the
// tenant on a multi-tenant Tempo. Single-tenant Tempo ignores it.
const TenantHeader = "X-Scope-OrgID"

// maxTenantIDLength is the longest tenant id Tempo accepts.
const maxTenantIDLength = 150

// vendorEnv lists the vendor variables consulted after GROW_GRAFANA_TEMPO_*, in the
// order of the service ticket (TEMPO_*, then the short GROW_TEMPO_* alias).
var vendorEnv = VendorEnv{
	URL:                []string{"TEMPO_URL", "GROW_TEMPO_URL"},
	CACert:             []string{"GROW_TEMPO_CA_CERT"},
	ClientCert:         []string{"GROW_TEMPO_CLIENT_CERT"},
	ClientKey:          []string{"GROW_TEMPO_CLIENT_KEY"},
	InsecureSkipVerify: []string{"GROW_TEMPO_INSECURE_SKIP_VERIFY"},
	Proxy:              []string{"GROW_TEMPO_PROXY"},
}

// vendorCommonAliases are the GROW_TEMPO_* aliases of the common settings that the
// generated VendorEnv has no slot for (timeouts and retries).
var vendorCommonAliases = struct {
	ConnectTimeout, RequestTimeout, TotalTimeout, MaxRetries []string
}{
	ConnectTimeout: []string{"GROW_TEMPO_CONNECT_TIMEOUT"},
	RequestTimeout: []string{"GROW_TEMPO_REQUEST_TIMEOUT"},
	TotalTimeout:   []string{"GROW_TEMPO_TOTAL_TIMEOUT"},
	MaxRetries:     []string{"GROW_TEMPO_MAX_RETRIES"},
}

// vendorTokenEnv lists vendor variables for the token, in precedence order.
var vendorTokenEnv = []string{"GROW_TEMPO_TOKEN"}

// vendorTenantEnv lists vendor variables for the tenant id, in precedence order.
var vendorTenantEnv = []string{"TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"}

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
//
// Tempo itself has no authentication. Token and Headers exist for an authenticating
// reverse proxy in front of Tempo; both are redacted everywhere.
type Config struct {
	CommonConfig

	// TenantID is sent as X-Scope-OrgID on every query (env GROW_GRAFANA_TEMPO_TENANT_ID,
	// TEMPO_TENANT_ID). Required by a multi-tenant Tempo, ignored by a single-tenant
	// one. Federated queries use "tenant-a|tenant-b".
	TenantID string

	// Token is sent as "Authorization: Bearer <token>" when set (env
	// GROW_GRAFANA_TEMPO_TOKEN); only useful behind an authenticating proxy.
	Token Secret
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_GRAFANA_TEMPO_* > vendor
// variables (TEMPO_URL, TEMPO_TENANT_ID, GROW_TEMPO_*) > defaults. env replaces the
// process environment when non-nil.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	if err := c.applyAliasEnv(r); err != nil {
		return Config{}, err
	}
	if c.Token.IsZero() {
		if v, ok := r.String("TOKEN", vendorTokenEnv...); ok {
			c.Token = NewSecret(v)
		}
	}
	if c.TenantID == "" {
		if v, ok := r.String("TENANT_ID", vendorTenantEnv...); ok {
			c.TenantID = strings.TrimSpace(v)
		}
	}
	c.CommonConfig = c.withDefaults(DefaultURL)
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// Validate checks the configuration and returns a CONFIG error.
func (c Config) Validate() error {
	if err := c.withDefaults(DefaultURL).validate(); err != nil {
		return err
	}
	return validateTenantID(c.TenantID)
}

// validateTenantID applies Tempo's tenant id rules (letters, digits and !-_.*'(),
// at most 150 characters per tenant; "|" separates federated tenants).
func validateTenantID(id string) error {
	if id == "" {
		return nil
	}
	for _, t := range strings.Split(id, "|") {
		if t == "" || len(t) > maxTenantIDLength || t == "." || t == ".." {
			return ConfigError("tenantId must be 1-150 characters per tenant")
		}
		for _, r := range t {
			if !isTenantRune(r) {
				return ConfigError("tenantId may contain only letters, digits and !-_.*'()")
			}
		}
	}
	return nil
}

func isTenantRune(r rune) bool {
	switch {
	case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		return true
	}
	return strings.ContainsRune("!-_.*'()", r)
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	return fmt.Sprintf("Config{%s TenantID:%q Token:%s}", c.describe(), c.TenantID, c.Token)
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }

// applyAliasEnv fills the timeouts and the retry count from their GROW_TEMPO_* aliases
// when neither an explicit value nor GROW_GRAFANA_TEMPO_* set them.
func (c *Config) applyAliasEnv(r *EnvReader) error {
	durations := []struct {
		dst     *time.Duration
		name    string
		aliases []string
	}{
		{&c.ConnectTimeout, "CONNECT_TIMEOUT", vendorCommonAliases.ConnectTimeout},
		{&c.RequestTimeout, "REQUEST_TIMEOUT", vendorCommonAliases.RequestTimeout},
		{&c.TotalTimeout, "TOTAL_TIMEOUT", vendorCommonAliases.TotalTimeout},
	}
	for _, d := range durations {
		if *d.dst != 0 {
			continue
		}
		v, ok, err := r.Duration(d.name, d.aliases...)
		if err != nil {
			return err
		}
		if ok {
			*d.dst = v
		}
	}
	if c.Retry == nil {
		n, ok, err := r.Int("MAX_RETRIES", vendorCommonAliases.MaxRetries...)
		if err != nil {
			return err
		}
		if ok {
			p := DefaultRetryPolicy()
			p.MaxRetries = n
			c.Retry = &p
		}
	}
	return nil
}
