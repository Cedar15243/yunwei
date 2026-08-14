from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
MODULE_SPEC = importlib.util.spec_from_file_location(
    "merge_production_overlay", ROOT / "deploy" / "merge_production_overlay.py"
)
MODULE = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(MODULE)


class ProductionOverlayTest(unittest.TestCase):
    def fixture(self, root: Path) -> tuple[Path, Path, Path, Path, Path, Path]:
        gateway = root / "gateway.env"
        voiceprint = root / "voiceprint.env"
        gateway_overlay = root / "gateway-overlay.env"
        voiceprint_overlay = root / "voiceprint-overlay.env"
        gateway_output = root / "gateway-output.env"
        voiceprint_output = root / "voiceprint-output.env"
        gateway.write_text(
            "\n".join(
                [
                    "V9_AI_MODEL=qwen3-vl-plus",
                    "V9_ASR_MODEL=fun-asr-realtime",
                    "V9_AI_API_KEY=provider-secret-kept-in-base",
                    "",
                ]
            ),
            "utf-8",
        )
        voiceprint.write_text(
            "\n".join(
                [
                    "IFLYTEK_APP_ID=provider-app-id",
                    "IFLYTEK_API_KEY=provider-api-key",
                    "IFLYTEK_API_SECRET=provider-api-secret",
                    "V9_VOICEPRINT_URL=https://api.xf-yun.com/v1/private/s1aa729d0",
                    "V9_VOICEPRINT_ADMIN_TOKEN_SHA256=" + "0" * 64,
                    "",
                ]
            ),
            "utf-8",
        )
        gateway_overlay.write_text(
            "\n".join(
                [
                    "V9_CONTENT_MANIFEST_SYNC_BASE_URL=https://project.supabase.co/functions/v1/ops-glasses",
                    "V9_CONTENT_MANIFEST_SYNC_TOKEN=manifest-sync-token-long-enough",
                    "V9_CONTROL_PLANE_SYNC_BASE_URL=https://project.supabase.co/functions/v1/ops-glasses",
                    "V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN=bootstrap-token-long-enough",
                    "V9_KNOWLEDGE_PARSER_TOKEN_SHA256=" + "a" * 64,
                    "",
                ]
            ),
            "utf-8",
        )
        voiceprint_overlay.write_text(
            "\n".join(
                [
                    "V9_VOICEPRINT_ADMIN_TOKEN_SHA256=" + "b" * 64,
                    "V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256=" + "c" * 64,
                    "",
                ]
            ),
            "utf-8",
        )
        return (
            gateway,
            voiceprint,
            gateway_overlay,
            voiceprint_overlay,
            gateway_output,
            voiceprint_output,
        )

    def test_merges_only_approved_runtime_overlays_with_private_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = self.fixture(Path(directory))
            gateway, voiceprint, gateway_overlay, voiceprint_overlay, gateway_output, voiceprint_output = paths
            gateway_values = MODULE.parse_environment(gateway)
            voiceprint_values = MODULE.parse_environment(voiceprint)
            gateway_overlay_values = MODULE.parse_environment(gateway_overlay)
            voiceprint_overlay_values = MODULE.parse_environment(voiceprint_overlay)
            MODULE.validate_inputs(
                gateway_values,
                voiceprint_values,
                gateway_overlay_values,
                voiceprint_overlay_values,
            )
            MODULE.atomic_write(gateway_output, {**gateway_values, **gateway_overlay_values})
            MODULE.atomic_write(
                voiceprint_output, {**voiceprint_values, **voiceprint_overlay_values}
            )

            merged_gateway = MODULE.parse_environment(gateway_output)
            merged_voiceprint = MODULE.parse_environment(voiceprint_output)
            self.assertEqual("qwen3-vl-plus", merged_gateway["V9_AI_MODEL"])
            self.assertEqual(
                "manifest-sync-token-long-enough",
                merged_gateway["V9_CONTENT_MANIFEST_SYNC_TOKEN"],
            )
            self.assertEqual("b" * 64, merged_voiceprint["V9_VOICEPRINT_ADMIN_TOKEN_SHA256"])
            self.assertEqual(
                "c" * 64,
                merged_voiceprint["V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256"],
            )
            if os.name != "nt":
                self.assertEqual(0o600, gateway_output.stat().st_mode & 0o777)
                self.assertEqual(0o600, voiceprint_output.stat().st_mode & 0o777)

    def test_rejects_overlay_scope_and_provider_baseline_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = self.fixture(Path(directory))
            gateway, voiceprint, gateway_overlay, voiceprint_overlay, _, _ = paths
            gateway_values = MODULE.parse_environment(gateway)
            voiceprint_values = MODULE.parse_environment(voiceprint)
            gateway_overlay_values = MODULE.parse_environment(gateway_overlay)
            voiceprint_overlay_values = MODULE.parse_environment(voiceprint_overlay)

            with self.assertRaisesRegex(ValueError, "gateway_overlay_key_forbidden"):
                MODULE.validate_inputs(
                    gateway_values,
                    voiceprint_values,
                    {**gateway_overlay_values, "V9_AI_MODEL": "unapproved-model"},
                    voiceprint_overlay_values,
                )
            with self.assertRaisesRegex(ValueError, "provider_model_not_approved"):
                MODULE.validate_inputs(
                    {**gateway_values, "V9_AI_MODEL": "unapproved-model"},
                    voiceprint_values,
                    gateway_overlay_values,
                    voiceprint_overlay_values,
                )
            with self.assertRaisesRegex(ValueError, "voiceprint_service_not_approved"):
                MODULE.validate_inputs(
                    gateway_values,
                    {
                        **voiceprint_values,
                        "V9_VOICEPRINT_URL": "https://api.xf-yun.com/v1/private/legacy",
                    },
                    gateway_overlay_values,
                    voiceprint_overlay_values,
                )


if __name__ == "__main__":
    unittest.main()
