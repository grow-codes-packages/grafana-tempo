package codes.grow.packages.tempo;

import java.util.List;

/**
 * Spans of one trace that matched a search.
 *
 * @param spans returned spans (at most {@code spss})
 * @param matched number of matching spans (may exceed {@code spans.size()})
 */
public record SpanSet(List<SearchSpan> spans, int matched) {
  /**
   * Copies the list.
   *
   * @param spans spans
   * @param matched matched count
   */
  public SpanSet {
    spans = spans == null ? List.of() : List.copyOf(spans);
  }
}
