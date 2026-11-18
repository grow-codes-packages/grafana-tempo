package codes.grow.packages.tempo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.tempo.core.AuthenticationException;
import codes.grow.packages.tempo.core.CancelledException;
import codes.grow.packages.tempo.core.ConfigException;
import codes.grow.packages.tempo.core.EnvReader;
import codes.grow.packages.tempo.core.HealthStatus;
import codes.grow.packages.tempo.core.Hooks;
import codes.grow.packages.tempo.core.Logger;
import codes.grow.packages.tempo.core.MockServer;
import codes.grow.packages.tempo.core.RetryPolicy;
import codes.grow.packages.tempo.core.Secret;
import codes.grow.packages.tempo.core.TlsOptions;
import codes.grow.packages.tempo.core.UnavailableException;
import codes.grow.packages.tempo.core.UnsupportedException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TempoClientTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private TempoClient client() {
    return TempoClient.builder()
        .url(server.url())
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
        .build();
  }

  private static int freePort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  @Test
  void healthVariants() throws Exception {
    server
        .enqueue(Fixtures.replay("ready.ok"))
        .enqueue(Fixtures.text(503, "Ingester not ready: waiting for 15s after being ready\n"))
        .enqueue(Fixtures.text(200, "starting"));
    try (TempoClient c = client()) {
      HealthStatus ok = c.health();
      assertThat(ok.healthy()).isTrue();
      assertThat(ok.status()).isEqualTo("ok");
      assertThat(ok.details()).containsEntry("ready", "ready");
      HealthStatus starting = c.health();
      assertThat(starting.healthy()).isFalse();
      assertThat(starting.status()).isEqualTo("unhealthy");
      assertThat(starting.details())
          .containsEntry("httpStatus", "503")
          .containsEntry("reason", "Ingester not ready: waiting for 15s after being ready");
      assertThat(c.healthAsync().get().details()).containsEntry("reason", "starting");
      assertThat(server.requests()).allSatisfy(r -> assertThat(r.uri()).isEqualTo("/ready"));
    }
  }

  @Test
  void healthUnreachableAndClosed() throws Exception {
    TempoClient c =
        TempoClient.builder()
            .url("http://127.0.0.1:" + freePort())
            .retry(RetryPolicy.builder().maxRetries(0).build())
            .build();
    HealthStatus down = c.health();
    assertThat(down.healthy()).isFalse();
    assertThat(down.status()).isEqualTo("unreachable");
    c.close();
    assertThatThrownBy(c::health).isInstanceOf(CancelledException.class);
  }

  @Test
  void pingSendsTenantAndMapsMissingTenant() throws Exception {
    server
        .enqueue(Fixtures.replay("searchTags.v2-scope-intrinsic"))
        .enqueue(Fixtures.replay("searchTags.mt-no-tenant-401"))
        .enqueue(Fixtures.text(401, "denied"));
    try (TempoClient c =
        TempoClient.builder().url(server.url()).tenantId("tenant-a").token("proxy-token").build()) {
      Duration d = c.ping();
      assertThat(d).isPositive();
      MockServer.Recorded r = server.requests().get(0);
      assertThat(r.uri()).isEqualTo("/api/v2/search/tags?scope=intrinsic");
      assertThat(r.header("X-Scope-OrgID")).isEqualTo("tenant-a");
      assertThat(r.header("Authorization")).isEqualTo("Bearer proxy-token");
      assertThat(r.header("User-Agent")).startsWith("grow-codes-grafana-tempo/").endsWith("(java)");
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .hasMessage("ping: tenant required (X-Scope-OrgID) (HTTP 401)");
      assertThatThrownBy(() -> c.pingAsync().get())
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(AuthenticationException.class)
          .satisfies(e -> assertThat(e.getCause().getMessage()).doesNotContain("proxy-token"));
    }
  }

  @Test
  void serverVersionFromBuildInfo() throws Exception {
    server.enqueue(Fixtures.replay("buildInfo.ok")).enqueue(200, "{\"version\":\"v3.2.0\"}");
    try (TempoClient c = client()) {
      assertThat(c.serverVersion()).isEqualTo("3.1.1");
      assertThat(c.serverVersionAsync().get()).isEqualTo("3.2.0");
    }
  }

  @Test
  void serverVersionFallsBackToStatusVersion() {
    server
        .enqueue(Fixtures.replay("buildInfo.wrong-path-404"))
        .enqueue(Fixtures.replay("serverVersion.status-version-text"))
        .enqueue(200, "{}")
        .enqueue(Fixtures.text(404, "404 page not found"))
        .enqueue(Fixtures.text(404, "404 page not found"))
        .enqueue(Fixtures.text(200, "no version here"))
        .enqueue(Fixtures.text(500, "broken"))
        .enqueue(Fixtures.text(500, "broken"))
        .enqueue(Fixtures.text(500, "broken"))
        .enqueue(Fixtures.text(500, "broken"));
    try (TempoClient c = client()) {
      assertThat(c.serverVersion()).isEqualTo("3.1.1");
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnavailableException.class);
    }
  }

  @Test
  void delegatesProbesToTheQueryClient() throws Exception {
    server
        .enqueue(Fixtures.replay("buildInfo.ok"))
        .enqueue(Fixtures.replay("buildInfo.ok"))
        .enqueue(Fixtures.replay("echo.ok"))
        .enqueue(Fixtures.replay("echo.ok"))
        .enqueue(Fixtures.replay("ready.ok"))
        .enqueue(Fixtures.replay("ready.ok"));
    try (TempoClient c = client()) {
      assertThat(c.buildInfo().revision()).isEqualTo("9db7df79d");
      assertThat(c.buildInfoAsync().get().branch()).isEqualTo("HEAD");
      assertThat(c.echo()).isEqualTo("echo");
      assertThat(c.echoAsync().get()).isEqualTo("echo");
      assertThat(c.ready()).isTrue();
      assertThat(c.readyAsync().get()).isTrue();
      assertThat(c.query().client()).isSameAs(c);
      assertThat(c.transport()).isNotNull();
    }
  }

  @Test
  void closeIsIdempotentAndClosesResources() {
    TempoClient c = client();
    AtomicInteger closed = new AtomicInteger();
    c.register(closed::incrementAndGet);
    c.register(
        () -> {
          throw new IllegalStateException("ignored");
        });
    assertThat(c.isClosed()).isFalse();
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(closed.get()).isEqualTo(1);
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
  }

  @Test
  void builderSettersAndRedaction() {
    Hooks hooks = new Hooks() {};
    TempoClient c =
        TempoClient.builder()
            .fromEnv(EnvReader.of(Map.of(P + "TENANT_ID", "env-tenant")))
            .url(server.url())
            .token(Secret.of("s3cr3t-token"))
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .retry(RetryPolicy.defaults())
            .tls(TlsOptions.defaults())
            .proxy("http://proxy.local:3128")
            .header("X-Extra", "1")
            .header("X-Scope-OrgID", "overridden")
            .userAgentSuffix("app/1")
            .hooks(hooks)
            .logger(Logger.NOOP)
            .build();
    TempoConfig cfg = c.config();
    assertThat(cfg.tenantId()).contains("env-tenant");
    assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
    assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(cfg.proxy()).contains("http://proxy.local:3128");
    assertThat(cfg.userAgentSuffix()).contains("app/1");
    assertThat(cfg.hooks()).isSameAs(hooks);
    assertThat(cfg.logger()).isSameAs(Logger.NOOP);
    assertThat(cfg.retry()).isNotNull();
    assertThat(cfg.tls()).isNotNull();
    assertThat(cfg.token()).isPresent();
    assertThat(cfg.headers()).containsEntry("X-Extra", "1");
    assertThat(cfg.toTransportConfig().headers())
        .containsEntry("X-Scope-OrgID", "env-tenant")
        .containsEntry("Authorization", "Bearer s3cr3t-token")
        .doesNotContainValue("overridden");
    assertThat(cfg.toTransportConfig().knownSecrets()).containsExactly("s3cr3t-token");
    assertThat(c.toString()).contains("[REDACTED]").doesNotContain("s3cr3t-token");
    c.close();
    TempoClient tenantClient = TempoClient.builder().url(server.url()).tenantId("t").build();
    assertThat(tenantClient.config().tenantId()).contains("t");
    tenantClient.close();
  }

  @Test
  void clearedTokenIsNotSent() {
    Secret token = Secret.of("abcd-token");
    TempoConfig cfg = TempoConfig.builder().token(token).build();
    token.clear();
    assertThat(cfg.toTransportConfig().headers()).doesNotContainKey("Authorization");
    assertThat(cfg.toTransportConfig().knownSecrets()).isEmpty();
    assertThat(TempoConfig.builder().token((String) null).build().token()).isEmpty();
  }

  @Test
  void configDefaults() {
    TempoConfig cfg = TempoConfig.builder().build();
    assertThat(cfg.url()).isEqualTo("http://127.0.0.1:3200");
    assertThat(cfg.tenantId()).isEmpty();
    assertThat(cfg.token()).isEmpty();
    assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(cfg.proxy()).isEmpty();
    assertThat(cfg.userAgentSuffix()).isEmpty();
    assertThat(cfg.toString()).contains("token=null").contains("tenantId=null");
    assertThat(cfg.toTransportConfig().headers()).isEmpty();
  }

  @Test
  void fromEnvPrecedence() {
    Map<String, String> env = new HashMap<>();
    env.put("TEMPO_URL", "http://vendor:3200");
    env.put("GROW_TEMPO_URL", "http://alias:3200");
    env.put("GROW_TEMPO_TENANT_ID", "alias-tenant");
    env.put("GROW_TEMPO_TOKEN", "alias-token");
    assertThat(TempoConfig.fromEnv(env).url()).isEqualTo("http://vendor:3200");
    assertThat(TempoConfig.fromEnv(env).tenantId()).contains("alias-tenant");
    assertThat(TempoConfig.fromEnv(env).token().map(Secret::reveal)).contains("alias-token");
    env.put("TEMPO_TENANT_ID", " vendor-tenant ");
    assertThat(TempoConfig.fromEnv(env).tenantId()).contains("vendor-tenant");
    env.put(P + "URL", "http://grow:3200");
    env.put(P + "TENANT_ID", "grow-tenant");
    env.put(P + "TOKEN", "grow-token");
    env.put(P + "CONNECT_TIMEOUT", "1500");
    env.put(P + "REQUEST_TIMEOUT", "2s");
    env.put(P + "TOTAL_TIMEOUT", "1m");
    env.put(P + "MAX_RETRIES", "5");
    env.put("GROW_TEMPO_PROXY", "http://proxy:3128");
    TempoConfig cfg = TempoConfig.fromEnv(env);
    assertThat(cfg.url()).isEqualTo("http://grow:3200");
    assertThat(cfg.tenantId()).contains("grow-tenant");
    assertThat(cfg.token().map(Secret::reveal)).contains("grow-token");
    assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(cfg.retry().maxRetries()).isEqualTo(5);
    assertThat(cfg.proxy()).contains("http://proxy:3128");
    TempoConfig explicit =
        TempoConfig.builder()
            .url("http://explicit:3200")
            .tenantId("x")
            .token("t")
            .retry(RetryPolicy.defaults())
            .tls(TlsOptions.defaults())
            .proxy("http://p:1")
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(1))
            .totalTimeout(Duration.ofSeconds(1))
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.url()).isEqualTo("http://explicit:3200");
    assertThat(explicit.tenantId()).contains("x");
    assertThat(explicit.proxy()).contains("http://p:1");
  }

  @Test
  void fromEnvTlsAliasesAndErrors() {
    Map<String, String> env = new HashMap<>();
    env.put("GROW_TEMPO_INSECURE_SKIP_VERIFY", "yes");
    env.put("GROW_TEMPO_CA_CERT", "/tmp/ca.pem");
    env.put("GROW_TEMPO_CLIENT_CERT", "/tmp/c.pem");
    env.put("GROW_TEMPO_CLIENT_KEY", "/tmp/k.pem");
    TlsOptions tls = TempoConfig.fromEnv(env).tls();
    assertThat(tls.insecureSkipVerify()).isTrue();
    assertThat(tls.caCertPath()).isPresent();
    assertThat(tls.clientCertPath()).isPresent();
    assertThat(tls.clientKeyPath()).isPresent();
    assertThatThrownBy(() -> TempoConfig.fromEnv(Map.of(P + "MAX_RETRIES", "-1")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "MAX_RETRIES");
    assertThatThrownBy(() -> TempoConfig.fromEnv(Map.of(P + "REQUEST_TIMEOUT", "soon")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "REQUEST_TIMEOUT")
        .hasMessageNotContaining("soon");
    assertThat(TempoClient.fromEnv().config()).isNotNull();
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> TempoConfig.builder().url("ftp://x").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> TempoConfig.builder().url("http://bad host").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> TempoConfig.builder().url("relative").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> TempoConfig.builder().connectTimeout(Duration.ZERO).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> TempoConfig.builder().requestTimeout(Duration.ofSeconds(-1)).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> TempoConfig.builder().proxy("not a url").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> TempoConfig.builder().proxy("relative/path").build())
        .isInstanceOf(ConfigException.class);
    for (String bad : new String[] {"", "bad/tenant", "a||b", ".", "..", "x".repeat(151), "ü"}) {
      assertThatThrownBy(() -> TempoConfig.builder().tenantId(bad).build())
          .as(bad)
          .isInstanceOf(ConfigException.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("bad/tenant"));
    }
    TempoConfig.validateTenantId(null);
    TempoConfig.validateTenantId("tenant-b|tenant-a");
    TempoConfig.validateTenantId("A9!-_.*'()");
  }
}
