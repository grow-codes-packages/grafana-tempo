package codes.grow.packages.tempo;

import java.util.Map;

/**
 * An exemplar span behind a metrics series (labels include {@code trace:id}).
 *
 * @param labels exemplar labels
 * @param value exemplar value
 * @param timestampMs time in milliseconds since the Unix epoch
 */
public record MetricExemplar(Map<String, Object> labels, double value, long timestampMs) {
  /**
   * Copies the labels.
   *
   * @param labels labels
   * @param value value
   * @param timestampMs time
   */
  public MetricExemplar {
    labels = labels == null ? Map.of() : java.util.Collections.unmodifiableMap(labels);
  }
}
