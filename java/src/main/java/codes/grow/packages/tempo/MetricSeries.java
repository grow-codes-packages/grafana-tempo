package codes.grow.packages.tempo;

import java.util.List;
import java.util.Map;

/**
 * One series of a TraceQL metrics query.
 *
 * @param labels series labels ({@code __name__}, {@code by()} attributes, quantile {@code p})
 * @param samples samples in time order
 * @param exemplars exemplars, when requested
 */
public record MetricSeries(
    Map<String, Object> labels, List<MetricSample> samples, List<MetricExemplar> exemplars) {
  /**
   * Copies the collections.
   *
   * @param labels labels
   * @param samples samples
   * @param exemplars exemplars
   */
  public MetricSeries {
    labels = labels == null ? Map.of() : java.util.Collections.unmodifiableMap(labels);
    samples = samples == null ? List.of() : List.copyOf(samples);
    exemplars = exemplars == null ? List.of() : List.copyOf(exemplars);
  }
}
