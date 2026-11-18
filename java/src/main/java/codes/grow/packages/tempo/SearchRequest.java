package codes.grow.packages.tempo;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Request of {@link TempoQueryClient#search(SearchRequest)}: a TraceQL query plus window and
 * limits.
 *
 * <pre>{@code
 * SearchRequest req = SearchRequest.builder()
 *     .traceql(TraceQL.span(Map.of("resource.service.name", "api")))
 *     .limit(5)
 *     .spss(10)
 *     .build();
 * }</pre>
 */
public final class SearchRequest {
  private final String traceql;
  private final Instant start;
  private final Instant end;
  private final Integer limit;
  private final Integer spss;

  private SearchRequest(Builder b) {
    this.traceql = b.traceql;
    this.start = b.start;
    this.end = b.end;
    this.limit = b.limit;
    this.spss = b.spss;
  }

  /**
   * Starts a builder.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Request for one TraceQL query with Tempo's defaults.
   *
   * @param traceql TraceQL text
   * @return request
   */
  public static SearchRequest of(String traceql) {
    return builder().traceql(traceql).build();
  }

  /**
   * TraceQL query ({@code {}} when unset: every trace).
   *
   * @return query text
   */
  public String traceql() {
    return traceql == null || traceql.isBlank() ? "{}" : traceql.trim();
  }

  /**
   * Window start.
   *
   * @return start, if set
   */
  public Optional<Instant> start() {
    return Optional.ofNullable(start);
  }

  /**
   * Window end.
   *
   * @return end, if set
   */
  public Optional<Instant> end() {
    return Optional.ofNullable(end);
  }

  /**
   * Maximum number of traces (Tempo default 20).
   *
   * @return limit, if set
   */
  public OptionalInt limit() {
    return limit == null ? OptionalInt.empty() : OptionalInt.of(limit);
  }

  /**
   * Maximum spans per spanset (Tempo default 3).
   *
   * @return spss, if set
   */
  public OptionalInt spss() {
    return spss == null ? OptionalInt.empty() : OptionalInt.of(spss);
  }

  /** Builder of {@link SearchRequest}. */
  public static final class Builder {
    private String traceql;
    private Instant start;
    private Instant end;
    private Integer limit;
    private Integer spss;

    private Builder() {}

    /**
     * Sets the TraceQL query text.
     *
     * @param traceql query
     * @return this builder
     */
    public Builder traceql(String traceql) {
      this.traceql = traceql;
      return this;
    }

    /**
     * Sets the TraceQL query from a builder.
     *
     * @param traceql query builder
     * @return this builder
     */
    public Builder traceql(TraceQL traceql) {
      this.traceql = traceql == null ? null : traceql.toString();
      return this;
    }

    /**
     * Sets the window start (sent as epoch seconds, rounded down).
     *
     * @param start start or {@code null}
     * @return this builder
     */
    public Builder start(Instant start) {
      this.start = start;
      return this;
    }

    /**
     * Sets the window end (sent as epoch seconds, rounded up).
     *
     * @param end end or {@code null}
     * @return this builder
     */
    public Builder end(Instant end) {
      this.end = end;
      return this;
    }

    /**
     * Sets the maximum number of traces.
     *
     * @param limit positive number
     * @return this builder
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Sets the maximum spans returned per spanset ({@code matched} still counts all).
     *
     * @param spss positive number
     * @return this builder
     */
    public Builder spss(int spss) {
      this.spss = spss;
      return this;
    }

    /**
     * Builds the request.
     *
     * @return request
     */
    public SearchRequest build() {
      return new SearchRequest(this);
    }
  }
}
