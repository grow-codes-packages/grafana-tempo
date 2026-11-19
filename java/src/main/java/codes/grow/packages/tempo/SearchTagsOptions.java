package codes.grow.packages.tempo;

import java.time.Instant;
import java.util.Optional;

/** Options of {@link TempoQueryClient#searchTags(SearchTagsOptions)}. */
public final class SearchTagsOptions {
  private final TagScope scope;
  private final String query;
  private final Instant start;
  private final Instant end;

  private SearchTagsOptions(Builder b) {
    this.scope = b.scope;
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
   * Options without filters (every scope that has data).
   *
   * @return options
   */
  public static SearchTagsOptions all() {
    return builder().build();
  }

  /**
   * Options for one scope.
   *
   * @param scope scope
   * @return options
   */
  public static SearchTagsOptions scope(TagScope scope) {
    return builder().scope(scope).build();
  }

  /**
   * Scope filter.
   *
   * @return scope, if set
   */
  public Optional<TagScope> scope() {
    return Optional.ofNullable(scope);
  }

  /**
   * TraceQL filter: only tags present on matching spans.
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

  /** Builder of {@link SearchTagsOptions}. */
  public static final class Builder {
    private TagScope scope;
    private String query;
    private Instant start;
    private Instant end;

    private Builder() {}

    /**
     * Sets the scope.
     *
     * @param scope scope or {@code null} for every scope
     * @return this builder
     */
    public Builder scope(TagScope scope) {
      this.scope = scope;
      return this;
    }

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
    public SearchTagsOptions build() {
      return new SearchTagsOptions(this);
    }
  }
}
