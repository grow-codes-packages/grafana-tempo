package codes.grow.packages.tempo.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.tempo.BuildInfo;
import codes.grow.packages.tempo.GetTraceOptions;
import codes.grow.packages.tempo.MetricSample;
import codes.grow.packages.tempo.MetricSeries;
import codes.grow.packages.tempo.MetricsRange;
import codes.grow.packages.tempo.MetricsResult;
import codes.grow.packages.tempo.SearchRequest;
import codes.grow.packages.tempo.SearchResult;
import codes.grow.packages.tempo.SearchTagValuesOptions;
import codes.grow.packages.tempo.SearchTagsOptions;
import codes.grow.packages.tempo.SearchTrace;
import codes.grow.packages.tempo.TagScope;
import codes.grow.packages.tempo.TagValue;
import codes.grow.packages.tempo.TempoClient;
import codes.grow.packages.tempo.TempoConfig;
import codes.grow.packages.tempo.TempoQueryClient;
import codes.grow.packages.tempo.TempoTracing;
import codes.grow.packages.tempo.TempoTracingConfig;
import codes.grow.packages.tempo.TraceQL;
import codes.grow.packages.tempo.core.AuthenticationException;
import codes.grow.packages.tempo.core.CancelledException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.HealthStatus;
import codes.grow.packages.tempo.core.UnsupportedException;
import codes.grow.packages.tempo.core.ValidationException;
import codes.grow.packages.tempo.tracing.SpanOptions;
import codes.grow.packages.tempo.tracing.Spans;
import codes.grow.packages.tempo.tracing.TracingConfig;
import codes.grow.packages.tempo.tracing.TracingHandle;
import codes.grow.packages.tempo.tracing.query.Span;
import codes.grow.packages.tempo.tracing.query.Trace;
import codes.grow.packages.tempo.tracing.query.TraceSearchQuery;
import codes.grow.packages.tempo.tracing.query.TraceSummary;
import codes.grow.packages.tempo.tracing.query.WaitForTraceOptions;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Calls every public function of the package against the real Tempo from docker/compose.yml
 * (single-tenant {@code grafana-tempo} plus multi-tenant {@code grafana-tempo-mt}). Trace by id is
 * visible at once; search, tags and metrics are polled because Tempo shows them about 30 s after
 * ingest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TempoIntegrationTest {
  private static final Duration VISIBILITY = Duration.ofSeconds(120);
  private static final WaitForTraceOptions WAIT =
      new WaitForTraceOptions(Duration.ofSeconds(30), Duration.ofMillis(500), 2);

  private Map<String, String> env;
  private EnvReader reader;
  private String run;
  private String service;
  private TempoClient client;
  private TempoQueryClient q;
  private TracingHandle tracing;
  private String traceId;
  private final List<TracingHandle> handles = new ArrayList<>();

  @BeforeAll
  void setUp() throws Exception {
    env = ConformanceTest.environment();
    assumeTrue(env.containsKey("GROW_GRAFANA_TEMPO_URL"), "run through docker/compose.yml");
    reader = EnvReader.of(env);
    run = UUID.randomUUID().toString().substring(0, 8);
    service = "grow-sdk-java-it-" + run;
    client = TempoClient.builder().fromEnv(reader).build();
    q = client.query();
    tracing =
        TempoTracing.initTracing(
            TempoTracingConfig.builder(service).setGlobal(false).build(), reader);
    handles.add(tracing);
    traceId = emit(tracing);
  }

  @AfterAll
  void tearDown() {
    handles.forEach(TracingHandle::shutdown);
  }

  private String env(String name) {
    String v = env.get(name);
    assertTrue(v != null && !v.isEmpty(), name + " missing from /bootstrap/env");
    return v;
  }

  /** Exports a two-span trace (GET /orders > load order, > 200 ms) and returns its id. */
  private String emit(TracingHandle h) throws Exception {
    Tracer tracer = h.tracer("grow-sdk-it");
    String id =
        Spans.withSpan(
            "GET /orders",
            SpanOptions.builder()
                .tracer(tracer)
                .kind(SpanKind.SERVER)
                .attribute("grow.run", run)
                .attribute("http.response.status_code", 200)
                .attribute("grow weird.key", "x y")
                .build(),
            () -> {
              Spans.addEvent("cache-miss", Map.of("n", 1));
              Spans.withSpan(
                  "load order",
                  SpanOptions.builder().tracer(tracer).attribute("grow.run", run).build(),
                  () -> {
                    Thread.sleep(250);
                    return null;
                  });
              assertEquals(Spans.currentTraceId().orElseThrow(), Spans.traceFields().get("trace_id"));
              return Spans.currentTraceId().orElseThrow();
            });
    h.forceFlush();
    return id;
  }

  private static void eventually(String what, Check check) throws Exception {
    long deadline = System.nanoTime() + VISIBILITY.toNanos();
    while (true) {
      try {
        check.run();
        return;
      } catch (AssertionError | RuntimeException e) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError(what + " did not become visible", e);
        }
        Thread.sleep(2000);
      }
    }
  }

  @FunctionalInterface
  private interface Check {
    void run() throws Exception;
  }

  @Test
  @Order(1)
  void clientSurface() throws Exception {
    HealthStatus hs = client.health();
    assertTrue(hs.healthy(), hs.toString());
    assertEquals("ok", hs.status());
    assertTrue(client.healthAsync().get().healthy());
    assertTrue(client.ping().toNanos() > 0);
    assertTrue(client.pingAsync().get().toNanos() > 0);
    String version = env("GROW_GRAFANA_TEMPO_SERVER_VERSION");
    assertEquals(version, client.serverVersion());
    assertEquals(version, client.serverVersionAsync().get());
    assertEquals(env("GROW_GRAFANA_TEMPO_URL"), client.config().url());
    try (TempoClient created = TempoClient.create(TempoConfig.fromEnv(env))) {
      assertEquals("echo", created.echo());
      assertEquals("echo", created.echoAsync().get());
      assertTrue(created.ready());
      assertTrue(created.readyAsync().get());
      assertEquals(version, created.buildInfo().version());
      assertEquals(version, created.buildInfoAsync().get().version());
    }
    try (TempoClient fromEnv = TempoClient.fromEnv()) {
      assertTrue(fromEnv.health().healthy());
    }
  }

  @Test
  @Order(2)
  void probes() throws Exception {
    String version = env("GROW_GRAFANA_TEMPO_SERVER_VERSION");
    assertEquals("echo", q.echo());
    assertEquals("echo", q.echoAsync().get());
    assertTrue(q.ready());
    assertTrue(q.readyAsync().get());
    BuildInfo info = q.buildInfo();
    assertEquals(version, info.version());
    assertFalse(info.goVersion().isEmpty());
    assertFalse(info.revision().isEmpty());
    assertEquals(version, q.buildInfoAsync().get().version());
  }

  @Test
  @Order(3)
  void seedTraceById() throws Exception {
    String seedId = env("GROW_GRAFANA_TEMPO_SEED_TRACE_ID");
    Trace t = q.getTrace(seedId).orElseThrow();
    assertEquals(seedId, t.traceId());
    assertEquals(3, t.spans().size());
    Span root = t.spans().get(0);
    Span db = t.spans().get(2);
    assertEquals("GET /seed", root.name());
    assertEquals(Span.Kind.SERVER, root.kind());
    assertEquals(Span.StatusCode.OK, root.status().code());
    assertEquals(env("GROW_GRAFANA_TEMPO_SEED_SERVICE"), root.serviceName());
    assertEquals(200L, root.attributes().get("http.response.status_code"));
    assertEquals(1, root.events().size());
    assertEquals(1_000_000, root.durationMicros());
    assertEquals(env("GROW_GRAFANA_TEMPO_SEED_CHILD_SERVICE"), db.serviceName());
    assertEquals(Span.StatusCode.ERROR, db.status().code());
    assertEquals(Optional.of("seed failure"), db.status().message());
    assertEquals(1, t.spans().get(1).links().size());
    long start = Long.parseLong(env("GROW_GRAFANA_TEMPO_SEED_START"));
    long end = Long.parseLong(env("GROW_GRAFANA_TEMPO_SEED_END"));
    Optional<Trace> ranged =
        q.getTraceAsync(
                seedId,
                GetTraceOptions.between(
                    Instant.ofEpochSecond(start - 60), Instant.ofEpochSecond(end + 60)))
            .get();
    assertEquals(3, ranged.orElseThrow().spans().size());
    assertTrue(q.getTrace("0123456789abcdef0123456789abcdef").isEmpty());
    assertTrue(q.getTraceAsync("0123456789abcdef0123456789abcdef").get().isEmpty());
    assertThrows(
        ValidationException.class,
        () ->
            q.getTrace(
                seedId,
                GetTraceOptions.between(Instant.ofEpochSecond(end), Instant.ofEpochSecond(start))));
  }

  @Test
  @Order(4)
  void otlpHttpRoundTrip() throws Exception {
    assertEquals(
        Optional.of(env("GROW_GRAFANA_TEMPO_OTLP_ENDPOINT")), tracing.config().endpoint());
    assertEquals(TracingConfig.Protocol.HTTP_PROTOBUF, tracing.config().protocol());
    Trace t = q.waitForTrace(traceId, WAIT);
    Span root = t.spans().get(0);
    Span child = t.spans().get(1);
    assertEquals("GET /orders", root.name());
    assertEquals("load order", child.name());
    assertEquals(Optional.of(root.spanId()), child.parentSpanId());
    assertEquals(service, root.serviceName());
    assertEquals(run, root.attributes().get("grow.run"));
    assertEquals("x y", root.attributes().get("grow weird.key"));
    assertEquals("cache-miss", root.events().get(0).name());
    assertTrue(child.durationMicros() >= 250_000, "child duration " + child.durationMicros());
    assertEquals(2, q.waitForTraceAsync(traceId, WAIT).get().spans().size());
  }

  @Test
  @Order(5)
  void otlpGrpcRoundTrip() throws Exception {
    TracingHandle g =
        TempoTracing.initTracing(
            TracingConfig.builder(service + "-grpc")
                .protocol(TracingConfig.Protocol.GRPC)
                .endpoint(env("GROW_GRAFANA_TEMPO_OTLP_GRPC_ENDPOINT"))
                .setGlobal(false)
                .build());
    handles.add(g);
    String id = emit(g);
    assertEquals(service + "-grpc", q.waitForTrace(id, WAIT).spans().get(0).serviceName());
  }

  @Test
  @Order(6)
  void multiTenant() throws Exception {
    String mtUrl = env("GROW_GRAFANA_TEMPO_MT_URL");
    String tenantA = env("GROW_GRAFANA_TEMPO_TENANT_ID");
    String tenantB = env("GROW_GRAFANA_TEMPO_OTHER_TENANT_ID");
    try (TempoQueryClient a =
            TempoQueryClient.create(TempoConfig.builder().url(mtUrl).tenantId(tenantA).build());
        TempoQueryClient b =
            TempoQueryClient.create(TempoConfig.builder().url(mtUrl).tenantId(tenantB).build());
        TempoClient none = TempoClient.builder().url(mtUrl).build()) {
      AuthenticationException e = assertThrows(AuthenticationException.class, none::ping);
      assertTrue(e.getMessage().contains("tenant required"), e.getMessage());
      assertTrue(none.health().healthy());
      assertTrue(a.getTrace(env("GROW_GRAFANA_TEMPO_MT_SEED_TRACE_ID")).isPresent());

      TracingHandle http =
          TempoTracing.initTracing(
              TempoTracing.withTenant(
                  TracingConfig.builder(service + "-mt")
                      .endpoint(env("GROW_GRAFANA_TEMPO_MT_OTLP_ENDPOINT"))
                      .setGlobal(false)
                      .build(),
                  tenantA));
      handles.add(http);
      String id = emit(http);
      assertEquals(service + "-mt", a.waitForTrace(id, WAIT).spans().get(0).serviceName());
      assertTrue(b.getTrace(id).isEmpty(), "tenant b must not see tenant a's trace");

      TracingHandle grpc =
          TempoTracing.initTracing(
              TempoTracingConfig.builder(service + "-mt-grpc")
                  .protocol(TracingConfig.Protocol.GRPC)
                  .endpoint(env("GROW_GRAFANA_TEMPO_MT_OTLP_GRPC_ENDPOINT"))
                  .tenantId(tenantA)
                  .setGlobal(false)
                  .build(),
              reader);
      handles.add(grpc);
      String gid = emit(grpc);
      assertEquals(service + "-mt-grpc", a.waitForTrace(gid, WAIT).spans().get(0).serviceName());
    }
  }

  @Test
  @Order(7)
  void searchTagsAndMetrics() throws Exception {
    Instant windowStart = Instant.now().minus(Duration.ofMinutes(10));
    Instant windowEnd = Instant.now().plus(Duration.ofMinutes(5));
    TraceQL query =
        TraceQL.span(Map.of("resource.service.name", service))
            .and("duration", ">", "200ms")
            .select("span.grow.run");
    eventually(
        "search",
        () -> {
          SearchResult res =
              q.search(
                  SearchRequest.builder()
                      .traceql(query)
                      .start(windowStart)
                      .end(windowEnd)
                      .limit(5)
                      .spss(5)
                      .build());
          SearchTrace hit =
              res.traces().stream()
                  .filter(t -> t.traceId().equals(traceId))
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("trace not found in " + res.traces()));
          assertEquals(2, hit.spanSets().get(0).matched());
          assertEquals(run, hit.spanSets().get(0).spans().get(0).attributes().get("grow.run"));
          assertEquals(Optional.of(service), hit.rootServiceName());
          assertFalse(q.searchAsync(SearchRequest.of(query.toString())).get().traces().isEmpty());
        });
    TraceSearchQuery tsq =
        TraceSearchQuery.builder()
            .service(service)
            .operation("GET /orders")
            .tag("grow.run", run)
            .minDurationMicros(200_000L)
            .maxDurationMicros(60_000_000L)
            .start(windowStart)
            .end(windowEnd)
            .build();
    eventually(
        "searchTraces",
        () -> {
          List<TraceSummary> got = q.searchTraces(tsq);
          assertEquals(1, got.size(), got.toString());
          assertEquals(traceId, got.get(0).traceId());
          assertEquals(2, got.get(0).spanCount());
          assertEquals(Optional.of("GET /orders"), got.get(0).rootName());
          assertEquals(got, q.searchTracesAsync(tsq).get());
        });
    eventually(
        "searchTags",
        () -> {
          Map<String, List<String>> tags =
              q.searchTags(SearchTagsOptions.builder().scope(TagScope.SPAN).query(query).build());
          assertTrue(tags.getOrDefault("span", List.of()).contains("grow.run"), tags.toString());
          assertTrue(q.searchTagsAsync(SearchTagsOptions.all()).get().containsKey("resource"));
        });
    String byService = "{ resource.service.name = " + TraceQL.quote(service) + " }";
    eventually(
        "searchTagValues",
        () -> {
          List<TagValue> vals =
              q.searchTagValues("span.grow weird.key", SearchTagValuesOptions.query(byService));
          assertEquals(List.of(new TagValue("string", "x y")), vals);
          assertEquals(
              vals,
              q.searchTagValuesAsync("span.\"grow weird.key\"", SearchTagValuesOptions.query(byService))
                  .get());
        });
    eventually(
        "listServices",
        () -> {
          List<String> services = q.listServices();
          assertTrue(services.contains(service), services.toString());
          List<String> sorted = new ArrayList<>(services);
          sorted.sort(null);
          assertEquals(sorted, services);
          assertTrue(q.listServicesAsync().get().contains(service));
        });
    eventually(
        "listOperations",
        () -> {
          List<String> ops = q.listOperations(service);
          // Tempo's filtered tag values occasionally omit child span names; the root operation
          // must be there and nothing foreign.
          assertTrue(ops.contains("GET /orders"), ops.toString());
          for (String op : ops) {
            assertTrue(op.equals("GET /orders") || op.equals("load order"), ops.toString());
          }
          assertTrue(q.listOperationsAsync(service).get().contains("GET /orders"));
        });
    eventually(
        "traceQLMetrics",
        () -> {
          MetricsResult res =
              q.traceQLMetrics(
                  byService + " | count_over_time()",
                  MetricsRange.builder()
                      .start(windowStart)
                      .end(windowEnd)
                      .step(Duration.ofSeconds(30))
                      .build());
          double total = 0;
          for (MetricSeries s : res.series()) {
            for (MetricSample p : s.samples()) {
              total += p.value();
            }
          }
          assertEquals(2.0, total, res.toString());
          assertEquals(Optional.of(Duration.ofSeconds(30)), res.step());
          MetricsResult since =
              q.traceQLMetricsAsync(
                      byService + " | rate()", MetricsRange.since(Duration.ofMinutes(15)))
                  .get();
          assertFalse(since.series().isEmpty());
        });
  }

  @Test
  @Order(8)
  void traceQLBuilderAgainstTempo() throws Exception {
    TraceQL structural =
        TraceQL.where("resource.service.name", "=", service)
            .descendant(TraceQL.where("name", "=", "load order").or("status", "=", "error"));
    eventually(
        "structural search",
        () -> {
          SearchResult res = q.search(SearchRequest.of(structural.toString()));
          assertTrue(
              res.traces().stream().anyMatch(t -> t.traceId().equals(traceId)), res.toString());
        });
    assertEquals(
        "{ span.\"grow weird.key\" = \"a \\\"q\\\" \\\\ ü\" }",
        TraceQL.where("span.grow weird.key", "=", "a \"q\" \\ ü").toString());
    assertEquals("span:duration", TraceQL.field("duration"));
    assertEquals("{}", TraceQL.check(" {} ", "check"));
  }

  @Test
  @Order(9)
  void errorsAndUnsupported() {
    assertThrows(
        ValidationException.class, () -> q.search(SearchRequest.of("{ resource.service.name = }")));
    assertThrows(ValidationException.class, () -> q.searchTagValues("service.name"));
    assertThrows(
        ValidationException.class,
        () -> q.traceQLMetrics("{}", MetricsRange.since(Duration.ofMinutes(15))));
    assertThrows(UnsupportedException.class, () -> q.getDependencies(null, null));
    ExecutionException e =
        assertThrows(ExecutionException.class, () -> q.getDependenciesAsync(null, null).get());
    assertTrue(e.getCause() instanceof UnsupportedException);
  }

  @Test
  @Order(10)
  void ownedQueryClientAndClose() {
    try (TempoQueryClient owned = TempoQueryClient.fromEnv()) {
      assertEquals("echo", owned.echo());
    }
    client.register(() -> {});
    client.close();
    client.close();
    assertTrue(client.isClosed());
    assertThrows(CancelledException.class, client::ping);
  }
}
