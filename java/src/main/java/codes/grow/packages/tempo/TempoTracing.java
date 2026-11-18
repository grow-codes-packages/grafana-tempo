package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.ConfigException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.tracing.OtlpExporters;
import codes.grow.packages.tempo.tracing.SpanExporterFactory;
import codes.grow.packages.tempo.tracing.Tracing;
import codes.grow.packages.tempo.tracing.TracingConfig;
import codes.grow.packages.tempo.tracing.TracingHandle;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OpenTelemetry tracing that exports to Grafana Tempo over OTLP (SPEC section 5), built on the
 * official OpenTelemetry Java SDK.
 *
 * <pre>{@code
 * TracingHandle tracing = TempoTracing.initTracing(
 *     TempoTracingConfig.builder("checkout").tenantId("team-a").build());
 * Spans.withSpan("work", () -> doWork());
 * tracing.shutdown();
 * }</pre>
 *
 * <p>Use the span helpers of {@code codes.grow.packages.tempo.tracing} ({@code Spans}, {@code
 * Traced}, {@code HttpSpans}, {@code TracingFilter}, {@code TracedHttpClient}, {@code
 * TracingInterceptor}) with the handle. Read spans back with {@link TempoQueryClient}.
 */
public final class TempoTracing {
  private TempoTracing() {}

  /**
   * Starts tracing: settings from {@code config}, then the process environment, then Tempo defaults
   * (see {@link TempoTracingConfig}). Call {@link TracingHandle#shutdown()} before the process
   * exits.
   *
   * @param config settings
   * @return handle
   * @throws ConfigException for invalid settings or variables
   */
  public static TracingHandle initTracing(TempoTracingConfig config) {
    return initTracing(config, EnvReader.system());
  }

  /**
   * {@link #initTracing(TempoTracingConfig)} with an explicit environment.
   *
   * @param config settings
   * @param env environment reader
   * @return handle
   */
  public static TracingHandle initTracing(TempoTracingConfig config, EnvReader env) {
    return Tracing.initWith(config.resolve(env), exporterFactory());
  }

  /**
   * Starts tracing from a generic config: every field is used as given; only an unset endpoint and
   * a missing {@code X-Scope-OrgID} header are filled from the environment, and {@code native}
   * means OTLP over HTTP.
   *
   * @param config generic config
   * @return handle
   */
  public static TracingHandle initTracing(TracingConfig config) {
    return initTracing(TempoTracingConfig.from(config));
  }

  /**
   * The Tempo exporter factory for {@link Tracing#initWith}: applies the Tempo defaults and builds
   * the official OTLP exporter.
   *
   * @return factory
   */
  public static SpanExporterFactory exporterFactory() {
    return TempoTracing::createExporter;
  }

  /**
   * Builds the official OTLP exporter: {@code grpc} → OTLP/gRPC (lower-case metadata, e.g. {@code
   * x-scope-orgid}); {@code http/protobuf} and {@code native} → OTLP/HTTP protobuf to {@code
   * <endpoint>/v1/traces}. An unset endpoint takes the Tempo default.
   *
   * @param config tracing config
   * @return exporter
   */
  public static SpanExporter createExporter(TracingConfig config) {
    return OtlpExporters.create(withDefaults(config));
  }

  /**
   * Applies the Tempo defaults: {@code native} → {@code http/protobuf}, unset endpoint → {@code
   * http://127.0.0.1:4318} ({@code :4317} for gRPC), gRPC header names lower-cased.
   *
   * @param config config
   * @return config with defaults
   */
  public static TracingConfig withDefaults(TracingConfig config) {
    TracingConfig.Builder b = config.toBuilder();
    TracingConfig.Protocol protocol =
        config.protocol() == TracingConfig.Protocol.NATIVE
            ? TracingConfig.Protocol.HTTP_PROTOBUF
            : config.protocol();
    b.protocol(protocol);
    boolean grpc = protocol == TracingConfig.Protocol.GRPC;
    if (config.endpoint().isEmpty()) {
      b.endpoint(
          grpc
              ? TempoTracingConfig.DEFAULT_OTLP_GRPC_ENDPOINT
              : TempoTracingConfig.DEFAULT_OTLP_HTTP_ENDPOINT);
    }
    if (grpc) {
      Map<String, String> lower = new LinkedHashMap<>();
      config.headers().forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
      b.headers(lower);
    }
    return b.build();
  }

  /**
   * Returns a copy of {@code config} that sends {@code tenantId} as {@code X-Scope-OrgID} with
   * every export. An empty or {@code null} tenant removes the header.
   *
   * @param config config
   * @param tenantId tenant
   * @return new config
   * @throws ConfigException for an invalid tenant id
   */
  public static TracingConfig withTenant(TracingConfig config, String tenantId) {
    Map<String, String> headers = new LinkedHashMap<>();
    config
        .headers()
        .forEach(
            (k, v) -> {
              if (!k.equalsIgnoreCase(TempoConfig.TENANT_HEADER)) {
                headers.put(k, v);
              }
            });
    if (tenantId != null && !tenantId.isEmpty()) {
      TempoConfig.validateTenantId(tenantId);
      headers.put(TempoConfig.TENANT_HEADER, tenantId);
    }
    return config.toBuilder().headers(headers).build();
  }
}
