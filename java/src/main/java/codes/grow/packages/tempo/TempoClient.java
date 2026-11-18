package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.AuthenticationException;
import codes.grow.packages.tempo.core.CancelledException;
import codes.grow.packages.tempo.core.ConfigException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.HealthCheck;
import codes.grow.packages.tempo.core.HealthStatus;
import codes.grow.packages.tempo.core.Hooks;
import codes.grow.packages.tempo.core.HttpTransport;
import codes.grow.packages.tempo.core.Logger;
import codes.grow.packages.tempo.core.Redaction;
import codes.grow.packages.tempo.core.RetryPolicy;
import codes.grow.packages.tempo.core.Secret;
import codes.grow.packages.tempo.core.TempoException;
import codes.grow.packages.tempo.core.TlsOptions;
import codes.grow.packages.tempo.core.TransportRequest;
import codes.grow.packages.tempo.core.TransportResponse;
import codes.grow.packages.tempo.core.UnsupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for Grafana Tempo's HTTP API (port 3200): health, ping, version and the query API through
 * {@link #query()}. Spans are sent with {@link TempoTracing#initTracing} (OTLP).
 *
 * <pre>{@code
 * try (TempoClient client = TempoClient.fromEnv()) {
 *   Optional<Trace> trace = client.query().getTrace(traceId);
 * }
 * }</pre>
 *
 * <p>Implements SPEC section 2.4. Thread-safe; create one per endpoint and close it when done.
 * Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 */
public final class TempoClient implements AutoCloseable {
  private static final Pattern VERSION_TEXT = Pattern.compile("version\\s+v?([0-9][^\\s(]*)");

  private final TempoConfig config;
  private final HttpTransport transport;
  private final TempoQueryClient query;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();

  private TempoClient(TempoConfig config) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
    this.query = new TempoQueryClient(this);
  }

  /**
   * Creates a client.
   *
   * @param config configuration
   * @return a new client
   * @throws ConfigException when the configuration, TLS or proxy settings are invalid
   */
  public static TempoClient create(TempoConfig config) {
    config.validate();
    return new TempoClient(config);
  }

  /**
   * Creates a client from {@code GROW_GRAFANA_TEMPO_*}, then {@code TEMPO_URL}, {@code
   * TEMPO_TENANT_ID} and the {@code GROW_TEMPO_*} aliases.
   *
   * @return a new client
   */
  public static TempoClient fromEnv() {
    return create(TempoConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public TempoConfig config() {
    return config;
  }

  /**
   * Returns the HTTP transport, for service modules built on this client.
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * Query API ({@code getTrace}, {@code search}, {@code searchTags}, {@code traceQLMetrics}, …)
   * sharing this client.
   *
   * @return the query client
   */
  public TempoQueryClient query() {
    return query;
  }

  /**
   * Registers a resource (for example a {@code TracingHandle}) closed with the client.
   *
   * @param resource the resource
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      resources.add(resource);
    }
  }

  /**
   * Readiness: {@code GET /ready} (no tenant needed). {@code ready} → healthy, status {@code ok};
   * HTTP 503 while Tempo starts (or any other answer) → {@code unhealthy} with {@code
   * details.reason}; network errors → {@code unreachable}. Never throws for an unhealthy or
   * unreachable service.
   *
   * @return the health status
   * @throws CancelledException when interrupted or closed
   */
  public HealthStatus health() {
    return HttpTransport.await(healthAsync(), "health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    CompletableFuture<TransportResponse> f =
        transport.sendAsync(
            TransportRequest.get("/ready")
                .operation("health")
                .acceptAnyStatus()
                .maxRetries(0)
                .build());
    return TempoQueryClient.link(
        f,
        f.handle(
            (r, err) -> {
              if (err != null) {
                TempoException e = HttpTransport.asSdkException(err, "health");
                if (e instanceof CancelledException || e instanceof ConfigException) {
                  throw e;
                }
                return HealthCheck.fromError(e);
              }
              return healthOf(r);
            }));
  }

  private HealthStatus healthOf(TransportResponse r) {
    String body =
        Redaction.truncate(
            Redaction.redactText(r.text().trim(), transport.config().knownSecrets()),
            Redaction.MAX_VENDOR_TEXT);
    Map<String, String> details = new LinkedHashMap<>();
    if (r.status() == 200 && body.equals("ready")) {
      details.put("ready", body);
      return new HealthStatus(true, "ok", details);
    }
    details.put("httpStatus", Integer.toString(r.status()));
    details.put("reason", body);
    return new HealthStatus(false, "unhealthy", details);
  }

  /**
   * Tenant-aware round trip: {@code GET /api/v2/search/tags?scope=intrinsic}. A multi-tenant Tempo
   * without a tenant → {@link AuthenticationException} ("tenant required").
   *
   * @return the latency
   * @throws AuthenticationException when the tenant is missing or a proxy rejects the credentials
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    CompletableFuture<TransportResponse> f =
        transport.sendAsync(
            TransportRequest.get("/api/v2/search/tags")
                .query("scope", "intrinsic")
                .operation("ping")
                .build());
    CompletableFuture<TransportResponse> mapped = TempoQueryClient.mapErrors(f, "ping", false);
    return TempoQueryClient.link(
        mapped, mapped.thenApply(r -> Duration.ofNanos(Math.max(1, System.nanoTime() - start))));
  }

  /**
   * Server version ({@code "3.1.1"}) from {@code /api/status/buildinfo}, falling back to the text
   * of {@code /status/version}. A leading {@code v} is removed.
   *
   * @return the version string
   * @throws UnsupportedException when the server reports none
   */
  public String serverVersion() {
    return HttpTransport.await(serverVersionAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverVersion()}.
   *
   * @return a future with the version
   */
  public CompletableFuture<String> serverVersionAsync() {
    CompletableFuture<BuildInfo> info = query.buildInfoAsync();
    CompletableFuture<String> out =
        info.handle(
                (bi, err) -> {
                  if (err != null) {
                    TempoException e = HttpTransport.asSdkException(err, "serverVersion");
                    if (!(e instanceof UnsupportedException)) {
                      throw e;
                    }
                    return "";
                  }
                  return bi.version();
                })
            .thenCompose(
                v -> {
                  if (!v.isEmpty()) {
                    return CompletableFuture.completedFuture(stripV(v));
                  }
                  CompletableFuture<TransportResponse> f =
                      TempoQueryClient.mapErrors(
                          transport.sendAsync(
                              TransportRequest.get("/status/version")
                                  .operation("serverVersion")
                                  .acceptStatus(404)
                                  .build()),
                          "serverVersion",
                          false);
                  return f.thenApply(TempoClient::versionFromText);
                });
    return TempoQueryClient.link(info, out);
  }

  private static String versionFromText(TransportResponse r) {
    Matcher m = r.status() == 404 ? null : VERSION_TEXT.matcher(r.text());
    if (m == null || !m.find()) {
      throw new UnsupportedException("serverVersion: the service does not report a version");
    }
    return m.group(1);
  }

  private static String stripV(String v) {
    return v.startsWith("v") ? v.substring(1) : v;
  }

  /**
   * Build information ({@code GET /api/status/buildinfo}); same as {@code query().buildInfo()}.
   *
   * @return build information
   */
  public BuildInfo buildInfo() {
    return query.buildInfo();
  }

  /**
   * Asynchronous {@link #buildInfo()}.
   *
   * @return future build information
   */
  public CompletableFuture<BuildInfo> buildInfoAsync() {
    return query.buildInfoAsync();
  }

  /**
   * {@code GET /api/echo} → {@code "echo"}; same as {@code query().echo()}.
   *
   * @return the echoed text
   */
  public String echo() {
    return query.echo();
  }

  /**
   * Asynchronous {@link #echo()}.
   *
   * @return future text
   */
  public CompletableFuture<String> echoAsync() {
    return query.echoAsync();
  }

  /**
   * {@code GET /ready} → true when ready, false while starting; throws on network errors. Same as
   * {@code query().ready()}.
   *
   * @return readiness
   */
  public boolean ready() {
    return query.ready();
  }

  /**
   * Asynchronous {@link #ready()}.
   *
   * @return future readiness
   */
  public CompletableFuture<Boolean> readyAsync() {
    return query.readyAsync();
  }

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Closes registered resources and releases connections; later calls fail with {@link
   * CancelledException}. Idempotent.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<AutoCloseable> copy;
    synchronized (resources) {
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      try {
        r.close();
      } catch (Exception ignored) {
        // best effort: close must not throw
      }
    }
    transport.close();
  }

  @Override
  public String toString() {
    return "TempoClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final TempoConfig.Builder config = TempoConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the endpoint base URL.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the tenant sent as {@code X-Scope-OrgID}.
     *
     * @param tenantId tenant id
     * @return this builder
     */
    public Builder tenantId(String tenantId) {
      config.tenantId(tenantId);
      return this;
    }

    /**
     * Sets a bearer token for an authenticating reverse proxy.
     *
     * @param token token
     * @return this builder
     */
    public Builder token(String token) {
      config.token(token);
      return this;
    }

    /**
     * Sets a bearer token for an authenticating reverse proxy.
     *
     * @param token token
     * @return this builder
     */
    public Builder token(Secret token) {
      config.token(token);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public TempoClient build() {
      return create(config.build());
    }
  }
}
