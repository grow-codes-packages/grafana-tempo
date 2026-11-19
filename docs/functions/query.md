# Query client

`TempoQueryClient` reads traces back from Tempo's HTTP API. Get it from `client.query` (it shares
the client's transport and tenant) or build an owned one from a config. It implements the shared
`TraceQueryClient` interface (`getTrace`, `searchTraces`, `listServices`, `listOperations`,
`getDependencies`, `waitForTrace`, see [Tracing](../tracing.md)) and adds Tempo's own API.

!!! note "Visibility delay"
    Trace by id is visible right after the export. Search, tags and metrics see new spans about
    **30 seconds** after ingest (Tempo's live-store cut), so poll in tests.

## getTrace

`getTrace(traceId, {start, end})` → `Trace` or null / `None` / `Optional.empty()` / `nil`.

- `GET /api/v2/traces/{id}`; when the v2 route itself is missing (404) the SDK falls back to the v1
  route `GET /api/traces/{id}`.
- A trace that does not exist (or belongs to another tenant) is **not an error**: v2 answers
  HTTP 200 `{"trace":{}}`, v1 answers 404, both become "no trace".
- Ids may be upper case or shorter than 32 hex characters (left-padded). Ids in the answer are
  converted from base64 to lower-case hex; spans are sorted (parents first, then start time).
- `start` / `end` narrow the backend blocks that are searched; recent (live) data is returned anyway.
- An invalid id → `VALIDATION` before any request.

=== "TypeScript"

    ```ts
    const trace = await client.query.getTrace("4bf92f3577b34da6a3ce929d0e0e4736");
    if (trace) for (const span of trace.spans) console.log(span.name, span.durationMicros);
    const ranged = await client.query.getTrace(id, { start: new Date(Date.now() - 3600_000), end: new Date() });
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::{tracing::TraceQueryClient, GetTraceOptions};

    let q = client.query();
    if let Some(trace) = q.get_trace("4bf92f3577b34da6a3ce929d0e0e4736").await? {
        println!("{} spans", trace.spans.len());
    }
    let ranged = q.get_trace_with(&id, GetTraceOptions::range(start, end)).await?;
    ```

=== "Go"

    ```go
    trace, err := client.Query().GetTrace(ctx, "4bf92f3577b34da6a3ce929d0e0e4736")
    if err != nil {
        return err
    }
    if trace == nil {
        fmt.Println("no such trace")
    }
    ranged, err := client.Query().GetTraceWith(ctx, id, tempo.GetTraceOptions{Start: start, End: end})
    ```

=== "Python"

    ```python
    trace = client.query.get_trace("4bf92f3577b34da6a3ce929d0e0e4736")
    if trace is not None:
        for node in trace.tree():
            print(node.span.name, [c.span.name for c in node.children])
    ranged = client.query.get_trace(trace_id, start=start, end=end)
    ```

=== "Java"

    ```java
    Optional<Trace> trace = client.query().getTrace("4bf92f3577b34da6a3ce929d0e0e4736");
    trace.ifPresent(t -> t.spans().forEach(s -> System.out.println(s.name())));
    Optional<Trace> ranged = client.query().getTrace(id, GetTraceOptions.between(start, end));
    ```

## search

`search({traceql, start, end, limit, spss})` → `{traces, metrics}` (`GET /api/search`).

- `traceql` is a string or a [`TraceQL`](traceql.md) builder. **Without a query (or a blank one) no
  `q` is sent** and Tempo matches every trace.
- `limit` (traces, server default 20) and `spss` (spans per span set, server default 3) must be
  positive: `0` or a negative number is a `VALIDATION` error. Go uses the zero value for "unset",
  so there only negative numbers are rejected.
- Each hit has `traceId` (32 hex), `rootServiceName`, `rootTraceName`, start time, duration,
  `spanSets` (matched spans, `matched` may exceed the spans returned) and per-service stats.
  Span names in span sets are only present when the query selects them (`| select(name)`).
- Invalid TraceQL → `VALIDATION` (HTTP 400).

=== "TypeScript"

    ```ts
    const res = await client.query.search({
      traceql: TraceQL.span({ "resource.service.name": "api" }).and("status", "=", "error"),
      start: new Date(Date.now() - 3600_000),
      limit: 20,
      spss: 5,
    });
    for (const t of res.traces) console.log(t.traceId, t.rootTraceName, t.durationMicros);
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::{SearchRequest, TraceQL};

    let q = TraceQL::span([("resource.service.name", "api")]).and("status", "=", "error");
    let res = client.query().search(&SearchRequest::from_traceql(&q)?.limit(20).spss(5)).await?;
    for t in &res.traces {
        println!("{} {:?}", t.trace_id, t.root_trace_name);
    }
    ```

=== "Go"

    ```go
    traceQL, err := tempo.TraceQL.Span(map[string]any{"resource.service.name": "api"}).
        And("status", "=", "error").Build()
    if err != nil {
        return err
    }
    res, err := client.Query().Search(ctx, tempo.SearchRequest{TraceQL: traceQL, Limit: 20, SpansPerSpanSet: 5})
    for _, t := range res.Traces {
        fmt.Println(t.TraceID, t.RootTraceName, t.Duration)
    }
    ```

=== "Python"

    ```python
    query = TraceQL.span({"resource.service.name": "api"}).and_("status", "=", "error")
    res = client.query.search(query, start=datetime.now(UTC) - timedelta(hours=1), limit=20, spss=5)
    for t in res.traces:
        print(t.trace_id, t.root_trace_name, t.duration_micros)
    ```

=== "Java"

    ```java
    TraceQL query = TraceQL.span(Map.of("resource.service.name", "api")).and("status", "=", "error");
    SearchResult res = client.query().search(SearchRequest.builder().traceql(query).limit(20).spss(5).build());
    res.traces().forEach(t -> System.out.println(t.traceId() + " " + t.rootTraceName()));
    ```

## searchTraces

`searchTraces({service, operation, tags, minDuration, maxDuration, start, end, limit})` →
`TraceSummary[]` — the shared tracing search. The query becomes **one TraceQL span set**:
`service` → `resource.service.name`, `operation` → `name`, `tags` → unscoped attributes (`.key`),
min/max duration → `trace:duration >= / <=` (trace-level duration). `limit` defaults to 20.
Durations in the summaries have millisecond precision (Tempo's search API).

=== "TypeScript"

    ```ts
    const hits = await client.query.searchTraces({
      service: "api",
      operation: "GET /orders",
      tags: { "http.response.status_code": "500" },
      minDurationMicros: 200_000,
    });
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::tracing::{TraceQueryClient, TraceSearchQuery};

    let query = TraceSearchQuery {
        service: Some("api".into()),
        min_duration: Some(Duration::from_millis(200)),
        ..Default::default()
    };
    let hits = client.query().search_traces(&query).await?;
    ```

=== "Go"

    ```go
    hits, err := client.Query().SearchTraces(ctx, tempo.TraceSearchQuery{
        Service:     "api",
        Operation:   "GET /orders",
        Tags:        map[string]string{"http.response.status_code": "500"},
        MinDuration: 200 * time.Millisecond,
    })
    ```

=== "Python"

    ```python
    from grow_grafana_tempo import TraceSearchQuery

    hits = client.query.search_traces(
        TraceSearchQuery(service="api", operation="GET /orders", min_duration_micros=200_000)
    )
    ```

=== "Java"

    ```java
    List<TraceSummary> hits =
        client.query().searchTraces(
            TraceSearchQuery.builder().service("api").operation("GET /orders")
                .minDurationMicros(200_000L).build());
    ```

## searchTags and searchTagValues

`searchTags({scope, query, start, end})` → attribute names per scope
(`GET /api/v2/search/tags`), for example `{"resource": ["service.name"], "span": ["http.route"]}`.
Scopes: `resource`, `span`, `intrinsic`, `event`, `link`, `instrumentation` (none = all).

`searchTagValues(tag, {query, start, end})` → `TagValue {type, value}` list
(`GET /api/v2/search/tag/{tag}/values`). `tag` is a TraceQL field (`resource.service.name`,
`span.http.response.status_code`, an intrinsic such as `name` or `status`, or a quoted name
`span."my key"`); it is URL-encoded as one path segment. Unscoped tags (`service.name`) →
`VALIDATION`.

Tempo ignores an invalid `query` on these endpoints and returns unfiltered values, so the SDK checks
the TraceQL on the client side first (`VALIDATION`).

=== "TypeScript"

    ```ts
    const tags = await client.query.searchTags({ scope: "span" });
    const values = await client.query.searchTagValues("span.http.route", {
      query: TraceQL.span({ "resource.service.name": "api" }),
    });
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::{SearchTagValuesOptions, SearchTagsOptions, TagScope};

    let tags = client.query().search_tags(&SearchTagsOptions::scope(TagScope::Span)).await?;
    let values = client
        .query()
        .search_tag_values("span.http.route", &SearchTagValuesOptions::query(r#"{ resource.service.name = "api" }"#))
        .await?;
    ```

=== "Go"

    ```go
    tags, err := client.Query().SearchTags(ctx, tempo.SearchTagsOptions{Scope: tempo.TagScopeSpan})
    values, err := client.Query().SearchTagValues(ctx, "span.http.route",
        tempo.SearchTagValuesOptions{Query: `{ resource.service.name = "api" }`})
    ```

=== "Python"

    ```python
    tags = client.query.search_tags(scope="span")
    values = client.query.search_tag_values(
        "span.http.route", query=TraceQL.span({"resource.service.name": "api"})
    )
    ```

=== "Java"

    ```java
    Map<String, List<String>> tags = client.query().searchTags(SearchTagsOptions.scope(TagScope.SPAN));
    List<TagValue> values =
        client.query().searchTagValues(
            "span.http.route", SearchTagValuesOptions.query("{ resource.service.name = \"api\" }"));
    ```

## listServices and listOperations

`listServices()` → service names (tag values of `resource.service.name`);
`listOperations(service)` → span names of that service (tag values of `name`, filtered by
`{ resource.service.name = "<service>" }`). TypeScript and Python also accept an optional time
range.

=== "TypeScript"

    ```ts
    const services = await client.query.listServices();
    const operations = await client.query.listOperations("api");
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::tracing::TraceQueryClient;

    let services = client.query().list_services().await?;
    let operations = client.query().list_operations("api").await?;
    ```

=== "Go"

    ```go
    services, err := client.Query().ListServices(ctx)
    operations, err := client.Query().ListOperations(ctx, "api")
    ```

=== "Python"

    ```python
    services = client.query.list_services()
    operations = client.query.list_operations("api")
    ```

=== "Java"

    ```java
    List<String> services = client.query().listServices();
    List<String> operations = client.query().listOperations("api");
    ```

## traceQLMetrics

`traceQLMetrics(query, {start, end, since, step, exemplars})` → series with labels, samples and
exemplars (`GET /api/metrics/query_range`). `query` must end in a metrics function
(`| rate()`, `| count_over_time()`, `| quantile_over_time(duration, .9)`, …). `since` is a relative
range (ignored when `start` is set), `step` the sample interval; both must be positive.
`exemplars` must be positive (Go: `0` = server default). Samples whose value is 0 (omitted by
Tempo) come back as 0.

=== "TypeScript"

    ```ts
    const res = await client.query.traceQLMetrics(
      '{ resource.service.name = "api" } | rate() by (span.http.route)',
      { sinceMs: 15 * 60_000, stepMs: 60_000 },
    );
    for (const s of res.series) console.log(s.labels, s.samples.length);
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::MetricsRange;

    let res = client
        .query()
        .traceql_metrics(
            r#"{ resource.service.name = "api" } | rate()"#,
            MetricsRange::since(Duration::from_secs(900)).step(Duration::from_secs(60)),
        )
        .await?;
    ```

=== "Go"

    ```go
    res, err := client.Query().TraceQLMetrics(ctx, `{ resource.service.name = "api" } | rate()`,
        tempo.MetricsRange{Since: 15 * time.Minute, Step: time.Minute})
    ```

=== "Python"

    ```python
    res = client.query.traceql_metrics(
        '{ resource.service.name = "api" } | rate()', since_s=900, step_s=60
    )
    ```

=== "Java"

    ```java
    MetricsResult res =
        client.query().traceQLMetrics(
            "{ resource.service.name = \"api\" } | rate()",
            MetricsRange.builder().since(Duration.ofMinutes(15)).step(Duration.ofMinutes(1)).build());
    ```

## waitForTrace

`waitForTrace(traceId, {timeout, interval, minSpans})` polls `getTrace` until the trace exists with
at least `minSpans` spans (defaults: 30 s, 1 s, 1) and raises `TIMEOUT` otherwise. Meant for tests.

=== "TypeScript"

    ```ts
    const trace = await client.query.waitForTrace(traceId, { timeoutMs: 15_000, minSpans: 2 });
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::tracing::{TraceQueryClient, WaitForTraceOptions};

    let trace = client.query().wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
    ```

=== "Go"

    ```go
    trace, err := client.Query().WaitForTrace(ctx, traceID, tempo.WaitForTraceOptions{Timeout: 15 * time.Second, MinSpans: 2})
    ```

=== "Python"

    ```python
    trace = client.query.wait_for_trace(trace_id, timeout_s=15, min_spans=2)
    ```

=== "Java"

    ```java
    Trace trace =
        client.query().waitForTrace(
            traceId, new WaitForTraceOptions(Duration.ofSeconds(15), Duration.ofSeconds(1), 2));
    ```

## getDependencies

Tempo has no dependency-graph endpoint (its service-graph processor writes metrics to Prometheus
instead), so `getDependencies` always raises `UNSUPPORTED` in every language. It is covered by unit
tests only.

=== "TypeScript"

    ```ts
    await client.query.getDependencies({ start, end }).catch((e) => e.code); // "UNSUPPORTED"
    ```

=== "Rust"

    ```rust
    let err = client.query().get_dependencies(DependencyOptions::default()).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);
    ```

=== "Go"

    ```go
    _, err := client.Query().GetDependencies(ctx, tempo.DependencyOptions{})
    errors.Is(err, tempo.ErrUnsupported) // true
    ```

=== "Python"

    ```python
    with pytest.raises(UnsupportedError):
        client.query.get_dependencies()
    ```

=== "Java"

    ```java
    assertThrows(UnsupportedException.class, () -> client.query().getDependencies(start, end));
    ```
