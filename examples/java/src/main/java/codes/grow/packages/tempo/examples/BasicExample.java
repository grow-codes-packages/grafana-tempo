// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.tempo.examples;

import codes.grow.packages.tempo.SearchRequest;
import codes.grow.packages.tempo.SearchResult;
import codes.grow.packages.tempo.TempoClient;
import codes.grow.packages.tempo.TempoQueryClient;
import codes.grow.packages.tempo.TempoTracing;
import codes.grow.packages.tempo.TempoTracingConfig;
import codes.grow.packages.tempo.TraceQL;
import codes.grow.packages.tempo.core.AuthenticationException;
import codes.grow.packages.tempo.core.HealthStatus;
import codes.grow.packages.tempo.tracing.SpanOptions;
import codes.grow.packages.tempo.tracing.Spans;
import codes.grow.packages.tempo.tracing.TracingHandle;
import codes.grow.packages.tempo.tracing.query.Span;
import codes.grow.packages.tempo.tracing.query.Trace;
import codes.grow.packages.tempo.tracing.query.WaitForTraceOptions;
import io.opentelemetry.api.trace.SpanKind;
import java.time.Duration;
import java.util.Map;

/**
 * End to end: sends a small trace to Tempo over OTLP, reads it back by id, then searches it with
 * TraceQL (Tempo indexes new spans for search about 30 seconds after ingest).
 *
 * <pre>
 * GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200 \
 * GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318 \
 * mvn -q compile exec:java
 * </pre>
 *
 * <p>Set {@code GROW_GRAFANA_TEMPO_TENANT_ID} for a multi-tenant Tempo.
 */
public final class BasicExample {
  private BasicExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   * @throws Exception on failure
   */
  public static void main(String[] args) throws Exception {
    // Query client: GROW_GRAFANA_TEMPO_URL / TEMPO_URL, GROW_GRAFANA_TEMPO_TENANT_ID / TEMPO_TENANT_ID.
    try (TempoClient client = TempoClient.fromEnv()) {
      System.out.println("config: " + client.config());
      HealthStatus health = client.health(); // never throws for an unreachable service
      System.out.println("health: " + health);
      if (!health.healthy()) {
        return;
      }
      try {
        System.out.println("ping: " + client.ping().toMillis() + " ms");
      } catch (AuthenticationException e) {
        System.out.println("set GROW_GRAFANA_TEMPO_TENANT_ID: " + e.getMessage());
        return;
      }
      System.out.println("server version: " + client.serverVersion());

      // Tracing: OTLP/HTTP to GROW_GRAFANA_TEMPO_OTLP_ENDPOINT (or OTEL_EXPORTER_OTLP_*),
      // with the X-Scope-OrgID tenant header when a tenant is configured.
      String service = "grow-tempo-example-" + System.currentTimeMillis() / 1000;
      TracingHandle tracing =
          TempoTracing.initTracing(
              TempoTracingConfig.builder(service).serviceVersion("1.0.0").setGlobal(false).build());
      String traceId;
      try {
        SpanOptions root =
            SpanOptions.builder()
                .tracer(tracing.tracer("example"))
                .kind(SpanKind.SERVER)
                .attribute("http.response.status_code", 200)
                .build();
        traceId =
            Spans.withSpan(
                "GET /checkout",
                root,
                () -> {
                  Spans.withSpan(
                      "charge card",
                      SpanOptions.builder().tracer(tracing.tracer("example")).build(),
                      () -> {
                        Thread.sleep(250);
                        return null;
                      });
                  return Spans.currentTraceId().orElseThrow();
                });
        tracing.forceFlush();
      } finally {
        tracing.shutdown();
      }

      TempoQueryClient query = client.query();
      Trace trace =
          query.waitForTrace(
              traceId, new WaitForTraceOptions(Duration.ofSeconds(30), Duration.ofSeconds(1), 2));
      for (Span s : trace.spans()) {
        System.out.printf("  %s %s %d us%n", s.serviceName(), s.name(), s.durationMicros());
      }

      TraceQL q =
          TraceQL.span(Map.of("resource.service.name", service))
              .and("duration", ">", "200ms")
              .select("span.http.response.status_code");
      System.out.println("TraceQL: " + q);
      for (int i = 0; i < 30; i++) {
        SearchResult result = query.search(SearchRequest.builder().traceql(q).limit(5).build());
        if (!result.traces().isEmpty()) {
          System.out.println("search found " + result.traces().get(0).traceId());
          System.out.println("services: " + query.listServices());
          return;
        }
        Thread.sleep(2000);
      }
      System.out.println("search: not indexed yet (Tempo needs about 30 s)");
    }
  }
}
