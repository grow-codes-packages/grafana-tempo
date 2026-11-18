package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.AuthenticationException;
import codes.grow.packages.tempo.core.HttpTransport;
import codes.grow.packages.tempo.core.NotFoundException;
import codes.grow.packages.tempo.core.TempoException;
import codes.grow.packages.tempo.core.TransportRequest;
import codes.grow.packages.tempo.core.TransportResponse;
import codes.grow.packages.tempo.core.UnsupportedException;
import codes.grow.packages.tempo.core.ValidationException;
import codes.grow.packages.tempo.tracing.query.DependencyLink;
import codes.grow.packages.tempo.tracing.query.Span;
import codes.grow.packages.tempo.tracing.query.Trace;
import codes.grow.packages.tempo.tracing.query.TraceIds;
import codes.grow.packages.tempo.tracing.query.TraceQueryClient;
import codes.grow.packages.tempo.tracing.query.TraceSearchQuery;
import codes.grow.packages.tempo.tracing.query.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Client for Tempo's HTTP query API (port 3200). Implements the SPEC {@link TraceQueryClient}
 * ({@code getTrace}, {@code searchTraces}, {@code listServices}, {@code listOperations}, {@code
 * getDependencies}, {@code waitForTrace}) plus Tempo's own {@link #search(SearchRequest) search}
 * (TraceQL), {@link #searchTags searchTags}, {@link #searchTagValues searchTagValues}, {@link
 * #traceQLMetrics traceQLMetrics}, {@link #echo()}, {@link #ready()} and {@link #buildInfo()}.
 *
 * <p>Get one from {@link TempoClient#query()}, or build an owned one with {@link
 * #create(TempoConfig)} / {@link #fromEnv()} ({@link #close()} then closes its client). The tenant
 * ({@code X-Scope-OrgID}) comes from the config. Every method has a blocking form and an {@code
 * …Async} form returning {@link CompletableFuture}.
 *
 * <p>Tempo shows a trace by id immediately after ingest, but search, tags and metrics only after
 * about 30 seconds.
 */
public final class TempoQueryClient implements TraceQueryClient, AutoCloseable {
  private final TempoClient client;
  private final boolean owned;

  /**
   * Wraps an existing client (not closed by {@link #close()}).
   *
   * @param client the client
   */
  public TempoQueryClient(TempoClient client) {
    this(client, false);
  }

  private TempoQueryClient(TempoClient client, boolean owned) {
    this.client = client;
    this.owned = owned;
  }

  /**
   * Builds a query client that owns its {@link TempoClient}.
   *
   * @param config configuration
   * @return query client
   */
  public static TempoQueryClient create(TempoConfig config) {
    return new TempoQueryClient(TempoClient.create(config), true);
  }

  /**
   * Builds an owned query client from {@code GROW_GRAFANA_TEMPO_*} / {@code TEMPO_*} variables.
   *
   * @return query client
   */
  public static TempoQueryClient fromEnv() {
    return create(TempoConfig.fromEnv());
  }

  /**
   * The underlying client.
   *
   * @return client
   */
  public TempoClient client() {
    return client;
  }

  // ---------------------------------------------------------------- transport helpers

  private CompletableFuture<TransportResponse> get(
      TransportRequest.Builder request, String operation, boolean unsupported404) {
    CompletableFuture<TransportResponse> source =
        client.transport().sendAsync(request.operation(operation).build());
    return mapErrors(source, operation, unsupported404);
  }

  static <T> CompletableFuture<T> mapErrors(
      CompletableFuture<T> source, String operation, boolean unsupported404) {
    CompletableFuture<T> out =
        source.handle(
            (v, err) -> {
              if (err != null) {
                throw mapTempoError(
                    HttpTransport.asSdkException(err, operation), operation, unsupported404);
              }
              return v;
            });
    return link(source, out);
  }

  /** Cancelling {@code derived} cancels {@code source} (the HTTP exchange). */
  static <T> CompletableFuture<T> link(CompletableFuture<?> source, CompletableFuture<T> derived) {
    derived.whenComplete(
        (v, e) -> {
          if (derived.isCancelled()) {
            source.cancel(true);
          }
        });
    return derived;
  }

  /**
   * Tempo-specific error mapping on top of the SPEC status mapping: a 401 {@code no org id} names
   * the missing tenant header; a 404 on a feature route means the endpoint is not available ({@link
   * UnsupportedException}).
   *
   * @param e error from the transport
   * @param operation operation name
   * @param unsupported404 whether a 404 means a missing feature route
   * @return the mapped error
   */
  static TempoException mapTempoError(TempoException e, String operation, boolean unsupported404) {
    String msg = e.getMessage() == null ? "" : e.getMessage();
    if (e instanceof AuthenticationException
        && msg.toLowerCase(Locale.ROOT).contains("no org id")) {
      return new AuthenticationException(
          operation + ": tenant required (" + TempoConfig.TENANT_HEADER + ") (HTTP 401)",
          401,
          e.requestId().orElse(null),
          null,
          e);
    }
    if (unsupported404 && e instanceof NotFoundException) {
      return new UnsupportedException(
          operation + ": endpoint not available on this Tempo (HTTP 404)",
          404,
          e.requestId().orElse(null),
          null,
          e);
    }
    return e;
  }

  private static void range(
      TransportRequest.Builder b, Optional<Instant> start, Optional<Instant> end) {
    start.ifPresent(s -> b.query("start", s.getEpochSecond()));
    end.ifPresent(
        e -> b.query("end", e.getNano() > 0 ? e.getEpochSecond() + 1 : e.getEpochSecond()));
  }

  private static int positive(int value, String name) {
    if (value <= 0) {
      throw new ValidationException(name + " must be a positive integer");
    }
    return value;
  }

  /**
   * Formats a duration as a Go duration ({@code 5s}, {@code 1500ms}, {@code 250us}, {@code 7ns}).
   *
   * @param d positive duration
   * @param name parameter name for the error message
   * @return Go duration text
   * @throws ValidationException when not positive
   */
  static String goDuration(Duration d, String name) {
    if (d.isZero() || d.isNegative()) {
      throw new ValidationException(name + " must be positive");
    }
    long nanos = d.toNanos();
    if (nanos % 1_000_000_000L == 0) {
      return nanos / 1_000_000_000L + "s";
    }
    if (nanos % 1_000_000L == 0) {
      return nanos / 1_000_000L + "ms";
    }
    if (nanos % 1_000L == 0) {
      return nanos / 1_000L + "us";
    }
    return nanos + "ns";
  }

  private static <T> T await(CompletableFuture<T> f, String operation) {
    return HttpTransport.await(f, operation);
  }

  private static <T> CompletableFuture<T> failed(RuntimeException e) {
    return CompletableFuture.failedFuture(e);
  }

  private static String padHex(String id, int length) {
    String hex = id == null ? "" : id.toLowerCase(Locale.ROOT);
    return hex.length() >= length ? hex : "0".repeat(length - hex.length()) + hex;
  }

  // ---------------------------------------------------------------- getTrace

  /**
   * Fetches a trace by id: {@code GET /api/v2/traces/{id}} (falls back to v1 {@code
   * /api/traces/{id}} when the v2 route answers 404). Empty when the trace does not exist or
   * belongs to another tenant.
   *
   * @param traceId 1-32 hex characters
   * @return the trace, or empty
   * @throws ValidationException for an invalid id
   */
  @Override
  public Optional<Trace> getTrace(String traceId) {
    return getTrace(traceId, GetTraceOptions.none());
  }

  /**
   * {@link #getTrace(String)} with a time window that narrows the backend blocks searched (recent
   * data is returned anyway).
   *
   * @param traceId trace id
   * @param options window
   * @return the trace, or empty
   */
  public Optional<Trace> getTrace(String traceId, GetTraceOptions options) {
    return await(getTraceAsync(traceId, options), "getTrace");
  }

  @Override
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId) {
    return getTraceAsync(traceId, GetTraceOptions.none());
  }

  /**
   * Asynchronous {@link #getTrace(String, GetTraceOptions)}.
   *
   * @param traceId trace id
   * @param options window
   * @return future trace
   */
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId, GetTraceOptions options) {
    String id;
    try {
      id = TraceIds.normalizeTraceId(traceId);
    } catch (ValidationException e) {
      return failed(e);
    }
    String operation = "getTrace " + id;
    TransportRequest.Builder v2 = TransportRequest.get("/api/v2/traces/" + id).acceptStatus(404);
    range(v2, options.start(), options.end());
    CompletableFuture<TransportResponse> first = get(v2, operation, false);
    CompletableFuture<Optional<Trace>> out =
        first.thenCompose(
            r -> {
              if (r.status() != 404) {
                return CompletableFuture.completedFuture(
                    trace(id, body(r).path("trace").get("resourceSpans")));
              }
              TransportRequest.Builder v1 =
                  TransportRequest.get("/api/traces/" + id).acceptStatus(404);
              range(v1, options.start(), options.end());
              return get(v1, operation, false)
                  .thenApply(
                      r1 ->
                          r1.status() == 404
                              ? Optional.<Trace>empty()
                              : trace(id, body(r1).get("batches")));
            });
    return link(first, out);
  }

  private static JsonNode body(TransportResponse r) {
    return r.body().length == 0
        ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
        : r.jsonNode();
  }

  private static Optional<Trace> trace(String id, JsonNode resourceSpans) {
    List<Span> spans = OtlpJson.spansFromResourceSpans(resourceSpans);
    return spans.isEmpty() ? Optional.empty() : Optional.of(new Trace(id, spans));
  }

  // ---------------------------------------------------------------- search

  /**
   * Runs a TraceQL search: {@code GET /api/search?q=…&start&end&limit&spss}. Trace ids come back
   * padded to 32 hex characters.
   *
   * @param request query, window and limits
   * @return matching traces
   * @throws ValidationException for invalid TraceQL (HTTP 400) or a non-positive limit
   */
  public SearchResult search(SearchRequest request) {
    return await(searchAsync(request), "search");
  }

  /**
   * Asynchronous {@link #search(SearchRequest)}.
   *
   * @param request request
   * @return future result
   */
  public CompletableFuture<SearchResult> searchAsync(SearchRequest request) {
    TransportRequest.Builder b = TransportRequest.get("/api/search");
    try {
      b.query("q", request.traceql());
      range(b, request.start(), request.end());
      if (request.limit().isPresent()) {
        b.query("limit", positive(request.limit().getAsInt(), "search: limit"));
      }
      if (request.spss().isPresent()) {
        b.query("spss", positive(request.spss().getAsInt(), "search: spss"));
      }
    } catch (ValidationException e) {
      return failed(e);
    }
    CompletableFuture<TransportResponse> f = get(b, "search", true);
    return link(f, f.thenApply(r -> searchResult(body(r))));
  }

  private static SearchResult searchResult(JsonNode body) {
    List<SearchTrace> traces = new ArrayList<>();
    for (JsonNode t : body.path("traces")) {
      traces.add(searchTrace(t));
    }
    Map<String, Long> metrics = new LinkedHashMap<>();
    body.path("metrics")
        .properties()
        .forEach(e -> metrics.put(e.getKey(), OtlpJson.longValue(e.getValue())));
    return new SearchResult(traces, metrics);
  }

  private static SearchTrace searchTrace(JsonNode t) {
    List<JsonNode> sets = new ArrayList<>();
    if (t.has("spanSets")) {
      t.get("spanSets").forEach(sets::add);
    } else if (t.has("spanSet")) {
      sets.add(t.get("spanSet"));
    }
    List<SpanSet> spanSets = new ArrayList<>();
    for (JsonNode s : sets) {
      List<SearchSpan> spans = new ArrayList<>();
      for (JsonNode sp : s.path("spans")) {
        spans.add(
            new SearchSpan(
                padHex(sp.path("spanID").asText(""), 16),
                sp.has("name") ? Optional.of(sp.get("name").asText()) : Optional.empty(),
                OtlpJson.nanosToMicros(sp.get("startTimeUnixNano")),
                OtlpJson.nanosToMicros(sp.get("durationNanos")),
                OtlpJson.fromKeyValues(sp.get("attributes"))));
      }
      spanSets.add(new SpanSet(spans, s.path("matched").asInt(0)));
    }
    Map<String, ServiceStats> stats = new LinkedHashMap<>();
    t.path("serviceStats")
        .properties()
        .forEach(
            e ->
                stats.put(
                    e.getKey(),
                    new ServiceStats(
                        e.getValue().path("spanCount").asInt(0),
                        e.getValue().path("errorCount").asInt(0))));
    return new SearchTrace(
        padHex(t.path("traceID").asText(""), 32),
        t.has("rootServiceName")
            ? Optional.of(t.get("rootServiceName").asText())
            : Optional.empty(),
        t.has("rootTraceName") ? Optional.of(t.get("rootTraceName").asText()) : Optional.empty(),
        OtlpJson.nanosToMicros(t.get("startTimeUnixNano")),
        OtlpJson.longValue(t.get("durationMs")) * 1000,
        spanSets,
        stats);
  }

  /**
   * SPEC search: builds one TraceQL spanset ({@code resource.service.name}, {@code name}, unscoped
   * {@code .tag} filters, {@code trace:duration} bounds in µs) and maps hits to {@link
   * TraceSummary} (duration has millisecond precision).
   *
   * @param query filter (limit default 20)
   * @return summaries
   */
  @Override
  public List<TraceSummary> searchTraces(TraceSearchQuery query) {
    return await(searchTracesAsync(query), "searchTraces");
  }

  @Override
  public CompletableFuture<List<TraceSummary>> searchTracesAsync(TraceSearchQuery query) {
    SearchRequest.Builder req = SearchRequest.builder();
    try {
      req.traceql(searchTracesTraceQL(query)).limit(query.limit());
    } catch (ValidationException e) {
      return failed(e);
    }
    query.start().ifPresent(req::start);
    query.end().ifPresent(req::end);
    CompletableFuture<SearchResult> f = searchAsync(req.build());
    return link(
        f,
        f.thenApply(
            r -> {
              List<TraceSummary> out = new ArrayList<>();
              for (SearchTrace t : r.traces()) {
                out.add(
                    new TraceSummary(
                        t.traceId(),
                        t.rootServiceName(),
                        t.rootTraceName(),
                        t.startMicros(),
                        t.durationMicros(),
                        t.spanCount()));
              }
              return List.copyOf(out);
            }));
  }

  /**
   * The TraceQL query {@link #searchTraces} sends for a {@link TraceSearchQuery}.
   *
   * @param query filter
   * @return TraceQL builder
   */
  public static TraceQL searchTracesTraceQL(TraceSearchQuery query) {
    TraceQL q = TraceQL.span();
    if (query.service().isPresent()) {
      q = q.and("resource.service.name", "=", query.service().get());
    }
    if (query.operation().isPresent()) {
      q = q.and("name", "=", query.operation().get());
    }
    for (Map.Entry<String, String> e : query.tags().entrySet()) {
      q = q.and("." + e.getKey(), "=", e.getValue());
    }
    if (query.minDurationMicros().isPresent()) {
      q = q.and("trace:duration", ">=", micros(query.minDurationMicros().getAsLong(), "min"));
    }
    if (query.maxDurationMicros().isPresent()) {
      q = q.and("trace:duration", "<=", micros(query.maxDurationMicros().getAsLong(), "max"));
    }
    return q;
  }

  private static String micros(long value, String which) {
    if (value < 0) {
      throw new ValidationException(
          "searchTraces: " + which + "DurationMicros must be a non-negative number");
    }
    return value + "us";
  }

  // ---------------------------------------------------------------- tags

  /**
   * Tag names per scope: {@code GET /api/v2/search/tags} → {@code {resource: [...], span: [...],
   * …}}. Without a scope it returns every scope that has data.
   *
   * @param options scope, TraceQL filter and window
   * @return scope name → tag names
   */
  public Map<String, List<String>> searchTags(SearchTagsOptions options) {
    return await(searchTagsAsync(options), "searchTags");
  }

  /**
   * Asynchronous {@link #searchTags(SearchTagsOptions)}.
   *
   * @param options options
   * @return future map
   */
  public CompletableFuture<Map<String, List<String>>> searchTagsAsync(SearchTagsOptions options) {
    TransportRequest.Builder b = TransportRequest.get("/api/v2/search/tags");
    try {
      options.scope().ifPresent(s -> b.query("scope", s.wireValue()));
      if (options.query().isPresent()) {
        b.query("q", TraceQL.check(options.query().get(), "searchTags"));
      }
    } catch (ValidationException e) {
      return failed(e);
    }
    range(b, options.start(), options.end());
    CompletableFuture<TransportResponse> f = get(b, "searchTags", true);
    return link(
        f,
        f.thenApply(
            r -> {
              Map<String, List<String>> out = new LinkedHashMap<>();
              for (JsonNode scope : body(r).path("scopes")) {
                if (scope.hasNonNull("name")) {
                  List<String> tags = new ArrayList<>();
                  scope.path("tags").forEach(t -> tags.add(t.asText()));
                  out.put(scope.get("name").asText(), List.copyOf(tags));
                }
              }
              return out;
            }));
  }

  /**
   * Values of one tag: {@code GET /api/v2/search/tag/{tag}/values}. {@code tag} is a TraceQL
   * identifier ({@code resource.service.name}, {@code span.http.response.status_code}, {@code
   * name}, {@code status}, {@code span.grow weird.key}); an unscoped attribute name is a {@link
   * ValidationException}.
   *
   * @param tag TraceQL identifier
   * @param options TraceQL filter and window
   * @return values with their types
   */
  public List<TagValue> searchTagValues(String tag, SearchTagValuesOptions options) {
    return await(searchTagValuesAsync(tag, options), "searchTagValues");
  }

  /**
   * {@link #searchTagValues(String, SearchTagValuesOptions)} without filters.
   *
   * @param tag TraceQL identifier
   * @return values
   */
  public List<TagValue> searchTagValues(String tag) {
    return searchTagValues(tag, SearchTagValuesOptions.none());
  }

  /**
   * Asynchronous {@link #searchTagValues(String, SearchTagValuesOptions)}.
   *
   * @param tag TraceQL identifier
   * @param options options
   * @return future values
   */
  public CompletableFuture<List<TagValue>> searchTagValuesAsync(
      String tag, SearchTagValuesOptions options) {
    String field;
    TransportRequest.Builder b;
    try {
      field = TraceQL.field(tag);
      b =
          TransportRequest.get(
              "/api/v2/search/tag/"
                  + URLEncoder.encode(field, StandardCharsets.UTF_8).replace("+", "%20")
                  + "/values");
      if (options.query().isPresent()) {
        b.query("q", TraceQL.check(options.query().get(), "searchTagValues"));
      }
    } catch (ValidationException e) {
      return failed(e);
    }
    range(b, options.start(), options.end());
    CompletableFuture<TransportResponse> f = get(b, "searchTagValues " + field, true);
    return link(
        f,
        f.thenApply(
            r -> {
              List<TagValue> out = new ArrayList<>();
              for (JsonNode v : body(r).path("tagValues")) {
                if (v.isTextual()) {
                  out.add(new TagValue("string", v.asText()));
                } else {
                  out.add(
                      new TagValue(v.path("type").asText("string"), v.path("value").asText("")));
                }
              }
              return List.copyOf(out);
            }));
  }

  /**
   * Service names ({@code resource.service.name} values seen recently), sorted.
   *
   * @return service names
   */
  @Override
  public List<String> listServices() {
    return await(listServicesAsync(), "listServices");
  }

  @Override
  public CompletableFuture<List<String>> listServicesAsync() {
    return sortedValues(
        searchTagValuesAsync("resource.service.name", SearchTagValuesOptions.none()));
  }

  /**
   * Span names of one service ({@code name} values filtered by the service), sorted.
   *
   * @param service service name
   * @return operation names
   * @throws ValidationException when the service is blank
   */
  @Override
  public List<String> listOperations(String service) {
    return await(listOperationsAsync(service), "listOperations");
  }

  @Override
  public CompletableFuture<List<String>> listOperationsAsync(String service) {
    if (service == null || service.isBlank()) {
      return failed(new ValidationException("listOperations: service is required"));
    }
    return sortedValues(
        searchTagValuesAsync(
            "name",
            SearchTagValuesOptions.builder()
                .query(TraceQL.span(Map.of("resource.service.name", service)))
                .build()));
  }

  private static CompletableFuture<List<String>> sortedValues(CompletableFuture<List<TagValue>> f) {
    Function<List<TagValue>, List<String>> sort =
        values -> {
          TreeSet<String> set = new TreeSet<>();
          values.forEach(v -> set.add(v.value()));
          return List.copyOf(set);
        };
    return link(f, f.thenApply(sort));
  }

  // ---------------------------------------------------------------- metrics

  /**
   * TraceQL metrics over a range: {@code GET /api/metrics/query_range} (for example {@code {
   * resource.service.name = "api" } | rate()}). Zero samples omitted by Tempo are reported as 0.
   *
   * @param query TraceQL metrics query
   * @param range window, step and exemplars
   * @return series
   * @throws ValidationException for a blank query or one without a metrics pipeline (HTTP 400)
   */
  public MetricsResult traceQLMetrics(String query, MetricsRange range) {
    return await(traceQLMetricsAsync(query, range), "traceQLMetrics");
  }

  /**
   * {@link #traceQLMetrics(String, MetricsRange)} with a builder query.
   *
   * @param query TraceQL builder (append the metrics pipeline yourself in text form otherwise)
   * @param range window
   * @return series
   */
  public MetricsResult traceQLMetrics(TraceQL query, MetricsRange range) {
    return traceQLMetrics(query.toString(), range);
  }

  /**
   * Asynchronous {@link #traceQLMetrics(String, MetricsRange)}.
   *
   * @param query query
   * @param range window
   * @return future series
   */
  public CompletableFuture<MetricsResult> traceQLMetricsAsync(String query, MetricsRange range) {
    TransportRequest.Builder b = TransportRequest.get("/api/metrics/query_range");
    try {
      String q = query == null ? "" : query.trim();
      if (q.isEmpty()) {
        throw new ValidationException("traceQLMetrics: query is required");
      }
      b.query("q", q);
      range(b, range.start(), range.end());
      if (range.since().isPresent()) {
        b.query("since", goDuration(range.since().get(), "traceQLMetrics: since"));
      }
      if (range.step().isPresent()) {
        b.query("step", goDuration(range.step().get(), "traceQLMetrics: step"));
      }
      if (range.exemplars().isPresent()) {
        b.query("exemplars", positive(range.exemplars().getAsInt(), "traceQLMetrics: exemplars"));
      }
    } catch (ValidationException e) {
      return failed(e);
    }
    CompletableFuture<TransportResponse> f = get(b, "traceQLMetrics", true);
    return link(f, f.thenApply(r -> metrics(body(r))));
  }

  private static MetricsResult metrics(JsonNode body) {
    List<MetricSeries> series = new ArrayList<>();
    for (JsonNode s : body.path("series")) {
      List<MetricSample> samples = new ArrayList<>();
      for (JsonNode p : s.path("samples")) {
        samples.add(
            new MetricSample(
                OtlpJson.longValue(p.get("timestampMs")), p.path("value").asDouble(0)));
      }
      List<MetricExemplar> exemplars = new ArrayList<>();
      for (JsonNode e : s.path("exemplars")) {
        exemplars.add(
            new MetricExemplar(
                OtlpJson.fromKeyValues(e.get("labels")),
                e.path("value").asDouble(0),
                OtlpJson.longValue(e.get("timestampMs"))));
      }
      series.add(new MetricSeries(OtlpJson.fromKeyValues(s.get("labels")), samples, exemplars));
    }
    Optional<Duration> step =
        body.hasNonNull("step")
            ? Optional.of(Duration.ofNanos(OtlpJson.longValue(body.get("step"))))
            : Optional.empty();
    return new MetricsResult(series, step);
  }

  // ---------------------------------------------------------------- probes

  /**
   * {@code GET /api/echo} → {@code "echo"} (no tenant needed).
   *
   * @return the echoed text
   */
  public String echo() {
    return await(echoAsync(), "echo");
  }

  /**
   * Asynchronous {@link #echo()}.
   *
   * @return future text
   */
  public CompletableFuture<String> echoAsync() {
    CompletableFuture<TransportResponse> f = get(TransportRequest.get("/api/echo"), "echo", false);
    return link(f, f.thenApply(r -> r.text().trim()));
  }

  /**
   * {@code GET /ready}: true when Tempo answers {@code ready}, false while it is starting (HTTP
   * 503) or reports another state. Throws on network errors (unlike {@link TempoClient#health()}).
   *
   * @return readiness
   */
  public boolean ready() {
    return await(readyAsync(), "ready");
  }

  /**
   * Asynchronous {@link #ready()}.
   *
   * @return future readiness
   */
  public CompletableFuture<Boolean> readyAsync() {
    CompletableFuture<TransportResponse> f =
        get(TransportRequest.get("/ready").acceptAnyStatus().maxRetries(0), "ready", false);
    return link(f, f.thenApply(r -> r.status() == 200 && r.text().trim().equals("ready")));
  }

  /**
   * {@code GET /api/status/buildinfo} → version, revision, branch, build user/date, Go version.
   *
   * @return build information
   * @throws UnsupportedException when the endpoint does not exist
   */
  public BuildInfo buildInfo() {
    return await(buildInfoAsync(), "buildInfo");
  }

  /**
   * Asynchronous {@link #buildInfo()}.
   *
   * @return future build information
   */
  public CompletableFuture<BuildInfo> buildInfoAsync() {
    CompletableFuture<TransportResponse> f =
        get(TransportRequest.get("/api/status/buildinfo"), "buildInfo", true);
    return link(
        f,
        f.thenApply(
            r -> {
              JsonNode d = body(r);
              return new BuildInfo(
                  text(d, "version"),
                  text(d, "revision"),
                  text(d, "branch"),
                  text(d, "buildUser"),
                  text(d, "buildDate"),
                  text(d, "goVersion"));
            }));
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v != null && v.isTextual() ? v.asText() : "";
  }

  // ---------------------------------------------------------------- unsupported + close

  /**
   * Always {@link UnsupportedException}: Tempo has no dependency-graph API (service graphs need the
   * metrics-generator plus a Prometheus backend).
   *
   * @param start ignored
   * @param end ignored
   * @return never
   * @throws UnsupportedException always
   */
  @Override
  public List<DependencyLink> getDependencies(Instant start, Instant end) {
    throw unsupportedDependencies();
  }

  @Override
  public CompletableFuture<List<DependencyLink>> getDependenciesAsync(Instant start, Instant end) {
    return failed(unsupportedDependencies());
  }

  private static UnsupportedException unsupportedDependencies() {
    return new UnsupportedException("getDependencies: Tempo has no dependency graph API");
  }

  /** Closes the underlying client when this query client owns it. Idempotent. */
  @Override
  public void close() {
    if (owned) {
      client.close();
    }
  }

  @Override
  public String toString() {
    return "TempoQueryClient{" + client.config() + "}";
  }
}
