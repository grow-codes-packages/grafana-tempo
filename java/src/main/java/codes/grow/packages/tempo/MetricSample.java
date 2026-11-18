package codes.grow.packages.tempo;

/**
 * One sample of a TraceQL metrics series. Tempo omits zero values; the SDK reports 0.
 *
 * @param timestampMs sample time in milliseconds since the Unix epoch
 * @param value sample value
 */
public record MetricSample(long timestampMs, double value) {}
