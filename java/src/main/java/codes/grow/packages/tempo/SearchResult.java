package codes.grow.packages.tempo;

import java.util.List;
import java.util.Map;

/**
 * Result of {@link TempoQueryClient#search(SearchRequest)}.
 *
 * @param traces matching traces
 * @param metrics query statistics ({@code inspectedBytes}, {@code completedJobs}, {@code
 *     totalJobs}, …)
 */
public record SearchResult(List<SearchTrace> traces, Map<String, Long> metrics) {
  /**
   * Copies the collections.
   *
   * @param traces traces
   * @param metrics metrics
   */
  public SearchResult {
    traces = traces == null ? List.of() : List.copyOf(traces);
    metrics = metrics == null ? Map.of() : java.util.Collections.unmodifiableMap(metrics);
  }
}
