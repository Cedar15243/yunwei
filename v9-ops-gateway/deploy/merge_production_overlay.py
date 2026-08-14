#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit


APPROVED_AI_MODEL = "qwen3-vl-plus"
APPROVED_ASR_MODEL = "fun-asr-realtime"
APPROVED_VOICEPRINT_URL = "https://api.xf-yun.com/v1/private/s1aa729d0"
GATEWAY_OVERLAY_KEYS = {
    "V9_CONTENT_MANIFEST_SYNC_BASE_URL",
    "V9_CONTENT_MANIFEST_SYNC_TOKEN",
    "V9_CONTROL_PLANE_SYNC_BASE_URL",
    "V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN",
    "V9_KNOWLEDGE_PARSER_TOKEN_SHA256",
}
VOICEPRINT_OVERLAY_KEYS = {
    "V9_VOICEPRINT_ADMIN_TOKEN_SHA256",
    "V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256",
}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def parse_environment(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text("utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError("environment_line_invalid")
        name, value = line.split("=", 1)
        name = name.strip()
        value = value.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ValueError("environment_name_invalid")
        if name in values:
            raise ValueError(f"environment_duplicate:{name}")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        if any(character in value for character in ("\r", "\n", "\0")):
            raise ValueError(f"environment_value_invalid:{name}")
        values[name] = value
    return values


def required(environment: dict[str, str], name: str) -> str:
    value = environment.get(name, "").strip()
    if not value:
        raise ValueError(f"environment_missing:{name}")
    return value


def validate_https_endpoint(name: str, value: str) -> None:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(f"environment_url_invalid:{name}")


def validate_overlay_keys(environment: dict[str, str], allowed: set[str], label: str) -> None:
    unexpected = sorted(set(environment) - allowed)
    missing = sorted(allowed - set(environment))
    if unexpected:
        raise ValueError(f"{label}_overlay_key_forbidden:{unexpected[0]}")
    if missing:
        raise ValueError(f"{label}_overlay_key_missing:{missing[0]}")


def validate_inputs(
    gateway: dict[str, str],
    voiceprint: dict[str, str],
    gateway_overlay: dict[str, str],
    voiceprint_overlay: dict[str, str],
) -> None:
    if required(gateway, "V9_AI_MODEL") != APPROVED_AI_MODEL:
        raise ValueError("provider_model_not_approved")
    if required(gateway, "V9_ASR_MODEL") != APPROVED_ASR_MODEL:
        raise ValueError("asr_model_not_approved")
    if required(voiceprint, "V9_VOICEPRINT_URL") != APPROVED_VOICEPRINT_URL:
        raise ValueError("voiceprint_service_not_approved")

    validate_overlay_keys(gateway_overlay, GATEWAY_OVERLAY_KEYS, "gateway")
    validate_overlay_keys(voiceprint_overlay, VOICEPRINT_OVERLAY_KEYS, "voiceprint")
    manifest_url = required(gateway_overlay, "V9_CONTENT_MANIFEST_SYNC_BASE_URL")
    control_plane_url = required(gateway_overlay, "V9_CONTROL_PLANE_SYNC_BASE_URL")
    validate_https_endpoint("V9_CONTENT_MANIFEST_SYNC_BASE_URL", manifest_url)
    validate_https_endpoint("V9_CONTROL_PLANE_SYNC_BASE_URL", control_plane_url)
    if manifest_url != control_plane_url or not manifest_url.endswith(
        "/functions/v1/ops-glasses"
    ):
        raise ValueError("control_plane_endpoint_mismatch")

    for name in (
        "V9_CONTENT_MANIFEST_SYNC_TOKEN",
        "V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN",
    ):
        if len(required(gateway_overlay, name)) < 24:
            raise ValueError(f"environment_token_invalid:{name}")
    for environment, name in (
        (gateway_overlay, "V9_KNOWLEDGE_PARSER_TOKEN_SHA256"),
        (voiceprint_overlay, "V9_VOICEPRINT_ADMIN_TOKEN_SHA256"),
    ):
        if not SHA256_PATTERN.fullmatch(required(environment, name)):
            raise ValueError(f"environment_sha256_invalid:{name}")
    previous_hash = voiceprint_overlay.get(
        "V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256", ""
    )
    if previous_hash and not SHA256_PATTERN.fullmatch(previous_hash):
        raise ValueError(
            "environment_sha256_invalid:V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256"
        )


def atomic_write(path: Path, environment: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent, prefix=f".{path.name}.", text=True
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            for name, value in environment.items():
                stream.write(f"{name}={value}\n")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-base", required=True)
    parser.add_argument("--voiceprint-base", required=True)
    parser.add_argument("--gateway-overlay", required=True)
    parser.add_argument("--voiceprint-overlay", required=True)
    parser.add_argument("--gateway-output", required=True)
    parser.add_argument("--voiceprint-output", required=True)
    args = parser.parse_args()

    gateway = parse_environment(Path(args.gateway_base))
    voiceprint = parse_environment(Path(args.voiceprint_base))
    gateway_overlay = parse_environment(Path(args.gateway_overlay))
    voiceprint_overlay = parse_environment(Path(args.voiceprint_overlay))
    validate_inputs(gateway, voiceprint, gateway_overlay, voiceprint_overlay)
    atomic_write(Path(args.gateway_output), {**gateway, **gateway_overlay})
    atomic_write(Path(args.voiceprint_output), {**voiceprint, **voiceprint_overlay})
    print("Production overlays validated and merged without exposing values.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from None
