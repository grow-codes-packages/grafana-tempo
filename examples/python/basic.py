"""Health, ping and server version with the synchronous client."""

from __future__ import annotations

import grow_grafana_tempo as sdk


def main() -> None:
    # Reads GROW_GRAFANA_TEMPO_URL / TEMPO_URL, GROW_GRAFANA_TEMPO_TENANT_ID / TEMPO_TENANT_ID …
    with sdk.TempoClient.from_env() as client:
        status = client.health()
        print(f"healthy={status.healthy} status={status.status}")
        try:
            print(f"ping: {client.ping():.1f} ms")
            print(f"server version: {client.server_version()}")
        except sdk.AuthenticationError as err:
            print(f"multi-tenant Tempo needs a tenant (X-Scope-OrgID): {err}")
        except sdk.TempoError as err:
            print(f"failed with {err.code}: {err} (retryable={err.retryable})")


if __name__ == "__main__":
    main()
