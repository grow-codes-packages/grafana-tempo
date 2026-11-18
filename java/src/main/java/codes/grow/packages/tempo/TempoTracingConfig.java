package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.ConfigException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.TlsOptions;
import codes.grow.packages.tempo.tracing.TracingConfig;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tempo tracing settings whose unset fields come from the environment, then Tempo defaults. {@link
 * #resolve(EnvReader)} turns it into the generic {@link TracingConfig} used by {@link
 * TempoTracing#initTracing(TempoTracingConfig)}.
 *
 * <p>Precedence per field: explicit &gt; {@code GROW_GRAFANA_TEMPO_OTLP_*} &gt; {@code
 * OTEL_EXPORTER_OTLP_TRACES_*} &gt; {@code OTEL_EXPORTER_OTLP_*} &gt; default.
 *
 * <ul>
 *   <li>Endpoint: {@code GROW_GRAFANA_TEMPO_OTLP_ENDPOINT}, {@code
 *       OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} (used as is), {@code OTEL_EXPORTER_OTLP_ENDPOINT}
 *       ({@code /v1/traces} appended for HTTP); default {@code http://127.0.0.1:4318} ({@code
 *       :4317} for gRPC).
 *   <li>Protocol: {@code GROW_GRAFANA_TEMPO_OTLP_PROTOCOL}, {@code
 *       OTEL_EXPORTER_OTLP_TRACES_PROTOCOL}, {@code OTEL_EXPORTER_OTLP_PROTOCOL}; default {@code
 *       http/protobuf} ({@code native} also means OTLP over HTTP: Tempo has no other protocol).
 *   <li>Headers: {@code OTEL_EXPORTER_OTLP_TRACES_HEADERS} or {@code OTEL_EXPORTER_OTLP_HEADERS}
 *       ({@code k=v,k2=v2}) for names not set explicitly.
 *   <li>Tenant ({@code X-Scope-OrgID}): explicit {@code tenantId}, an explicit header, then {@code
 *       GROW_GRAFANA_TEMPO_TENANT_ID}, {@code TEMPO_TENANT_ID}, {@code GROW_TEMPO_TENANT_ID}.
 *   <li>Compression: {@code GROW_GRAFANA_TEMPO_OTLP_COMPRESSION}, {@code
 *       OTEL_EXPORTER_OTLP_TRACES_COMPRESSION}, {@code OTEL_EXPORTER_OTLP_COMPRESSION}.
 *   <li>Service name: {@code OTEL_SERVICE_NAME}, then {@code service.name} in {@code
 *       OTEL_RESOURCE_ATTRIBUTES}; the other resource attributes fill unset ones.
 *   <li>Sampler: {@code OTEL_TRACES_SAMPLER} and {@code OTEL_TRACES_SAMPLER_ARG}.
 * </ul>
 *
 * <p>A multi-tenant Tempo answers an export without a tenant with HTTP 503 / gRPC {@code
 * Unavailable}, which the exporter retries until it drops the spans: set the tenant whenever Tempo
 * runs with multi-tenancy.
 */
public final class TempoTracingConfig {
  /** Default OTLP/HTTP endpoint (Tempo's OTLP HTTP receiver). */
  public static final String DEFAULT_OTLP_HTTP_ENDPOINT = "http://127.0.0.1:4318";

  /** Default OTLP/gRPC endpoint (Tempo's OTLP gRPC receiver). */
  public static final String DEFAULT_OTLP_GRPC_ENDPOINT = "http://127.0.0.1:4317";

  private final Builder b;

  private TempoTracingConfig(Builder b) {
    this.b = b.copy();
  }

  /**
   * Starts an empty builder (every field from the environment or the default).
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Starts a builder with a service name.
   *
   * @param serviceName {@code service.name}
   * @return builder
   */
  public static Builder builder(String serviceName) {
    return new Builder().serviceName(serviceName);
  }

  /**
   * Takes every field of a generic config as explicit; only an unset endpoint and a missing tenant
   * header are filled from the environment.
   *
   * @param config generic config
   * @return Tempo config
   */
  public static TempoTracingConfig from(TracingConfig config) {
    Builder b =
        builder(config.serviceName())
            .serviceVersion(config.serviceVersion().orElse(null))
            .environment(config.environment().orElse(null))
            .resourceAttributes(config.resourceAttributes())
            .endpoint(config.endpoint().orElse(null))
            .protocol(config.protocol())
            .headers(config.headers())
            .sampler(config.sampler())
            .samplerRatio(config.samplerRatio())
            .propagators(config.propagators())
            .batch(config.batch())
            .tls(config.tls().orElse(null))
            .compression(config.compression())
            .setGlobal(config.setGlobal());
    return b.build();
  }

