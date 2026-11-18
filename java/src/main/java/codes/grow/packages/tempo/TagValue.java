package codes.grow.packages.tempo;

/**
 * One tag value from {@link TempoQueryClient#searchTagValues(String, SearchTagValuesOptions)}.
 * Values are always text; {@code type} tells how to read them ({@code string}, {@code int}, {@code
 * keyword}, …).
 *
 * @param type value type reported by Tempo
 * @param value value as text
 */
public record TagValue(String type, String value) {}
