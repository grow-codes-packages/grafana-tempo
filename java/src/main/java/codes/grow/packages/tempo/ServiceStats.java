package codes.grow.packages.tempo;

/**
 * Spans and errors of one service in a search hit.
 *
 * @param spanCount spans of the service in the trace
 * @param errorCount spans with error status
 */
public record ServiceStats(int spanCount, int errorCount) {}
