"""TempoConfig: tenant, vendor variables, validation, headers."""

from __future__ import annotations

import pytest

import grow_grafana_tempo as sdk

P = "GROW_GRAFANA_TEMPO_"


def test_defaults() -> None:
    cfg = sdk.TempoConfig()
    assert cfg.url == sdk.DEFAULT_URL == "http://127.0.0.1:3200"
    assert cfg.tenant_id is None and cfg.token is None
    assert cfg.tenant_headers() == {} and cfg.request_headers() == {}


def test_env_precedence_url_and_tenant() -> None:
    env = {"GROW_TEMPO_URL": "http://alias:1", "GROW_TEMPO_TENANT_ID": "alias"}
    cfg = sdk.TempoConfig.from_env(env)
    assert (cfg.url, cfg.tenant_id) == ("http://alias:1", "alias")
    env.update({"TEMPO_URL": "http://vendor:1", "TEMPO_TENANT_ID": "vendor"})
    cfg = sdk.TempoConfig.from_env(env)
    assert (cfg.url, cfg.tenant_id) == ("http://vendor:1", "vendor")
    env.update({P + "URL": "http://grow:1", P + "TENANT_ID": " grow "})
    cfg = sdk.TempoConfig.from_env(env)
    assert (cfg.url, cfg.tenant_id) == ("http://grow:1", "grow")
    cfg = sdk.TempoConfig.from_env(env, tenant_id="explicit")
    assert cfg.tenant_id == "explicit"
    assert sdk.TempoConfig.from_env({"GROW_TEMPO_TOKEN": "tok-1234"}).token is not None


def test_headers() -> None:
    cfg = sdk.TempoConfig(tenant_id="tenant-b|tenant-a", token="tok-secret")
    assert cfg.tenant_headers() == {sdk.TENANT_HEADER: "tenant-b|tenant-a"}
    assert cfg.request_headers() == {
        "X-Scope-OrgID": "tenant-b|tenant-a",
        "Authorization": "Bearer tok-secret",
    }
    assert "tok-secret" not in repr(cfg)
    assert "tenant-b|tenant-a" in repr(cfg)


@pytest.mark.parametrize("tenant", ["bad/tenant", "", "a||b", "x" * 151, ".", "..", "sp ace", "ü"])
def test_invalid_tenants(tenant: str) -> None:
    with pytest.raises(sdk.ConfigError) as info:
        sdk.TempoConfig(tenant_id=tenant).validate()
    if tenant:
        assert tenant not in str(info.value).replace("tenant_id", "")


def test_valid_tenants() -> None:
    for tenant in ("tenant-a", "a|b", "A.b_c-d!*'()", "x" * 150):
        sdk.validate_tenant_id(tenant)
    sdk.validate_tenant_id(None)
