package codes.grow.packages.tempo;

/** Attribute scope accepted by {@link TempoQueryClient#searchTags(SearchTagsOptions)}. */
public enum TagScope {
  /** Resource attributes ({@code resource}). */
  RESOURCE,
  /** Span attributes ({@code span}). */
  SPAN,
  /** Intrinsics such as {@code name}, {@code status}, {@code duration} ({@code intrinsic}). */
  INTRINSIC,
  /** Event attributes ({@code event}). */
  EVENT,
  /** Link attributes ({@code link}). */
  LINK,
  /** Instrumentation scope attributes ({@code instrumentation}). */
  INSTRUMENTATION;

  /**
   * Wire value.
   *
   * @return the lower-case scope name Tempo expects
   */
  public String wireValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
