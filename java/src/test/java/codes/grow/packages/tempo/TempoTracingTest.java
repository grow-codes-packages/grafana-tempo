package codes.grow.packages.tempo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.tempo.core.ConfigException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.MockServer;
import codes.grow.packages.tempo.core.TlsOptions;
import codes.grow.packages.tempo.tracing.Spans;
import codes.grow.packages.tempo.tracing.TracingConfig;
import codes.grow.packages.tempo.tracing.TracingHandle;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TempoTracingTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private static TracingConfig resolve(TempoTracingConfig.Builder b, Map<String, String> env) {
    return b.build().resolve(EnvReader.of(env));
  }

  @Test
  void defaults() {
    TracingConfig c = resolve(TempoTracingConfig.builder("svc"), Map.of());
    assertThat(c.serviceName()).isEqualTo("svc");
    assertThat(c.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThat(c.endpoint()).contains("http://127.0.0.1:4318");
    assertThat(c.headers()).isEmpty();
    assertThat(c.compression()).isEqualTo(TracingConfig.Compression.GZIP);
    assertThat(c.sampler()).isEqualTo(TracingConfig.SamplerType.PARENTBASED_ALWAYS_ON);
    assertThat(c.setGlobal()).isTrue();
    TracingConfig g =
        resolve(TempoTracingConfig.builder("svc").protocol(TracingConfig.Protocol.GRPC), Map.of());
    assertThat(g.endpoint()).contains("http://127.0.0.1:4317");
    TracingConfig n =
        resolve(
            TempoTracingConfig.builder("svc").protocol(TracingConfig.Protocol.NATIVE), Map.of());
    assertThat(n.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThatThrownBy(() -> resolve(TempoTracingConfig.builder(), Map.of()))
        .isInstanceOf(ConfigException.class);
  }

  @Test
  void growVariablesWin() {
    Map<String, String> env = new HashMap<>();
    env.put(P + "OTLP_ENDPOINT", "http://grow:4318");
    env.put("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://traces:4318/v1/traces");
    env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://base:4318");
    env.put(P + "OTLP_PROTOCOL", "grpc");
    env.put("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "http/protobuf");
    env.put(P + "OTLP_COMPRESSION", "none");
    env.put("OTEL_EXPORTER_OTLP_COMPRESSION", "gzip");
    env.put(P + "TENANT_ID", "grow-tenant");
    env.put("TEMPO_TENANT_ID", "vendor");
    TracingConfig c = resolve(TempoTracingConfig.builder("svc"), env);
    assertThat(c.endpoint()).contains("http://grow:4318");
    assertThat(c.protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    assertThat(c.compression()).isEqualTo(TracingConfig.Compression.NONE);
    assertThat(c.headers()).containsEntry("X-Scope-OrgID", "grow-tenant");
  }

  @Test
  void otelVariables() {
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://traces:4318/custom");
    env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "HTTP/PROTOBUF");
    env.put("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20abc,x-plus=a+b, ,x-keep=env");
    env.put("OTEL_EXPORTER_OTLP_TRACES_COMPRESSION", "gzip");
    env.put("OTEL_SERVICE_NAME", " from-env ");
    env.put("OTEL_RESOURCE_ATTRIBUTES", "service.name=ignored,team=core,region=eu");
    env.put("OTEL_TRACES_SAMPLER", "traceidratio");
    env.put("OTEL_TRACES_SAMPLER_ARG", "0.25");
    env.put("GROW_TEMPO_TENANT_ID", "alias");
    TracingConfig c =
        resolve(
            TempoTracingConfig.builder()
                .header("X-Keep", "explicit")
                .resourceAttributes(Map.of("region", "us")),
            env);
    assertThat(c.serviceName()).isEqualTo("from-env");
    assertThat(c.endpoint()).contains("http://traces:4318/custom");
    assertThat(c.headers())
        .containsEntry("authorization", "Bearer abc")
        .containsEntry("x-plus", "a+b")
        .containsEntry("X-Keep", "explicit")
        .doesNotContainKey("x-keep")
        .containsEntry("X-Scope-OrgID", "alias");
    assertThat(c.resourceAttributes())
        .containsEntry("team", "core")
        .containsEntry("region", "us")
        .doesNotContainKey("service.name");
    assertThat(c.sampler()).isEqualTo(TracingConfig.SamplerType.TRACEIDRATIO);
    assertThat(c.samplerRatio()).isEqualTo(0.25);
    env.remove("OTEL_SERVICE_NAME");
    assertThat(resolve(TempoTracingConfig.builder(), env).serviceName()).isEqualTo("ignored");
  }

  @Test
  void baseEndpointGetsTracesPathForHttpOnly() {
    Map<String, String> env = Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://base:4318/");
    assertThat(resolve(TempoTracingConfig.builder("s"), env).endpoint())
        .contains("http://base:4318/v1/traces");
    assertThat(
            resolve(TempoTracingConfig.builder("s").protocol(TracingConfig.Protocol.GRPC), env)
                .endpoint())
        .contains("http://base:4318/");
    assertThat(TempoTracingConfig.appendTracesPath("not a url")).isEqualTo("not a url");
    assertThat(TempoTracingConfig.appendTracesPath("relative")).isEqualTo("relative");
  }

  @Test
  void tenantRules() {
    Map<String, String> env = Map.of(P + "TENANT_ID", "env");
    assertThat(resolve(TempoTracingConfig.builder("s").tenantId("explicit"), env).headers())
        .containsExactly(Map.entry("X-Scope-OrgID", "explicit"));
    assertThat(
            resolve(TempoTracingConfig.builder("s").header("x-scope-orgid", "hdr"), env).headers())
        .containsExactly(Map.entry("x-scope-orgid", "hdr"));
    assertThat(
            resolve(
                    TempoTracingConfig.builder("s").header("X-Scope-OrgID", "hdr").tenantId(""),
                    env)
                .headers())
        .isEmpty();
    assertThatThrownBy(() -> resolve(TempoTracingConfig.builder("s").tenantId("bad/t"), env))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(
            () -> resolve(TempoTracingConfig.builder("s").header("X-Scope-OrgID", "a b"), env))
        .isInstanceOf(ConfigException.class);
    assertThat(TempoTracingConfig.builder("s").tenantId("t").build().tenantId()).contains("t");
  }

  @Test
  void invalidVariablesNameTheVariableOnly() {
    String[][] cases = {
      {P + "OTLP_PROTOCOL", "http/json"},
      {"OTEL_EXPORTER_OTLP_COMPRESSION", "zstd"},
      {"OTEL_TRACES_SAMPLER", "sometimes"},
      {"OTEL_TRACES_SAMPLER_ARG", "half"},
      {"OTEL_EXPORTER_OTLP_HEADERS", "novalue"},
      {"OTEL_EXPORTER_OTLP_TRACES_HEADERS", "=x"},
      {"OTEL_EXPORTER_OTLP_HEADERS", "k=%zz"},
      {"OTEL_RESOURCE_ATTRIBUTES", "broken"},
    };
    for (String[] c : cases) {
      assertThatThrownBy(() -> resolve(TempoTracingConfig.builder("s"), Map.of(c[0], c[1])))
          .as(c[0])
          .isInstanceOf(ConfigException.class)
          .hasMessageContaining(c[0])
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("=" + c[1]));
    }
  }

  @Test
  void explicitValuesPassThrough() {
    TlsOptions tls = TlsOptions.builder().insecureSkipVerify(true).build();
    TracingConfig.BatchSettings batch =
        new TracingConfig.BatchSettings(10, 5, Duration.ofMillis(100), Duration.ofSeconds(1));
    Map<String, String> env = new HashMap<>();
    env.put("OTEL_TRACES_SAMPLER", "always_on");
    env.put("OTEL_TRACES_SAMPLER_ARG", "0.1");
    env.put(P + "OTLP_COMPRESSION", "none");
    env.put(P + "OTLP_ENDPOINT", "http://env:4318");
    TempoTracingConfig tc =
        TempoTracingConfig.builder("svc")
            .serviceVersion("1.0")
            .environment("test")
            .endpoint("https://explicit:4318/v1/traces")
            .protocol(TracingConfig.Protocol.HTTP_PROTOBUF)
            .headers(Map.of("Authorization", "Bearer secret-value"))
            .sampler(TracingConfig.SamplerType.ALWAYS_OFF)
            .samplerRatio(0.5)
            .propagators(List.of("tracecontext"))
            .batch(batch)
            .tls(tls)
            .compression(TracingConfig.Compression.GZIP)
            .setGlobal(false)
            .build();
    TracingConfig c = tc.resolve(EnvReader.of(env));
    assertThat(c.serviceVersion()).contains("1.0");
    assertThat(c.environment()).contains("test");
    assertThat(c.endpoint()).contains("https://explicit:4318/v1/traces");
    assertThat(c.sampler()).isEqualTo(TracingConfig.SamplerType.ALWAYS_OFF);
    assertThat(c.samplerRatio()).isEqualTo(0.5);
    assertThat(c.propagators()).containsExactly("tracecontext");
    assertThat(c.batch()).isEqualTo(batch);
    assertThat(c.tls()).contains(tls);
    assertThat(c.compression()).isEqualTo(TracingConfig.Compression.GZIP);
    assertThat(c.setGlobal()).isFalse();
    assertThat(tc.toString()).contains("svc").doesNotContain("secret-value");
    assertThat(TempoTracingConfig.builder().build().toString()).contains("protocol=null");
    assertThat(TempoTracingConfig.from(c).resolve(EnvReader.of(env)).endpoint())
        .contains("https://explicit:4318/v1/traces");
    TracingConfig fromGeneric =
        TempoTracingConfig.from(TracingConfig.builder("g").build())
            .resolve(EnvReader.of(Map.of(P + "OTLP_ENDPOINT", "http://env:4318")));
    assertThat(fromGeneric.endpoint()).contains("http://env:4318");
  }

  @Test
  void exporterDefaultsAndTenant() {
    TracingConfig grpc =
        TempoTracing.withDefaults(
            TracingConfig.builder("s")
                .protocol(TracingConfig.Protocol.GRPC)
                .header("X-Scope-OrgID", "t")
                .build());
    assertThat(grpc.endpoint()).contains("http://127.0.0.1:4317");
    assertThat(grpc.headers()).containsExactly(Map.entry("x-scope-orgid", "t"));
    TracingConfig nat =
        TempoTracing.withDefaults(
            TracingConfig.builder("s").protocol(TracingConfig.Protocol.NATIVE).build());
    assertThat(nat.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThat(nat.endpoint()).contains("http://127.0.0.1:4318");
    TracingConfig kept =
        TempoTracing.withDefaults(TracingConfig.builder("s").endpoint("http://x:1").build());
    assertThat(kept.endpoint()).contains("http://x:1");

    TracingConfig base = TracingConfig.builder("s").header("x-scope-orgid", "old").build();
    assertThat(TempoTracing.withTenant(base, "new").headers())
        .containsExactly(Map.entry("X-Scope-OrgID", "new"));
    assertThat(TempoTracing.withTenant(base, "").headers()).isEmpty();
    assertThat(TempoTracing.withTenant(base, null).headers()).isEmpty();
    assertThatThrownBy(() -> TempoTracing.withTenant(base, "a/b"))
        .isInstanceOf(ConfigException.class);

    SpanExporter http = TempoTracing.createExporter(TracingConfig.builder("s").build());
    assertThat(http).isInstanceOf(OtlpHttpSpanExporter.class);
    http.shutdown();
    SpanExporter g =
        TempoTracing.exporterFactory()
            .create(TracingConfig.builder("s").protocol(TracingConfig.Protocol.GRPC).build());
    assertThat(g).isInstanceOf(OtlpGrpcSpanExporter.class);
    g.shutdown();
  }

  @Test
  void initTracingExportsToTempoWithTheTenantHeader() throws Exception {
    try (MockServer otlp = MockServer.start()) {
      otlp.fallback(
          ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            MockServer.send(ex, 200, "");
          });
      TracingHandle h =
          TempoTracing.initTracing(
              TempoTracingConfig.builder("unit-svc")
                  .endpoint(otlp.url())
                  .tenantId("tenant-a")
                  .compression(TracingConfig.Compression.NONE)
                  .setGlobal(false)
                  .build(),
              EnvReader.of(Map.of()));
      Spans.withSpan("unit-span", () -> "ok");
      h.forceFlush();
      h.shutdown();
      assertThat(otlp.requests()).isNotEmpty();
      MockServer.Recorded r = otlp.requests().get(0);
      assertThat(r.method()).isEqualTo("POST");
      assertThat(r.uri()).isEqualTo("/v1/traces");
      assertThat(r.header("X-Scope-OrgID")).isEqualTo("tenant-a");
      assertThat(r.header("Content-Type")).isEqualTo("application/x-protobuf");
      assertThat(r.body()).contains("unit-span").contains("unit-svc");

      TracingHandle generic =
          TempoTracing.initTracing(
              TracingConfig.builder("generic").endpoint(otlp.url()).setGlobal(false).build());
      generic.shutdown();
      TracingHandle sys =
          TempoTracing.initTracing(
              TempoTracingConfig.builder("sys").endpoint(otlp.url()).setGlobal(false).build());
      sys.shutdown();
    }
  }
}
