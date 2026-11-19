# Client and probes

`TempoClient` talks to Tempo's HTTP API (port 3200). It owns the transport (timeouts, retries, TLS,
proxy, hooks) and hands out the [query client](query.md) through `query`. The shared client
behaviour (construction, hooks, logger, closing) is described on [Client](../client.md).

| Function | Endpoint | Returns |
| - | - | - |
| `health()` | `GET /ready` | `HealthStatus {healthy, status, details}`; HTTP 503 while Tempo starts → `unhealthy`, network error → `unreachable`; never throws for those |
| `ping()` | `GET /api/v2/search/tags?scope=intrinsic` | latency (TS/Python: milliseconds; Rust/Go/Java: `Duration`); multi-tenant Tempo without tenant → `AUTHENTICATION` |
| `serverVersion()` | `GET /api/status/buildinfo`, then `GET /status/version` | `"3.1.1"` (no leading `v`); neither available → `UNSUPPORTED` |
| `buildInfo()` | `GET /api/status/buildinfo` | `{version, revision, branch, buildUser, buildDate, goVersion}` |
| `echo()` | `GET /api/echo` | `"echo"` (no tenant needed) |
| `ready()` | `GET /ready` | `true` when ready, `false` during start-up (503); other errors are raised |
| `close()` | — | idempotent |

`buildInfo`, `echo` and `ready` also exist on the query client.

## Example

=== "TypeScript"

    ```ts
    import { TempoClient } from "@grow-codes/grafana-tempo";

    const client = new TempoClient({ url: "http://tempo:3200", tenantId: "team-a" });
    const health = await client.health();          // { healthy: true, status: "ok", ... }
    const latencyMs = await client.ping();
    const version = await client.serverVersion();  // "3.1.1"
    const info = await client.buildInfo();         // info.goVersion, info.revision
    console.log(await client.echo(), await client.ready());
    await client.close();
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::{TempoClient, TempoConfig};

    let client = TempoClient::new(
        TempoConfig::builder().url("http://tempo:3200").tenant_id("team-a").build()?,
    )?;
    let health = client.health().await?;
    let latency = client.ping().await?;            // Duration
    let version = client.server_version().await?;  // "3.1.1"
    let info = client.build_info().await?;
    println!("{} {}", client.echo().await?, client.ready().await?);
    client.close().await;
    ```

=== "Go"

    ```go
    client, err := tempo.NewClient(tempo.Config{
        CommonConfig: tempo.CommonConfig{URL: "http://tempo:3200"},
        TenantID:     "team-a",
    })
    if err != nil {
        return err
    }
    defer client.Close()
    health, _ := client.Health(ctx)
    latency, err := client.Ping(ctx)          // time.Duration
    version, err := client.ServerVersion(ctx) // "3.1.1"
    info, err := client.BuildInfo(ctx)
    echo, err := client.Echo(ctx)
    ready, err := client.Ready(ctx)
    ```

=== "Python"

    ```python
    from grow_grafana_tempo import TempoClient, TempoConfig

    with TempoClient(TempoConfig(url="http://tempo:3200", tenant_id="team-a")) as client:
        health = client.health()
        latency_ms = client.ping()
        version = client.server_version()  # "3.1.1"
        info = client.build_info()
        print(client.echo(), client.ready())
    ```

=== "Java"

    ```java
    TempoConfig config =
        TempoConfig.builder().url("http://tempo:3200").tenantId("team-a").build();
    try (TempoClient client = TempoClient.create(config)) {
      HealthStatus health = client.health();
      Duration latency = client.ping();
      String version = client.serverVersion(); // "3.1.1"
      BuildInfo info = client.buildInfo();
      System.out.println(client.echo() + " " + client.ready());
    }
    ```
