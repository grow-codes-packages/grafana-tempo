package codes.grow.packages.tempo;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Result of {@link TempoQueryClient#traceQLMetrics(String, MetricsRange)}.
 *
 * @param series result series
 * @param step step Tempo used, when reported
 */
public record MetricsResult(List<MetricSeries> series, Optional<Duration> step) {
  /**
   * Copies the list.
   *
   * @param series series
   * @param step step
   */
  public MetricsResult {
    series = series == null ? List.of() : List.copyOf(series);
    step = step == null ? Optional.empty() : step;
  }
}
