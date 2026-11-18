package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.ConfigException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.Hooks;
import codes.grow.packages.tempo.core.Logger;
import codes.grow.packages.tempo.core.Redaction;
import codes.grow.packages.tempo.core.RetryPolicy;
import codes.grow.packages.tempo.core.Secret;
import codes.grow.packages.tempo.core.TlsOptions;
import codes.grow.packages.tempo.core.TransportConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable configuration of a {@link TempoClient} (SPEC section 2.2).
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; SDK variable ({@link EnvReader#DEFAULT_PREFIX} + name)
 * &gt; vendor variables &gt; default. {@link #toString()} never prints credentials.
 *
 * <p>Tempo has no authentication of its own: put an authenticating reverse proxy in front of it and
 * pass its credentials with {@link Builder#header(String, String)} (sensitive headers are redacted
 * everywhere). Environment: {@code GROW_GRAFANA_TEMPO_URL}, then {@code TEMPO_URL} and {@code
 * GROW_TEMPO_URL}; {@code GROW_GRAFANA_TEMPO_TENANT_ID}, then {@code TEMPO_TENANT_ID} and {@code
 * GROW_TEMPO_TENANT_ID}; {@code GROW_GRAFANA_TEMPO_TOKEN}, then {@code GROW_TEMPO_TOKEN}; the
 * common SPEC variables ({@code GROW_GRAFANA_TEMPO_REQUEST_TIMEOUT}, …) with {@code GROW_TEMPO_*}
 * aliases for TLS and proxy.
 */
public final class TempoConfig {
  /** Default endpoint of the query API (HTTP port 3200). */
  public static final String DEFAULT_URL = "http://127.0.0.1:3200";

  /**
   * Header that selects the tenant on a multi-tenant Tempo (query and OTLP ingest; gRPC metadata
   * {@code x-scope-orgid}). A single-tenant Tempo ignores it.
   */
  public static final String TENANT_HEADER = "X-Scope-OrgID";

  /** Vendor variables read for {@code url} after the SDK variable {@code URL}, in order. */
  static final String[] URL_VENDOR_VARS = {"TEMPO_URL", "GROW_TEMPO_URL"};

  /** Vendor variables read for {@code tenantId} after {@code TENANT_ID}, in order. */
  static final String[] TENANT_VENDOR_VARS = {"TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"};

  /** Vendor variables read for {@code token} after the SDK variable {@code TOKEN}, in order. */
  static final String[] TOKEN_VENDOR_VARS = {"GROW_TEMPO_TOKEN"};

  /** Vendor variables read for the CA bundle after the SDK variable {@code CA_CERT}. */
  static final String[] CA_CERT_VENDOR_VARS = {"GROW_TEMPO_CA_CERT"};

  private static final int MAX_TENANT_LENGTH = 150;
  private static final String TENANT_SPECIALS = "!-_.*'()";

  private final String url;
  private final String tenantId;
  private final Secret token;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private TempoConfig(Builder b) {
    this.url = b.url != null ? b.url : DEFAULT_URL;
    this.tenantId = b.tenantId;
    this.token = b.token;
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value (the message names the variable,
   *     never the value)
   */
  public static TempoConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static TempoConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Checks URL scheme, positive timeouts, retry limits and TLS pairs.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    URI u;
    try {
      u = new URI(url);
    } catch (URISyntaxException e) {
      throw new ConfigException("config: url is not a valid URL");
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null) {
      throw new ConfigException("config: url must use http or https and include a host");
    }
    validateTenantId(tenantId);
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("totalTimeout", totalTimeout);
    tls.validate();
    if (proxy != null) {
      try {
        URI p = new URI(proxy);
        if (p.getHost() == null) {
          throw new ConfigException("config: proxy must be an absolute URL");
        }
      } catch (URISyntaxException e) {
        throw new ConfigException("config: proxy is not a valid URL");
      }
    }
  }

  /**
   * Checks a Tempo tenant id: per tenant 1-150 characters of letters, digits and {@code !-_.*'()}
   * (not {@code .} or {@code ..}); {@code |} joins tenants of a federated query.
   *
   * @param tenantId tenant id, or {@code null} (no tenant)
   * @throws ConfigException when the id is invalid (the message never repeats the value)
   */
  public static void validateTenantId(String tenantId) {
    if (tenantId == null) {
      return;
    }
    for (String t : tenantId.split("\\|", -1)) {
      if (t.isEmpty() || t.length() > MAX_TENANT_LENGTH || t.equals(".") || t.equals("..")) {
        throw new ConfigException("config: tenantId must be 1-150 characters per tenant");
      }
      for (int i = 0; i < t.length(); i++) {
        char c = t.charAt(i);
        boolean ok =
            (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || TENANT_SPECIALS.indexOf(c) >= 0;
        if (!ok) {
          throw new ConfigException(
              "config: tenantId may contain only letters, digits and !-_.*'() (| between tenants)");
        }
      }
    }
  }

  private static void requirePositive(String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      throw new ConfigException("config: " + name + " must be positive");
    }
  }

  /**
   * Translates the common fields into transport settings.
   *
   * @return the transport configuration
   */
  public TransportConfig toTransportConfig() {
    Map<String, String> sent = new LinkedHashMap<>(headers);
    if (tenantId != null) {
      sent.keySet().removeIf(k -> k.equalsIgnoreCase(TENANT_HEADER));
      sent.put(TENANT_HEADER, tenantId);
    }
    if (token != null && !token.isCleared()) {
      sent.keySet().removeIf(k -> k.equalsIgnoreCase("Authorization"));
      sent.put("Authorization", "Bearer " + token.reveal());
    }
    return TransportConfig.builder(url)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(sent)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(
            () -> token == null || token.isCleared() ? List.of() : List.of(token.reveal()))
        .build();
  }

  /**
   * Returns the endpoint base URL.
   *
   * @return the URL
   */
  public String url() {
    return url;
  }

  /**
   * Returns the tenant sent as {@code X-Scope-OrgID} on every query call.
   *
   * @return the tenant id, or empty (no header)
   */
  public Optional<String> tenantId() {
    return Optional.ofNullable(tenantId);
  }

  /**
   * Returns the bearer token for an authenticating reverse proxy (Tempo itself has no auth).
   *
   * @return the token, or empty
   */
  public Optional<Secret> token() {
    return Optional.ofNullable(token);
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  @Override
  public String toString() {
    return "TempoConfig{url="
        + Redaction.redactUrl(url)
        + ", tenantId="
        + tenantId
        + ", token="
        + (token == null ? "null" : Redaction.REDACTED)
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private String url;
    private String tenantId;
    private Secret token;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    /**
     * Fills every field that was not set explicitly from the environment.
     *
     * @param env environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader env) {
      if (url == null) {
        url = env.string("URL", URL_VENDOR_VARS).orElse(null);
      }
      if (tenantId == null) {
        tenantId = env.string("TENANT_ID", TENANT_VENDOR_VARS).map(String::trim).orElse(null);
      }
      if (token == null) {
        token = env.string("TOKEN", TOKEN_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (connectTimeout == null) {
        connectTimeout = env.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = env.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = env.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && env.integer("MAX_RETRIES").isPresent()) {
        int n = env.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        env.string("CA_CERT", CA_CERT_VENDOR_VARS).ifPresent(p -> t.caCertPath(Paths.get(p)));
        env.string("CLIENT_CERT", "GROW_TEMPO_CLIENT_CERT")
            .ifPresent(p -> t.clientCertPath(Paths.get(p)));
        env.string("CLIENT_KEY", "GROW_TEMPO_CLIENT_KEY")
            .ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        env.bool("INSECURE_SKIP_VERIFY", "GROW_TEMPO_INSECURE_SKIP_VERIFY")
            .ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = env.string("PROXY", "GROW_TEMPO_PROXY").orElse(null);
      }
      return this;
    }

    /**
     * Sets the endpoint base URL.
     *
     * @param url {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the tenant sent as {@code X-Scope-OrgID} on every query call. Required by a multi-tenant
     * Tempo, ignored by a single-tenant one; {@code "a|b"} runs a federated query.
     *
     * @param tenantId tenant id or {@code null}
     * @return this builder
     */
    public Builder tenantId(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    /**
     * Sets a bearer token sent as {@code Authorization: Bearer <token>}; only useful behind an
     * authenticating reverse proxy (Tempo itself has no authentication).
     *
     * @param token token or {@code null}
     * @return this builder
     */
    public Builder token(Secret token) {
      this.token = token;
      return this;
    }

    /**
     * Sets a bearer token for an authenticating reverse proxy.
     *
     * @param token token or {@code null}
     * @return this builder
     */
    public Builder token(String token) {
      this.token = token == null ? null : Secret.of(token);
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every request.
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public TempoConfig build() {
      TempoConfig c = new TempoConfig(this);
      c.validate();
      return c;
    }
  }
}
