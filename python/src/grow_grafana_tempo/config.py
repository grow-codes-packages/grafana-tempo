"""Configuration for the Grafana Tempo client.

This file is owned by the service package (generated once by sdk-kit).
"""

from __future__ import annotations

import dataclasses
import re
from collections.abc import Mapping
from typing import Any, ClassVar, TypeVar

from ._core import BaseConfig, ConfigError, EnvReader, Secret

__all__ = [
    "DEFAULT_URL",
    "TENANT_HEADER",
    "TempoConfig",
    "validate_tenant_id",
]

#: Default endpoint of the Tempo query API (HTTP port 3200).
DEFAULT_URL = "http://127.0.0.1:3200"

#: Header that selects the tenant on a multi-tenant Tempo (query API and OTLP ingest; gRPC
#: metadata uses the lower-case form ``x-scope-orgid``). A single-tenant Tempo ignores it.
TENANT_HEADER = "X-Scope-OrgID"

_MAX_TENANT_LENGTH = 150
_TENANT_CHARS = re.compile(r"^[A-Za-z0-9!\-_.*'()]+$")

_C = TypeVar("_C", bound="TempoConfig")


def validate_tenant_id(tenant_id: str | None, what: str = "tenant_id") -> None:
    """Raise :class:`ConfigError` when ``tenant_id`` breaks Tempo's tenant id rules.

    Each tenant is 1-150 characters of letters, digits and ``!-_.*'()``; ``|`` joins several
    tenants for a federated query (``"tenant-b|tenant-a"``). ``None`` means "no tenant".
    The message never echoes the value.
    """
    if tenant_id is None:
        return
    for tenant in tenant_id.split("|"):
        if not tenant or len(tenant) > _MAX_TENANT_LENGTH or tenant in (".", ".."):
            raise ConfigError(f"config: {what} must be 1-150 characters per tenant")
        if not _TENANT_CHARS.match(tenant):
            raise ConfigError(f"config: {what} may contain only letters, digits and !-_.*'()")


@dataclasses.dataclass(frozen=True, repr=False)
class TempoConfig(BaseConfig):
    """Immutable settings for :class:`~grow_grafana_tempo.TempoClient` (query API, port 3200).

    Common fields (``url``, timeouts, ``retry``, ``tls``, ``proxy``, ``headers``,
    ``user_agent_suffix``, ``hooks``, ``logger``) come from :class:`BaseConfig`. Durations are
    float seconds; ``datetime.timedelta`` is accepted.

    Tempo has no authentication of its own. ``token`` (sent as ``Authorization: Bearer``) and
    ``headers`` exist for an authenticating reverse proxy in front of Tempo; both are redacted
    in ``repr()``, logs and error messages.

    Environment (``from_env``), precedence explicit > ``GROW_GRAFANA_TEMPO_<NAME>`` > vendor >
    default: ``URL`` (vendor ``TEMPO_URL``, ``GROW_TEMPO_URL``), ``TENANT_ID``
    (``TEMPO_TENANT_ID``, ``GROW_TEMPO_TENANT_ID``), ``TOKEN`` (``GROW_TEMPO_TOKEN``),
    ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``, ``MAX_RETRIES``, ``CA_CERT``,
    ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY``, ``PROXY`` (vendor aliases
    ``GROW_TEMPO_<NAME>``).
    """

    #: Vendor variables per GROW_* suffix, in precedence order.
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {
        "URL": ("TEMPO_URL", "GROW_TEMPO_URL"),
        "TENANT_ID": ("TEMPO_TENANT_ID", "GROW_TEMPO_TENANT_ID"),
        "TOKEN": ("GROW_TEMPO_TOKEN",),
        "CONNECT_TIMEOUT": ("GROW_TEMPO_CONNECT_TIMEOUT",),
        "REQUEST_TIMEOUT": ("GROW_TEMPO_REQUEST_TIMEOUT",),
        "TOTAL_TIMEOUT": ("GROW_TEMPO_TOTAL_TIMEOUT",),
        "MAX_RETRIES": ("GROW_TEMPO_MAX_RETRIES",),
        "CA_CERT": ("GROW_TEMPO_CA_CERT",),
        "CLIENT_CERT": ("GROW_TEMPO_CLIENT_CERT",),
        "CLIENT_KEY": ("GROW_TEMPO_CLIENT_KEY",),
        "INSECURE_SKIP_VERIFY": ("GROW_TEMPO_INSECURE_SKIP_VERIFY",),
        "PROXY": ("GROW_TEMPO_PROXY",),
    }

    url: str = DEFAULT_URL
    #: Bearer token for an authenticating proxy in front of Tempo (Tempo itself has no auth).
    token: Secret | None = None
    #: Tenant sent as ``X-Scope-OrgID`` on every call; required by a multi-tenant Tempo,
    #: ignored by a single-tenant one. ``"a|b"`` runs a federated query.
    tenant_id: str | None = None

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        token = reader.string("TOKEN", cls.ENV_VENDOR.get("TOKEN", ()))
        if token is not None:
            values["token"] = Secret(token)
        tenant = reader.string("TENANT_ID", cls.ENV_VENDOR.get("TENANT_ID", ()))
        if tenant is not None:
            values["tenant_id"] = tenant.strip()
        return values

    def validate(self: _C) -> _C:
        """Common checks plus the token and tenant rules; raises :class:`ConfigError`."""
        super().validate()
        if self.token is not None and not self.token.reveal().strip():
            raise ConfigError("config: token must not be blank")
        validate_tenant_id(self.tenant_id)
        return self

    def tenant_headers(self) -> dict[str, str]:
        """``{"X-Scope-OrgID": tenant}`` when a tenant is set, else ``{}``."""
        return {} if self.tenant_id is None else {TENANT_HEADER: self.tenant_id}

    def request_headers(self) -> dict[str, str]:
        """Headers added to every query call: the tenant and, when set, the bearer token."""
        headers = self.tenant_headers()
        if self.token is not None:
            headers["Authorization"] = f"Bearer {self.token.reveal()}"
        return headers
