package codes.grow.packages.tempo;

import java.time.Instant;
import java.util.Optional;

/** Options of {@link TempoQueryClient#searchTagValues(String, SearchTagValuesOptions)}. */
public final class SearchTagValuesOptions {
  private static final SearchTagValuesOptions NONE = builder().build();

  private final String query;
  private final Instant start;
  private final Instant end;

  private SearchTagValuesOptions(Builder b) {
    this.query = b.query;
    this.start = b.start;
    this.end = b.end;
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
   * Options without filters.
   *
   * @return options
   */
  public static SearchTagValuesOptions none() {
    return NONE;
  }

  /**
   * Options with a TraceQL filter.
   *
   * @param query query text
   * @return options
   */
  public static SearchTagValuesOptions query(String query) {
    return builder().query(query).build();
  }

  /**
   * TraceQL filter: only values on matching spans.
   *
   * @return query, if set
   */
  public Optional<String> query() {
    return Optional.ofNullable(query);
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

  /** Builder of {@link SearchTagValuesOptions}. */
  public static final class Builder {
    private String query;
    private Instant start;
    private Instant end;

    private Builder() {}

    /**
     * Sets the TraceQL filter.
     *
     * @param query query text or {@code null}
     * @return this builder
     */
    public Builder query(String query) {
      this.query = query;
      return this;
    }

    /**
     * Sets the TraceQL filter from a builder.
     *
     * @param query query builder
     * @return this builder
     */
    public Builder query(TraceQL query) {
      this.query = query == null ? null : query.toString();
      return this;
    }

    /**
     * Sets the window start.
     *
     * @param start start or {@code null}
     * @return this builder
     */
    public Builder start(Instant start) {
      this.start = start;
      return this;
    }

    /**
     * Sets the window end.
     *
     * @param end end or {@code null}
     * @return this builder
     */
    public Builder end(Instant end) {
      this.end = end;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return options
     */
    public SearchTagValuesOptions build() {
      return new SearchTagValuesOptions(this);
    }
  }
}
