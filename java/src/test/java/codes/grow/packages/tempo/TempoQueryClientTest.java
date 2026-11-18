package codes.grow.packages.tempo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.tempo.core.AuthenticationException;
import codes.grow.packages.tempo.core.CancelledException;
import codes.grow.packages.tempo.core.MockServer;
import codes.grow.packages.tempo.core.NotFoundException;
import codes.grow.packages.tempo.core.RetryPolicy;
import codes.grow.packages.tempo.core.TimeoutException;
import codes.grow.packages.tempo.core.UnavailableException;
import codes.grow.packages.tempo.core.UnsupportedException;
import codes.grow.packages.tempo.core.ValidationException;
import codes.grow.packages.tempo.tracing.query.Span;
import codes.grow.packages.tempo.tracing.query.Trace;
import codes.grow.packages.tempo.tracing.query.TraceSearchQuery;
import codes.grow.packages.tempo.tracing.query.TraceSummary;
import codes.grow.packages.tempo.tracing.query.WaitForTraceOptions;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TempoQueryClientTest {
  private static final String ID = "00f74bc1b058a1a8b85f9ae0231e4001";

  private MockServer server;
  private TempoClient client;
  private TempoQueryClient q;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    client =
        TempoClient.builder()
            .url(server.url())
            .tenantId("tenant-a")
            .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
            .build();
    q = client.query();
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  /** Path plus decoded query parameters sorted by name (the transport keeps no order). */
  private String lastUri() {
    String raw = rawLastUri();
    int qm = raw.indexOf('?');
    if (qm < 0) {
      return raw;
    }
    java.util.TreeMap<String, String> params = new java.util.TreeMap<>();
    for (String kv : raw.substring(qm + 1).split("&")) {
      int eq = kv.indexOf('=');
      params.put(
          URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
    }
    StringBuilder sb = new StringBuilder(raw.substring(0, qm));
    char sep = '?';
    for (Map.Entry<String, String> e : params.entrySet()) {
      sb.append(sep).append(e.getKey()).append('=').append(e.getValue());
      sep = '&';
    }
    return sb.toString();
  }

  private String rawLastUri() {
    List<MockServer.Recorded> r = server.requests();
    return r.get(r.size() - 1).uri();
  }

  @Test
  void getTraceV2NormalizesTheModel() {
    server.enqueue(Fixtures.replay("getTrace.v2-ok"));
    Trace t = q.getTrace(ID.toUpperCase()).orElseThrow();
    assertThat(server.requests().get(0).uri()).isEqualTo("/api/v2/traces/" + ID);
    assertThat(server.requests().get(0).header("X-Scope-OrgID")).isEqualTo("tenant-a");
    assertThat(t.traceId()).isEqualTo(ID);
    Span root = t.spans().get(0);
    assertThat(root.name()).isEqualTo("GET /orders");
    assertThat(root.spanId()).isEqualTo("a000000000000001");
    assertThat(root.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(root.status().code()).isEqualTo(Span.StatusCode.OK);
    assertThat(root.serviceName()).isEqualTo("grow-sdk-verify-179503498127460-api");
    assertThat(root.attributes())
        .containsEntry("http.response.status_code", 200L)
        .containsEntry("grow.note", "a \"quoted\" \\ value ü")
        .containsEntry("grow weird.key", "x y");
    assertThat(root.startMicros()).isEqualTo(1795035005729668L);
    assertThat(root.durationMicros()).isEqualTo(450_000);
    assertThat(root.events()).extracting(Span.Event::name).containsExactly("cache-miss");
    assertThat(t.spans().get(1).parentSpanId()).contains("a000000000000001");
    assertThat(t.spans().get(1).status().code()).isEqualTo(Span.StatusCode.UNSET);
  }

  @Test
  void getTraceSeedHasLinksAndErrors() {
    server.enqueue(Fixtures.replay("getTrace.seed-v2"));
    Trace t = q.getTrace("5b8efff798038103d269b633813fc60c").orElseThrow();
    assertThat(t.spans()).hasSize(3);
    assertThat(t.spans()).anySatisfy(s -> assertThat(s.links()).hasSize(1));
    assertThat(t.spans())
        .anySatisfy(s -> assertThat(s.status().code()).isEqualTo(Span.StatusCode.ERROR));
  }

  @Test
  void getTraceRangeAndMisses() {
    server.enqueue(Fixtures.replay("getTrace.v2-not-found"));
    assertThat(
            q.getTrace(
                "1",
                GetTraceOptions.between(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200, 5))))
        .isEmpty();
    assertThat(lastUri())
        .isEqualTo("/api/v2/traces/00000000000000000000000000000001?end=201&start=100");
    server.enqueue(200, "{\"trace\":null}");
    assertThat(q.getTrace("1", GetTraceOptions.between(null, Instant.ofEpochSecond(9)))).isEmpty();
    assertThat(lastUri()).endsWith("?end=9");
    assertThat(GetTraceOptions.none().start()).isEmpty();
  }

  @Test
  void getTraceFallsBackToV1() {
    server
        .enqueue(Fixtures.text(404, "404 page not found"))
        .enqueue(Fixtures.replay("getTrace.v1-ok"));
    Trace t = q.getTrace(ID).orElseThrow();
    assertThat(t.spans()).isNotEmpty();
    assertThat(server.requests().get(1).uri()).isEqualTo("/api/traces/" + ID);
    server
        .enqueue(Fixtures.text(404, "404 page not found"))
        .enqueue(Fixtures.replay("getTrace.v1-not-found"));
    assertThat(q.getTrace(ID)).isEmpty();
  }

  @Test
  void getTraceErrors() {
    assertThatThrownBy(() -> q.getTrace("xyz")).isInstanceOf(ValidationException.class);
    assertThat(q.getTraceAsync("0".repeat(33))).isCompletedExceptionally();
    assertThat(server.requests()).isEmpty();
    server.enqueue(Fixtures.replay("getTrace.mt-no-tenant-401"));
    assertThatThrownBy(() -> q.getTrace(ID))
        .isInstanceOf(AuthenticationException.class)
        .hasMessage("getTrace " + ID + ": tenant required (X-Scope-OrgID) (HTTP 401)");
    server.enqueue(Fixtures.replay("getTrace.v2-range-end-before-start"));
    assertThatThrownBy(() -> q.getTrace(ID)).isInstanceOf(ValidationException.class);
  }

  @Test
  void asyncVariants() throws Exception {
    server.enqueue(Fixtures.replay("getTrace.v2-ok"));
    assertThat(q.getTraceAsync(ID).get()).isPresent();
    server.enqueue(Fixtures.replay("echo.ok"));
    assertThat(q.echoAsync().get()).isEqualTo("echo");
  }

  @Test
  void waitForTracePolls() {
    server
        .enqueue(Fixtures.replay("getTrace.v2-not-found"))
        .enqueue(Fixtures.replay("getTrace.v2-ok"));
    Trace t =
        q.waitForTrace(
            ID, new WaitForTraceOptions(Duration.ofSeconds(5), Duration.ofMillis(10), 2));
    assertThat(t.spans().size()).isGreaterThanOrEqualTo(2);
    server.fallback(Fixtures.replay("getTrace.v2-not-found"));
    assertThatThrownBy(
            () ->
                q.waitForTrace(
                    ID, new WaitForTraceOptions(Duration.ofMillis(50), Duration.ofMillis(10), 1)))
        .isInstanceOf(TimeoutException.class);
  }

  @Test
  void searchMapsTraces() {
    server.enqueue(Fixtures.replay("search.spss-1"));
    SearchResult r =
        q.search(
            SearchRequest.builder()
                .traceql(TraceQL.span(Map.of("resource.service.name", "api")))
                .start(Instant.ofEpochSecond(1))
                .end(Instant.ofEpochSecond(2))
                .limit(20)
                .spss(1)
                .build());
    assertThat(lastUri())
        .isEqualTo(
            "/api/search?end=2&limit=20&q={ resource.service.name = \"api\" }&spss=1&start=1");
    assertThat(rawLastUri()).contains("q=%7B%20resource.service.name%20%3D%20%22api%22%20%7D");
    SearchTrace second = r.traces().get(1);
    assertThat(second.traceId()).isEqualTo(ID);
    assertThat(second.rootServiceName()).contains("grow-sdk-verify-179503498127460-api");
    assertThat(second.rootTraceName()).contains("GET /orders");
    assertThat(second.durationMicros()).isEqualTo(450_000);
    assertThat(second.startMicros()).isEqualTo(1795035005729668L);
    assertThat(second.spanSets().get(0).matched()).isEqualTo(3);
    SearchSpan span = second.spanSets().get(0).spans().get(0);
    assertThat(span.spanId()).isEqualTo("a000000000000001");
    assertThat(span.name()).isEmpty();
    assertThat(span.durationMicros()).isEqualTo(450_000);
    assertThat(span.attributes()).containsKey("service.name");
    assertThat(r.traces().get(0).serviceStats().values().iterator().next().errorCount())
        .isEqualTo(1);
  }

  @Test
  void searchDefaultsLegacyShapesAndMetrics() {
    server.enqueue(Fixtures.replay("search.select-name-status"));
    SearchResult r = q.search(SearchRequest.of(null));
    assertThat(lastUri()).isEqualTo("/api/search?q={}");
    assertThat(r.traces().get(0).spanSets().get(0).spans().get(0).name()).contains("GET /health");
    server.enqueue(
        200,
        "{\"traces\":[{\"traceID\":\"ab\",\"spanSet\":{\"spans\":[{\"spanID\":\"1\"}]}},{\"traceID\":\"cd\"}],"
            + "\"metrics\":{\"inspectedBytes\":\"12\",\"completedJobs\":2}}");
    r = q.search(SearchRequest.of("  "));
    assertThat(r.traces().get(0).traceId()).endsWith("00ab");
    assertThat(r.traces().get(0).spanSets().get(0).spans().get(0).spanId())
        .isEqualTo("0000000000000001");
    assertThat(r.traces().get(0).spanSets().get(0).matched()).isZero();
    assertThat(r.traces().get(1).spanSets()).isEmpty();
    assertThat(r.traces().get(1).durationMicros()).isZero();
    assertThat(r.traces().get(1).rootServiceName()).isEmpty();
    assertThat(r.metrics()).containsEntry("inspectedBytes", 12L).containsEntry("completedJobs", 2L);
    server.enqueue(200, "");
    assertThat(q.search(SearchRequest.of("{}")).traces()).isEmpty();
  }

  @Test
  void searchErrors() {
    assertThatThrownBy(() -> q.search(SearchRequest.builder().limit(0).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.search(SearchRequest.builder().spss(-1).build()))
        .isInstanceOf(ValidationException.class);
    server.enqueue(Fixtures.replay("search.invalid-traceql-400"));
    assertThatThrownBy(() -> q.search(SearchRequest.of("{ bad")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("invalid TraceQL query");
    server.enqueue(Fixtures.replay("search.mt-no-tenant-401"));
    assertThatThrownBy(() -> q.search(SearchRequest.of("{}")))
        .isInstanceOf(AuthenticationException.class)
        .hasMessageContaining("tenant required");
    server.enqueue(Fixtures.text(404, "404 page not found"));
    assertThatThrownBy(() -> q.search(SearchRequest.of("{}")))
        .isInstanceOf(UnsupportedException.class);
  }

  @Test
  void searchTracesBuildsTraceQL() {
    server.enqueue(Fixtures.replay("searchTraces.mapped-traceql"));
    List<TraceSummary> got =
        q.searchTraces(
            TraceSearchQuery.builder()
                .service("svc")
                .operation("GET /orders")
                .tag("grow.run", "1")
                .tag("weird key", "x")
                .minDurationMicros(400_000L)
                .maxDurationMicros(500_000L)
                .start(Instant.ofEpochSecond(10))
                .end(Instant.ofEpochSecond(20))
                .limit(5)
                .build());
    assertThat(lastUri())
        .isEqualTo(
            "/api/search?end=20&limit=5&q={ resource.service.name = \"svc\" && name = \"GET /orders\""
                + " && .grow.run = \"1\" && .\"weird key\" = \"x\" && trace:duration >= 400000us"
                + " && trace:duration <= 500000us }&start=10");
    assertThat(got).hasSize(1);
    TraceSummary s = got.get(0);
    assertThat(s.traceId()).isEqualTo(ID);
    assertThat(s.spanCount()).isEqualTo(4);
    assertThat(s.rootName()).contains("GET /orders");
    assertThat(s.durationMicros()).isEqualTo(450_000);
    server.enqueue(200, "{\"traces\":[]}");
    assertThat(q.searchTraces(TraceSearchQuery.builder().build())).isEmpty();
    assertThat(lastUri()).isEqualTo("/api/search?limit=20&q={}");
    assertThatThrownBy(
            () -> q.searchTraces(TraceSearchQuery.builder().minDurationMicros(-1L).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> q.searchTraces(TraceSearchQuery.builder().maxDurationMicros(-1L).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void searchTags() {
    server.enqueue(Fixtures.replay("searchTags.v2-all-scopes"));
    Map<String, List<String>> all = q.searchTags(SearchTagsOptions.all());
    assertThat(all).containsKeys("span", "resource", "intrinsic", "event", "link");
    assertThat(all.get("resource")).contains("service.name");
    assertThat(lastUri()).isEqualTo("/api/v2/search/tags");
    server.enqueue(
        200,
        "{\"scopes\":[{\"name\":\"span\",\"tags\":[\"a\"]},{\"tags\":[\"x\"]},{\"name\":\"e\"}]}");
    Map<String, List<String>> span =
        q.searchTags(
            SearchTagsOptions.builder()
                .scope(TagScope.SPAN)
                .query(TraceQL.where("resource.service.name", "=", "api"))
                .start(Instant.ofEpochSecond(1))
                .end(Instant.ofEpochSecond(2))
                .build());
    assertThat(span).containsOnly(Map.entry("span", List.of("a")), Map.entry("e", List.of()));
    assertThat(lastUri())
        .isEqualTo(
            "/api/v2/search/tags?end=2&q={ resource.service.name = \"api\" }&scope=span&start=1");
    assertThat(SearchTagsOptions.scope(TagScope.INSTRUMENTATION).scope())
        .contains(TagScope.INSTRUMENTATION);
    assertThat(TagScope.INTRINSIC.wireValue()).isEqualTo("intrinsic");
    assertThatThrownBy(() -> q.searchTags(SearchTagsOptions.builder().query("{ broken").build()))
        .isInstanceOf(ValidationException.class);
    assertThat(SearchTagsOptions.builder().query((TraceQL) null).build().query()).isEmpty();
    server.enqueue(Fixtures.replay("searchTags.v2-scope-invalid-400"));
    assertThatThrownBy(() -> q.searchTags(SearchTagsOptions.scope(TagScope.LINK)))
        .isInstanceOf(ValidationException.class);
    server.enqueue(Fixtures.replay("searchTags.mt-no-tenant-401"));
    assertThatThrownBy(() -> q.searchTags(SearchTagsOptions.all()))
        .isInstanceOf(AuthenticationException.class);
  }

  @Test
  void searchTagValues() {
    server.enqueue(Fixtures.replay("searchTagValues.v2-quoted-tag-path"));
    List<TagValue> v =
        q.searchTagValues(
            "span.grow weird.key",
            SearchTagValuesOptions.builder()
                .query("{ resource.service.name = \"api\" }")
                .start(Instant.ofEpochSecond(1))
                .end(Instant.ofEpochSecond(2))
                .build());
    assertThat(v).containsExactly(new TagValue("string", "x y"));
    assertThat(rawLastUri()).startsWith("/api/v2/search/tag/span.%22grow%20weird.key%22/values?");
    server.enqueue(Fixtures.replay("searchTagValues.v2-int-typed"));
    assertThat(q.searchTagValues("span.http.response.status_code"))
        .containsExactly(new TagValue("int", "200"), new TagValue("int", "503"));
    server.enqueue(Fixtures.replay("searchTagValues.v1-service-name"));
    assertThat(q.searchTagValues("resource.service.name"))
        .first()
        .isEqualTo(new TagValue("string", "grow-sdk-seed-api"));
    server.enqueue(200, "{\"tagValues\":[{}]}");
    assertThat(q.searchTagValues("status", SearchTagValuesOptions.query("{}")))
        .containsExactly(new TagValue("string", ""));
    assertThat(SearchTagValuesOptions.builder().query((TraceQL) null).build().query()).isEmpty();
    assertThatThrownBy(() -> q.searchTagValues("service.name"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.searchTagValues("name", SearchTagValuesOptions.query("bad")))
        .isInstanceOf(ValidationException.class);
    server.enqueue(Fixtures.replay("searchTagValues.v2-invalid-tag-400"));
    assertThatThrownBy(() -> q.searchTagValues("span.x")).isInstanceOf(ValidationException.class);
  }

  @Test
  void listServicesAndOperations() throws Exception {
    server.enqueue(200, "{\"tagValues\":[{\"value\":\"b\"},{\"value\":\"a\"},{\"value\":\"b\"}]}");
    assertThat(q.listServices()).containsExactly("a", "b");
    assertThat(rawLastUri()).isEqualTo("/api/v2/search/tag/resource.service.name/values");
    server.enqueue(Fixtures.replay("searchTagValues.v2-name-filtered"));
    assertThat(q.listOperations("api"))
        .containsExactly("GET /health", "GET /orders", "load order", "render");
    assertThat(lastUri())
        .isEqualTo("/api/v2/search/tag/name/values?q={ resource.service.name = \"api\" }");
    server.enqueue(200, "{\"tagValues\":[]}");
    assertThat(q.listServicesAsync().get()).isEmpty();
    assertThatThrownBy(() -> q.listOperations(" ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.listOperations(null)).isInstanceOf(ValidationException.class);
  }

  @Test
  void traceQLMetrics() {
    server.enqueue(Fixtures.replay("traceQLMetrics.count-over-time"));
    MetricsResult r =
        q.traceQLMetrics(
            "{ resource.service.name = \"api\" } | count_over_time()",
            MetricsRange.builder()
                .start(Instant.ofEpochSecond(1))
                .end(Instant.ofEpochSecond(2))
                .step(Duration.ofSeconds(5))
                .exemplars(5)
                .build());
    assertThat(lastUri())
        .isEqualTo(
            "/api/metrics/query_range?end=2&exemplars=5"
                + "&q={ resource.service.name = \"api\" } | count_over_time()&start=1&step=5s");
    MetricSeries s = r.series().get(0);
    assertThat(s.labels()).containsEntry("__name__", "count_over_time");
    assertThat(s.samples().get(0).value()).isZero();
    assertThat(s.samples().get(s.samples().size() - 1).value()).isEqualTo(4.0);
    assertThat(s.samples().get(0).timestampMs()).isEqualTo(1795034945000L);
    assertThat(s.exemplars().get(0).labels()).containsKey("trace:id");
    assertThat(s.exemplars().get(0).timestampMs()).isEqualTo(1795035005729L);
    assertThat(s.exemplars().get(0).value()).isEqualTo(4.0);
    assertThat(r.step()).contains(Duration.ofSeconds(5));
    server.enqueue(Fixtures.replay("traceQLMetrics.quantile-exemplars"));
    r = q.traceQLMetrics(TraceQL.span(), MetricsRange.since(Duration.ofMinutes(15)));
    assertThat(lastUri()).isEqualTo("/api/metrics/query_range?q={}&since=900s");
    assertThat(r.series().get(0).labels()).containsEntry("p", 0.9);
    server.enqueue(200, "{}");
    r =
        q.traceQLMetrics(
            "{} | rate()", MetricsRange.builder().step(Duration.ofMillis(1500)).build());
    assertThat(r.series()).isEmpty();
    assertThat(r.step()).isEmpty();
    assertThat(lastUri()).endsWith("step=1500ms");
  }

  @Test
  void traceQLMetricsErrors() {
    MetricsRange none = MetricsRange.builder().build();
    assertThatThrownBy(() -> q.traceQLMetrics(" ", none)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.traceQLMetrics((String) null, none))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.traceQLMetrics("{}", MetricsRange.since(Duration.ZERO)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                q.traceQLMetrics("{}", MetricsRange.builder().step(Duration.ofSeconds(-1)).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.traceQLMetrics("{}", MetricsRange.builder().exemplars(0).build()))
        .isInstanceOf(ValidationException.class);
    server.enqueue(Fixtures.replay("traceQLMetrics.no-pipeline-400"));
    assertThatThrownBy(() -> q.traceQLMetrics("{}", MetricsRange.since(Duration.ofMinutes(1))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("not a metrics query");
  }

  @Test
  void goDurations() {
    assertThat(TempoQueryClient.goDuration(Duration.ofSeconds(30), "x")).isEqualTo("30s");
    assertThat(TempoQueryClient.goDuration(Duration.ofMillis(250), "x")).isEqualTo("250ms");
    assertThat(TempoQueryClient.goDuration(Duration.ofNanos(3000), "x")).isEqualTo("3us");
    assertThat(TempoQueryClient.goDuration(Duration.ofNanos(3), "x")).isEqualTo("3ns");
  }

  @Test
  void probes() {
    server.enqueue(Fixtures.replay("echo.ok"));
    assertThat(q.echo()).isEqualTo("echo");
    server.enqueue(Fixtures.replay("ready.ok"));
    assertThat(q.ready()).isTrue();
    server.enqueue(Fixtures.text(503, "Ingester not ready: waiting for 15s after being ready"));
    assertThat(q.ready()).isFalse();
    server.enqueue(Fixtures.text(200, "starting"));
    assertThat(q.ready()).isFalse();
    server.enqueue(Fixtures.replay("buildInfo.ok"));
    BuildInfo info = q.buildInfo();
    assertThat(info.version()).isEqualTo("3.1.1");
    assertThat(info.goVersion()).isEqualTo("go1.27.3");
    assertThat(info.buildUser()).isEmpty();
    server.enqueue(200, "{\"version\":1}");
    assertThat(q.buildInfo().version()).isEmpty();
    server.enqueue(Fixtures.replay("buildInfo.wrong-path-404"));
    assertThatThrownBy(() -> q.buildInfo()).isInstanceOf(UnsupportedException.class);
  }

  @Test
  void readyThrowsOnNetworkErrors() {
    int port;
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      port = s.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    try (TempoQueryClient owned =
        TempoQueryClient.create(
            TempoConfig.builder()
                .url("http://127.0.0.1:" + port)
                .retry(RetryPolicy.builder().maxRetries(0).build())
                .build())) {
      assertThatThrownBy(owned::ready).isInstanceOf(UnavailableException.class);
      assertThat(owned.toString()).contains("127.0.0.1");
    }
  }

  @Test
  void dependenciesAreUnsupported() {
    assertThatThrownBy(() -> q.getDependencies(null, null))
        .isInstanceOf(UnsupportedException.class);
    assertThatThrownBy(() -> q.getDependenciesAsync(null, null).get())
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(UnsupportedException.class);
  }

  @Test
  void ownershipAndClose() {
    TempoQueryClient borrowed = new TempoQueryClient(client);
    borrowed.close();
    assertThat(client.isClosed()).isFalse();
    assertThat(borrowed.client()).isSameAs(client);
    TempoQueryClient owned =
        TempoQueryClient.create(TempoConfig.builder().url(server.url()).build());
    owned.close();
    owned.close();
    assertThat(owned.client().isClosed()).isTrue();
    assertThatThrownBy(owned::echo).isInstanceOf(CancelledException.class);
  }

  @Test
  void fromEnvUsesTheProcessEnvironment() {
    try (TempoQueryClient env = TempoQueryClient.fromEnv()) {
      assertThat(env.client().config().url()).isNotBlank();
    }
  }

  @Test
  void cancellingADerivedFutureCancelsTheExchange() throws Exception {
    server.enqueue(
        ex -> {
          Thread.sleep(2000);
          MockServer.send(ex, 200, "{}");
        });
    CompletableFuture<BuildInfo> f = q.buildInfoAsync();
    f.cancel(true);
    assertThat(f).isCancelled();
    CompletableFuture<String> source = new CompletableFuture<>();
    CompletableFuture<String> derived =
        TempoQueryClient.link(source, source.thenApply(String::trim));
    derived.cancel(true);
    assertThat(source).isCancelled();
  }

  @Test
  void errorMapping() {
    NotFoundException nf = new NotFoundException("x: not found (HTTP 404)");
    assertThat(TempoQueryClient.mapTempoError(nf, "op", false)).isSameAs(nf);
    assertThat(TempoQueryClient.mapTempoError(nf, "op", true))
        .isInstanceOf(UnsupportedException.class)
        .hasMessage("op: endpoint not available on this Tempo (HTTP 404)");
    AuthenticationException other = new AuthenticationException("op: unauthorized (HTTP 401)");
    assertThat(TempoQueryClient.mapTempoError(other, "op", false)).isSameAs(other);
    ValidationException noMessage = new ValidationException(null);
    assertThat(TempoQueryClient.mapTempoError(noMessage, "op", true)).isSameAs(noMessage);
    assertThat(Optional.of(q).map(TempoQueryClient::toString).orElseThrow()).contains("tenant-a");
  }
}
