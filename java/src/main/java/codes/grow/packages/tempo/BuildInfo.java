package codes.grow.packages.tempo;

/**
 * Build information from {@code GET /api/status/buildinfo}. Unset fields are empty strings.
 *
 * @param version Tempo version, e.g. {@code 3.1.1}
 * @param revision git revision
 * @param branch git branch
 * @param buildUser build user
 * @param buildDate build date
 * @param goVersion Go version of the binary
 */
public record BuildInfo(
    String version,
    String revision,
    String branch,
    String buildUser,
    String buildDate,
    String goVersion) {}
