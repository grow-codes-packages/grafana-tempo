package codes.grow.packages.tempo.integration;

import codes.grow.packages.tempo.TempoClient;
import codes.grow.packages.tempo.TempoQueryClient;
import codes.grow.packages.tempo.TempoTracing;
import codes.grow.packages.tempo.TempoTracingConfig;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.Poll;
import codes.grow.packages.tempo.testing.ConformanceRunner;
import codes.grow.packages.tempo.testing.tracing.TracingTarget;
import codes.grow.packages.tempo.tracing.TracingConfig;
import codes.grow.packages.tempo.tracing.TracingHandle;
import codes.grow.packages.tempo.tracing.query.Trace;
import codes.grow.packages.tempo.tracing.query.TraceQueryClient;
import codes.grow.packages.tempo.tracing.query.TraceSearchQuery;
import codes.grow.packages.tempo.tracing.query.TraceSummary;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conformance targets of this service: {@code core} is the client, {@code tracing} a Tempo
 * tracing handle plus the query client. Capability {@code dependencies} is left out: Tempo has no
 * dependency-graph API (docs/compatibility.md).
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"version", "query", "services"};

  /** How long search-based data (services, tags) may take to appear after ingest. */
  static final Duration SEARCH_VISIBILITY = Duration.ofSeconds(120);

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    String service = "grow-java-conformance-" + UUID.randomUUID().toString().substring(0, 8);
    runner.serviceName(service);
    runner.target("core", scenario -> TempoClient.builder().fromEnv(env).build());
    TempoClient client = TempoClient.builder().fromEnv(env).build();
    TraceQueryClient query = new VisibleQuery(client.query(), service);
    runner.target(
        "tracing",
        scenario -> {
          TracingHandle handle =
              TempoTracing.initTracing(
                  TempoTracingConfig.builder(service)
                      .setGlobal(false)
                      .batch(
                          new TracingConfig.BatchSettings(
                              2048, 512, Duration.ofMillis(200), Duration.ofSeconds(30)))
                      .build(),
                  env);
          return TracingTarget.of(handle, query);
        });
  }

  /**
   * Tempo serves a trace by id at once but lists a new service only about 30 s after ingest, so
   * {@code listServices} polls until the configured service shows up.
   */
  static final class VisibleQuery implements TraceQueryClient {
    private final TempoQueryClient query;
    private final String service;

    VisibleQuery(TempoQueryClient query, String service) {
      this.query = query;
      this.service = service;
    }

    @Override
    public Optional<Trace> getTrace(String traceId) {
      return query.getTrace(traceId);
    }

    @Override
    public List<TraceSummary> searchTraces(TraceSearchQuery q) {
      return query.searchTraces(q);
    }

    @Override
    public List<String> listServices() {
      return Poll.until(
          "listServices",
          SEARCH_VISIBILITY,
          Duration.ofSeconds(2),
          () -> Optional.of(query.listServices()).filter(s -> s.contains(service)));
    }

    @Override
    public List<String> listOperations(String svc) {
      return query.listOperations(svc);
    }
  }
}
