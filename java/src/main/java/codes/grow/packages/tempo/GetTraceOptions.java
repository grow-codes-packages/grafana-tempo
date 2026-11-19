package codes.grow.packages.tempo;

import java.time.Instant;
import java.util.Optional;

/**
 * Options of {@link TempoQueryClient#getTrace(String, GetTraceOptions)}: a time window that narrows
 * the backend blocks Tempo searches (sent as epoch seconds). Recent data is returned even outside
 * the window.
 */
public final class GetTraceOptions {
  private static final GetTraceOptions NONE = new GetTraceOptions(null, null);

  private final Instant start;
  private final Instant end;

  private GetTraceOptions(Instant start, Instant end) {
    this.start = start;
    this.end = end;
  }

  /**
   * No window.
   *
   * @return options without start and end
   */
  public static GetTraceOptions none() {
    return NONE;
  }

  /**
   * A window.
   *
   * @param start window start or {@code null}
   * @param end window end or {@code null}
   * @return options
   */
  public static GetTraceOptions between(Instant start, Instant end) {
    return new GetTraceOptions(start, end);
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
}
