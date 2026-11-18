// Basics of the Grafana Tempo TypeScript SDK: configuration from the environment, hooks, health,
// an authenticated ping, the server version and error handling.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:<port> GROW_GRAFANA_TEMPO_TOKEN=... npm start

import { TempoClient, TempoError } from "@grow-codes/grafana-tempo";

const client = TempoClient.fromEnv({
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
  hooks: {
    onError(info) {
      if (info.willRetry)
        console.error(`retrying ${info.method} ${info.url} (attempt ${info.attempt})`);
    },
  },
});
console.log(`config: ${client.config}`); // credentials print as [REDACTED]

try {
  const health = await client.health(); // never throws for an unreachable service
  console.log("health:", health);
  if (!health.healthy) process.exitCode = 1;

  const latency = await client.ping({ signal: AbortSignal.timeout(10_000) });
  console.log(`ping: ${latency.toFixed(1)} ms`);

  try {
    console.log("server version:", await client.serverVersion());
  } catch (err) {
    if (err instanceof TempoError && err.code === "UNSUPPORTED") {
      console.log("server version: not reported by this service");
    } else {
      throw err;
    }
  }
} catch (err) {
  if (err instanceof TempoError) {
    // Canonical code + HTTP status + request id; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, {
      httpStatus: err.httpStatus,
      requestId: err.requestId,
    });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close();
}
