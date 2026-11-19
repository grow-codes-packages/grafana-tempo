/**
 * Grow Codes SDK for Grafana Tempo.
 *
 * <ul>
 *   <li>{@link codes.grow.packages.tempo.TempoClient}: health, ping, server version and the query
 *       API ({@link codes.grow.packages.tempo.TempoQueryClient}: traces by id, TraceQL search,
 *       tags, tag values, TraceQL metrics).
 *   <li>{@link codes.grow.packages.tempo.TraceQL}: TraceQL builder with escaping.
 *   <li>{@link codes.grow.packages.tempo.TempoTracing}: OpenTelemetry setup with the Tempo OTLP
 *       exporter preset and tenant header.
 * </ul>
 *
 * <p>Shared building blocks live in {@code core} (errors, transport, redaction); the tracing
 * helpers and adapters in {@code tracing}; in-memory fakes and the conformance runner in {@code
 * testing}.
 */
package codes.grow.packages.tempo;
