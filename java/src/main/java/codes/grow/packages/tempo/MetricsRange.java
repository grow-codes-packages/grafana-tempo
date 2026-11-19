package codes.grow.packages.tempo;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Window and resolution of {@link TempoQueryClient#traceQLMetrics(String, MetricsRange)}: either
 * {@code start}/{@code end} or {@code since} (a window ending now), plus an optional {@code step}
 * and exemplar count.
 */
public final class MetricsRange {
  private final Instant start;
  private final Instant end;
  private final Duration since;
  private final Duration step;
  private final Integer exemplars;

  private MetricsRange(Builder b) {
    this.start = b.start;
    this.end = b.end;
    this.since = b.since;
    this.step = b.step;
    this.exemplars = b.exemplars;
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
   * A window ending now.
   *
   * @param since window length, e.g. 15 minutes
   * @return range
   */
  public static MetricsRange since(Duration since) {
    return builder().since(since).build();
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
   * Relative window ending now.
   *
   * @return since, if set
   */
  public Optional<Duration> since() {
    return Optional.ofNullable(since);
  }

  /**
   * Step between samples (Tempo picks one when unset).
   *
   * @return step, if set
   */
  public Optional<Duration> step() {
    return Optional.ofNullable(step);
  }

  /**
   * Maximum number of exemplars.
   *
   * @return exemplars, if set
   */
  public OptionalInt exemplars() {
    return exemplars == null ? OptionalInt.empty() : OptionalInt.of(exemplars);
  }

  /** Builder of {@link MetricsRange}. */
  public static final class Builder {
    private Instant start;
    private Instant end;
    private Duration since;
    private Duration step;
    private Integer exemplars;

    private Builder() {}

    /**
     * Sets the window start (epoch seconds).
     *
     * @param start start or {@code null}
     * @return this builder
     */
    public Builder start(Instant start) {
      this.start = start;
      return this;
    }

    /**
     * Sets the window end (epoch seconds).
     *
     * @param end end or {@code null}
     * @return this builder
     */
    public Builder end(Instant end) {
      this.end = end;
      return this;
    }

    /**
     * Sets a relative window ending now.
     *
     * @param since positive duration or {@code null}
     * @return this builder
     */
    public Builder since(Duration since) {
      this.since = since;
      return this;
    }

    /**
     * Sets the step between samples.
     *
     * @param step positive duration or {@code null}
     * @return this builder
     */
    public Builder step(Duration step) {
      this.step = step;
      return this;
    }

    /**
     * Sets the maximum number of exemplars.
     *
     * @param exemplars positive number
     * @return this builder
     */
    public Builder exemplars(int exemplars) {
      this.exemplars = exemplars;
      return this;
    }

    /**
     * Builds the range.
     *
     * @return range
     */
    public MetricsRange build() {
      return new MetricsRange(this);
    }
  }
}