  /**
   * The explicit tenant.
   *
   * @return tenant id, if set
   */
  public Optional<String> tenantId() {
    return Optional.ofNullable(b.tenantId);
  }

  /**
   * Resolves the settings against the process environment.
   *
   * @return generic tracing config
   * @throws ConfigException naming an invalid variable (never its value)
   */
  public TracingConfig resolve() {
    return resolve(EnvReader.system());
  }

  /**
   * Resolves the settings against an environment reader.
   *
   * @param env environment ({@link EnvReader#of(Map)} in tests)
   * @return generic tracing config
   * @throws ConfigException naming an invalid variable (never its value)
   */
  public TracingConfig resolve(EnvReader env) {
    TracingConfig.Protocol protocol = b.protocol;
    if (protocol == null) {
      protocol =
          env.string(
                  "OTLP_PROTOCOL",
                  "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
                  "OTEL_EXPORTER_OTLP_PROTOCOL")
              .map(
                  v ->
                      parseProtocol(
                          v,
                          env.source(
                                  "OTLP_PROTOCOL",
                                  "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
                                  "OTEL_EXPORTER_OTLP_PROTOCOL")
                              .orElse("protocol")))
              .orElse(TracingConfig.Protocol.HTTP_PROTOBUF);
    }
    if (protocol == TracingConfig.Protocol.NATIVE) {
      protocol = TracingConfig.Protocol.HTTP_PROTOBUF;
    }
    boolean grpc = protocol == TracingConfig.Protocol.GRPC;

    String endpoint = b.endpoint;
    if (endpoint == null) {
      Optional<String> direct = env.string("OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
      Optional<String> base = env.string("", "OTEL_EXPORTER_OTLP_ENDPOINT");
      if (direct.isPresent()) {
        endpoint = direct.get().trim();
      } else if (base.isPresent()) {
        endpoint = grpc ? base.get().trim() : appendTracesPath(base.get().trim());
      } else {
        endpoint = grpc ? DEFAULT_OTLP_GRPC_ENDPOINT : DEFAULT_OTLP_HTTP_ENDPOINT;
      }
    }

    Map<String, String> headers = new LinkedHashMap<>(b.headers);
    String headerVar =
        env.source("", "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS")
            .orElse(null);
    if (headerVar != null) {
      Map<String, String> parsed =
          parseList(
              env.string("", headerVar).orElse(""), headerVar + " is not a valid header list");
      parsed.forEach(
          (k, v) -> {
            if (!hasKey(headers, k)) {
              headers.put(k, v);
            }
          });
    }
    String tenant = b.tenantId;
    if (tenant == null && !hasKey(b.headers, TempoConfig.TENANT_HEADER)) {
      tenant =
          env.string("TENANT_ID", TempoConfig.TENANT_VENDOR_VARS).map(String::trim).orElse(null);
    }
    if (tenant != null) {
      headers.keySet().removeIf(k -> k.equalsIgnoreCase(TempoConfig.TENANT_HEADER));
      if (!tenant.isEmpty()) {
        headers.put(TempoConfig.TENANT_HEADER, tenant);
      }
    }
    for (Map.Entry<String, String> h : headers.entrySet()) {
      if (h.getKey().equalsIgnoreCase(TempoConfig.TENANT_HEADER)) {
        TempoConfig.validateTenantId(h.getValue());
      }
    }

    TracingConfig.Compression compression = b.compression;
    if (compression == null) {
      String[] vars = {"OTEL_EXPORTER_OTLP_TRACES_COMPRESSION", "OTEL_EXPORTER_OTLP_COMPRESSION"};
      Optional<String> v = env.string("OTLP_COMPRESSION", vars);
      compression =
          v.isEmpty()
              ? TracingConfig.Compression.GZIP
              : parseCompression(v.get(), env.source("OTLP_COMPRESSION", vars).orElse(""));
    }

    Map<String, Object> resource = new LinkedHashMap<>(b.resourceAttributes);
    String resourceServiceName = null;
    Optional<String> resourceVar = env.string("", "OTEL_RESOURCE_ATTRIBUTES");
    if (resourceVar.isPresent()) {
      Map<String, String> attrs =
          parseList(resourceVar.get(), "OTEL_RESOURCE_ATTRIBUTES is not a valid attribute list");
      resourceServiceName = attrs.remove("service.name");
      attrs.forEach(resource::putIfAbsent);
    }
    resource.remove("service.name");
    String serviceName = b.serviceName;
    if (serviceName == null || serviceName.isBlank()) {
      serviceName =
          env.string("", "OTEL_SERVICE_NAME").map(String::trim).orElse(resourceServiceName);
    }

    TracingConfig.SamplerType sampler = b.sampler;
    if (sampler == null) {
      sampler =
          env.string("", "OTEL_TRACES_SAMPLER")
              .map(TempoTracingConfig::parseSampler)
              .orElse(TracingConfig.SamplerType.PARENTBASED_ALWAYS_ON);
    }
    Double ratio = b.samplerRatio;
    if (ratio == null) {
      Optional<String> arg = env.string("", "OTEL_TRACES_SAMPLER_ARG");
      if (arg.isPresent()) {
        try {
          ratio = Double.parseDouble(arg.get().trim());
        } catch (NumberFormatException e) {
          throw new ConfigException("config: OTEL_TRACES_SAMPLER_ARG is not a valid number");
        }
      }
    }

    TracingConfig.Builder out =
        TracingConfig.builder(serviceName)
            .serviceVersion(b.serviceVersion)
            .environment(b.environment)
            .resourceAttributes(resource)
            .endpoint(endpoint)
            .protocol(protocol)
            .headers(headers)
            .sampler(sampler)
            .compression(compression)
            .tls(b.tls);
    if (ratio != null) {
      out.samplerRatio(ratio);
    }
    if (b.propagators != null) {
      out.propagators(b.propagators);
    }
    if (b.batch != null) {
      out.batch(b.batch);
    }
    if (b.setGlobal != null) {
      out.setGlobal(b.setGlobal);
    }
    return out.build();
  }

  private static boolean hasKey(Map<String, String> map, String name) {
    for (String k : map.keySet()) {
      if (k.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Appends {@code /v1/traces} to a base OTLP/HTTP endpoint (the OTel rule for {@code
   * OTEL_EXPORTER_OTLP_ENDPOINT}).
   *
   * @param endpoint base endpoint
   * @return traces endpoint
   */
  static String appendTracesPath(String endpoint) {
    URI u;
    try {
      u = URI.create(endpoint);
    } catch (IllegalArgumentException e) {
      return endpoint;
    }
    if (u.getHost() == null) {
      return endpoint;
    }
    return endpoint.replaceAll("/+$", "") + "/v1/traces";
  }

  /**
   * Parses the OTel list format {@code k1=v1,k2=v2} (percent-encoded keys and values).
   *
   * @param raw list text
   * @param error message of the {@link ConfigException} on a malformed list
   * @return parsed map in list order
   */
  static Map<String, String> parseList(String raw, String error) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String item : raw.split(",")) {
      String it = item.trim();
      if (it.isEmpty()) {
        continue;
      }
      int eq = it.indexOf('=');
      String key = eq < 0 ? "" : it.substring(0, eq).trim();
      if (key.isEmpty()) {
        throw new ConfigException("config: " + error);
      }
      try {
        out.put(decode(key), decode(it.substring(eq + 1).trim()));
      } catch (IllegalArgumentException e) {
        throw new ConfigException("config: " + error);
      }
    }
    return out;
  }

  private static String decode(String s) {
    return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  static TracingConfig.Protocol parseProtocol(String value, String variable) {
    String v = value.trim().toLowerCase(Locale.ROOT);
    for (TracingConfig.Protocol p : TracingConfig.Protocol.values()) {
      if (p.wireValue().equals(v)) {
        return p;
      }
    }
    throw new ConfigException(
        "config: invalid value in " + variable + " (expected grpc, http/protobuf or native)");
  }

  static TracingConfig.Compression parseCompression(String value, String variable) {
    String v = value.trim().toLowerCase(Locale.ROOT);
    for (TracingConfig.Compression c : TracingConfig.Compression.values()) {
      if (c.wireValue().equals(v)) {
        return c;
      }
    }
    throw new ConfigException("config: invalid value in " + variable + " (expected gzip or none)");
  }

  static TracingConfig.SamplerType parseSampler(String value) {
    String v = value.trim().toLowerCase(Locale.ROOT);
    for (TracingConfig.SamplerType s : TracingConfig.SamplerType.values()) {
      if (s.wireValue().equals(v)) {
        return s;
      }
    }
    throw new ConfigException("config: invalid value in OTEL_TRACES_SAMPLER");
  }

  @Override
  public String toString() {
    return "TempoTracingConfig{serviceName="
        + b.serviceName
        + ", endpoint="
        + b.endpoint
        + ", protocol="
        + (b.protocol == null ? null : b.protocol.wireValue())
        + ", tenantId="
        + b.tenantId
        + ", headers="
        + codes.grow.packages.tempo.core.Redaction.redactHeaders(b.headers)
        + "}";
  }

  /** Builder of {@link TempoTracingConfig}; every field is optional. */
  public static final class Builder {
    private String serviceName;
    private String serviceVersion;
    private String environment;
    private Map<String, Object> resourceAttributes = new LinkedHashMap<>();
    private String endpoint;
    private TracingConfig.Protocol protocol;
    private Map<String, String> headers = new LinkedHashMap<>();
    private String tenantId;
    private TracingConfig.SamplerType sampler;
    private Double samplerRatio;
    private List<String> propagators;
    private TracingConfig.BatchSettings batch;
    private TlsOptions tls;
    private TracingConfig.Compression compression;
    private Boolean setGlobal;

    private Builder() {}

    private Builder copy() {
      Builder c = new Builder();
      c.serviceName = serviceName;
      c.serviceVersion = serviceVersion;
      c.environment = environment;
      c.resourceAttributes = new LinkedHashMap<>(resourceAttributes);
      c.endpoint = endpoint;
      c.protocol = protocol;
      c.headers = new LinkedHashMap<>(headers);
      c.tenantId = tenantId;
      c.sampler = sampler;
      c.samplerRatio = samplerRatio;
      c.propagators = propagators == null ? null : new ArrayList<>(propagators);
      c.batch = batch;
      c.tls = tls;
      c.compression = compression;
      c.setGlobal = setGlobal;
      return c;
    }

    /**
     * Sets {@code service.name} (default {@code OTEL_SERVICE_NAME}).
     *
     * @param serviceName name
     * @return this builder
     */
    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /**
     * Sets {@code service.version}.
     *
     * @param serviceVersion version or {@code null}
     * @return this builder
     */
    public Builder serviceVersion(String serviceVersion) {
      this.serviceVersion = serviceVersion;
      return this;
    }

    /**
     * Sets {@code deployment.environment}.
     *
     * @param environment environment or {@code null}
     * @return this builder
     */
    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    /**
     * Replaces the extra resource attributes.
     *
     * @param attributes attributes
     * @return this builder
     */
    public Builder resourceAttributes(Map<String, ?> attributes) {
      this.resourceAttributes = new LinkedHashMap<>(attributes);
      return this;
    }

    /**
     * Sets the OTLP endpoint ({@code http://host:4318} or a full {@code …/v1/traces} URL; {@code
     * http://host:4317} for gRPC).
     *
     * @param endpoint URL or {@code null}
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets the export protocol.
     *
     * @param protocol protocol or {@code null}
     * @return this builder
     */
    public Builder protocol(TracingConfig.Protocol protocol) {
      this.protocol = protocol;
      return this;
    }

    /**
     * Replaces the export headers (for example a proxy {@code Authorization}).
     *
     * @param headers headers
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      this.headers = new LinkedHashMap<>(headers);
      return this;
    }

    /**
     * Adds an export header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the tenant sent as {@code X-Scope-OrgID} (gRPC metadata {@code x-scope-orgid}); an empty
     * string removes the header.
     *
     * @param tenantId tenant or {@code null} (environment)
     * @return this builder
     */
    public Builder tenantId(String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    /**
     * Sets the sampler.
     *
     * @param sampler sampler or {@code null}
     * @return this builder
     */
    public Builder sampler(TracingConfig.SamplerType sampler) {
      this.sampler = sampler;
      return this;
    }

    /**
     * Sets the ratio of the ratio samplers.
     *
     * @param samplerRatio between 0 and 1
     * @return this builder
     */
    public Builder samplerRatio(double samplerRatio) {
      this.samplerRatio = samplerRatio;
      return this;
    }

    /**
     * Sets the propagators.
     *
     * @param propagators names ({@code tracecontext}, {@code baggage}, {@code b3}, {@code b3multi})
     * @return this builder
     */
    public Builder propagators(List<String> propagators) {
      this.propagators = new ArrayList<>(propagators);
      return this;
    }

    /**
     * Sets the batch processor settings.
     *
     * @param batch settings
     * @return this builder
     */
    public Builder batch(TracingConfig.BatchSettings batch) {
      this.batch = batch;
      return this;
    }

    /**
     * Sets the exporter TLS options.
     *
     * @param tls options or {@code null}
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets the export compression.
     *
     * @param compression compression or {@code null}
     * @return this builder
     */
    public Builder compression(TracingConfig.Compression compression) {
      this.compression = compression;
      return this;
    }

    /**
     * Whether to register the SDK as the global OpenTelemetry (default true).
     *
     * @param setGlobal flag
     * @return this builder
     */
    public Builder setGlobal(boolean setGlobal) {
      this.setGlobal = setGlobal;
      return this;
    }

    /**
     * Builds the (unresolved) settings.
     *
     * @return settings
     */
    public TempoTracingConfig build() {
      return new TempoTracingConfig(this);
    }
  }
}
