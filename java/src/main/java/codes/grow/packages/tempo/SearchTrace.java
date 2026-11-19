package codes.grow.packages.tempo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One trace found by {@link TempoQueryClient#search(SearchRequest)}.
 *
 * @param traceId 32 lower-case hex characters (Tempo trims leading zeros; the SDK pads them back)
 * @param rootServiceName service of the root span, if known
 * @param rootTraceName name of the root span, if known
 * @param startMicros trace start in microseconds since the Unix epoch
 * @param durationMicros trace duration (Tempo reports whole milliseconds)
 * @param spanSets matching spans
 * @param serviceStats spans and errors per service
 */
public record SearchTrace(
    String traceId,
    Optional<String> rootServiceName,
    Optional<String> rootTraceName,
    long startMicros,
    long durationMicros,
    List<SpanSet> spanSets,
    Map<String, ServiceStats> serviceStats) {
  /**
   * Copies the collections.
   *
   * @param traceId trace id
   * @param rootServiceName root service
   * @param rootTraceName root name
   * @param startMicros start
   * @param durationMicros duration
   * @param spanSets span sets
   * @param serviceStats service stats
   */
  public SearchTrace {
    rootServiceName = rootServiceName == null ? Optional.empty() : rootServiceName;
    rootTraceName = rootTraceName == null ? Optional.empty() : rootTraceName;
    spanSets = spanSets == null ? List.of() : List.copyOf(spanSets);
    serviceStats =
        serviceStats == null ? Map.of() : java.util.Collections.unmodifiableMap(serviceStats);
  }

  /**
   * Total spans of the trace (sum of {@code serviceStats} span counts).
   *
   * @return span count
   */
  public int spanCount() {
    int n = 0;
    for (ServiceStats s : serviceStats.values()) {
      n += s.spanCount();
    }
    return n;
  }
}
