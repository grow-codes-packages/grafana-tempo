package codes.grow.packages.tempo;

import java.util.Map;
import java.util.Optional;

/**
 * One span of a search spanset. {@code name} is present only when the query selects it ({@code |
 * select(name)}); attributes always hold {@code service.name} plus the selected attributes.
 *
 * @param spanId 16 lower-case hex characters
 * @param name span name, when selected
 * @param startMicros start time in microseconds since the Unix epoch
 * @param durationMicros duration in microseconds
 * @param attributes attribute values
 */
public record SearchSpan(
    String spanId,
    Optional<String> name,
    long startMicros,
    long durationMicros,
    Map<String, Object> attributes) {
  /**
   * Copies the attributes.
   *
   * @param spanId span id
   * @param name name
   * @param startMicros start
   * @param durationMicros duration
   * @param attributes attributes
   */
  public SearchSpan {
    name = name == null ? Optional.empty() : name;
    attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(attributes);
  }
}
