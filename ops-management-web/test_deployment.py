from pathlib import Path
import importlib.util
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
DEPLOY = ROOT / "deploy"


def load_merge_module():
    spec = importlib.util.spec_from_file_location(
        "dingdang_ops_management_merge_caddy", DEPLOY / "merge_caddy.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ManagementWebDeploymentContractTest(unittest.TestCase):
    def test_compose_uses_a_release_image_and_an_isolated_loopback_port(self):
        content = (ROOT / "docker-compose.cloud.yml").read_text("utf-8")

        self.assertIn("OPS_MANAGEMENT_IMAGE", content)
        self.assertIn("container_name: dingdang-ops-management-web", content)
        self.assertIn('entrypoint: ["nginx", "-g", "daemon off;"]', content)
        self.assertIn('127.0.0.1:${OPS_MANAGEMENT_PORT:-8788}:8080', content)
        self.assertIn("healthcheck:", content)
        self.assertIn("read_only: true", content)
        self.assertIn("no-new-privileges:true", content)
        self.assertIn("cap_drop:", content)
        self.assertIn("cap_add:", content)
        self.assertIn("- SETUID", content)
        self.assertNotIn("8787", content)
        self.assertNotIn("8790", content)
        self.assertNotIn("expert-collab", content)

    def test_nginx_exposes_health_and_applies_browser_security_headers(self):
        content = (ROOT / "nginx.conf").read_text("utf-8")

        self.assertIn("location = /health", content)
        self.assertIn('"service":"dingdang-ops-management-web', content)
        self.assertIn("Content-Security-Policy", content)
        self.assertIn("X-Content-Type-Options", content)
        self.assertIn("X-Frame-Options", content)
        self.assertIn("Referrer-Policy", content)
        self.assertIn("Cache-Control", content)
        self.assertIn("location /assets/", content)

    def test_environment_template_requires_a_dedicated_domain_without_secrets(self):
        content = (ROOT / ".env.cloud.example").read_text("utf-8")

        self.assertIn("OPS_MANAGEMENT_DOMAIN=ops.example.com", content)
        self.assertIn("OPS_MANAGEMENT_PORT=8788", content)
        self.assertNotIn("service_role", content.lower())
        self.assertNotRegex(content, r"(?i)(password|secret|private_key)=\\S+")

    def test_caddy_merge_only_appends_or_replaces_its_managed_site(self):
        module = load_merge_module()
        original = """{
    admin off
}

bb.chinacedar.top:2305 {
    handle /health {
        reverse_proxy 127.0.0.1:8787
    }

    handle_path /v9-ops/* {
        reverse_proxy 127.0.0.1:8790
    }

    handle {
        root * /srv/dingdang-expert-collab/web
        file_server
    }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            caddyfile = Path(directory) / "Caddyfile"
            caddyfile.write_text(original, "utf-8")

            module.merge(
                str(caddyfile),
                str(DEPLOY / "Caddyfile.snippet.template"),
                "ops.bb.chinacedar.top",
                8788,
            )
            first = caddyfile.read_text("utf-8")
            module.merge(
                str(caddyfile),
                str(DEPLOY / "Caddyfile.snippet.template"),
                "ops.bb.chinacedar.top",
                8788,
            )
            second = caddyfile.read_text("utf-8")

        self.assertTrue(first.startswith(original))
        self.assertEqual(first, second)
        self.assertEqual(first.count(module.MANAGED_START), 1)
        self.assertEqual(first.count(module.MANAGED_END), 1)
        self.assertIn("ops.bb.chinacedar.top {", first)
        self.assertIn("reverse_proxy 127.0.0.1:8788", first)
        self.assertEqual(first.count("reverse_proxy 127.0.0.1:8787"), 1)
        self.assertEqual(first.count("reverse_proxy 127.0.0.1:8790"), 1)

    def test_caddy_merge_rejects_the_existing_expert_origin(self):
        module = load_merge_module()
        with tempfile.TemporaryDirectory() as directory:
            caddyfile = Path(directory) / "Caddyfile"
            caddyfile.write_text("bb.chinacedar.top:2305 {}\n", "utf-8")
            with self.assertRaisesRegex(ValueError, "dedicated"):
                module.merge(
                    str(caddyfile),
                    str(DEPLOY / "Caddyfile.snippet.template"),
                    "bb.chinacedar.top",
                    8788,
                )

    def test_install_orchestrates_stage_then_activation(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        self.assertIn('"$SOURCE_ROOT/deploy/stage.sh" "$RELEASE_ID"', install)
        self.assertIn('"$SOURCE_ROOT/deploy/activate.sh" "$RELEASE_ID"', install)
        self.assertNotIn("docker build", install)
        self.assertNotIn("merge_caddy.py", install)

    def test_stage_builds_an_immutable_candidate_without_touching_caddy(self):
        stage = (DEPLOY / "stage.sh").read_text("utf-8")

        self.assertIn("APP_ROOT=/srv/dingdang-ops-management-web", stage)
        self.assertIn("RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID", stage)
        self.assertIn('test ! -e "$RELEASE_DIR"', stage)
        self.assertIn('ln -sfn "$RELEASE_DIR" "$APP_ROOT/candidate"', stage)
        self.assertIn("sha256sum", stage)
        self.assertIn("docker build", stage)
        self.assertIn("OPS_MANAGEMENT_IMAGE", stage)
        self.assertIn("OPS_MANAGEMENT_IMAGE_ID", stage)
        self.assertIn("OPS_MANAGEMENT_BUILD_CONFIG_SHA256", stage)
        self.assertIn("build_config_sha256", stage)
        self.assertIn("validate_browser_supabase_config", stage)
        self.assertIn("supabase_browser_key_invalid", stage)
        self.assertIn("/auth/v1/settings", stage)
        self.assertIn('decoded.get("role") == "anon"', stage)
        self.assertNotIn("service_role", (ROOT / ".env.cloud.example").read_text("utf-8"))
        self.assertIn("assert_image_identity", stage)
        self.assertIn("docker image inspect", stage)
        self.assertIn("docker run", stage)
        self.assertIn("--entrypoint nginx", stage)
        self.assertIn('"$IMAGE" -g "daemon off;"', stage)
        self.assertIn("--read-only", stage)
        self.assertIn("docker exec", stage)
        self.assertIn("dingdang-ops-management-stage-", stage)
        self.assertIn("https://bb.chinacedar.top:2305/health", stage)
        self.assertIn("https://bb.chinacedar.top:2305/v9-ops/health", stage)
        self.assertIn("assert_container_unchanged", stage)
        self.assertIn("OLD_CANDIDATE", stage)
        self.assertIn("RELEASE_CREATED", stage)
        self.assertIn('rm -rf "$RELEASE_DIR"', stage)
        self.assertIn('ln -sfn "$OLD_CANDIDATE" "$APP_ROOT/candidate"', stage)
        self.assertNotIn('python3 "$RELEASE_DIR/deploy/merge_caddy.py"', stage)
        self.assertNotIn('python3 "$SOURCE_ROOT/deploy/merge_caddy.py"', stage)
        self.assertNotIn("caddy reload", stage)
        self.assertNotIn('ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"', stage)

    def test_activation_switches_the_verified_candidate_and_protects_expert(self):
        activate = (DEPLOY / "activate.sh").read_text("utf-8")

        self.assertIn('readlink -f "$APP_ROOT/candidate"', activate)
        self.assertIn("RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID", activate)
        self.assertIn('ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"', activate)
        self.assertIn('ln -sfn "$OLD_CURRENT" "$APP_ROOT/previous"', activate)
        self.assertIn("trap rollback_failed_activation EXIT HUP INT TERM", activate)
        self.assertIn("OPS_MANAGEMENT_IMAGE_ID", activate)
        self.assertIn("OPS_MANAGEMENT_BUILD_CONFIG_SHA256", activate)
        self.assertIn("candidate_build_config_does_not_match_production_config", activate)
        self.assertIn("assert_image_identity", activate)
        self.assertIn("--project-name dingdang-ops-management", activate)
        self.assertIn("merge_caddy.py", activate)
        self.assertIn("docker exec ai-edge-caddy caddy validate", activate)
        self.assertIn("docker exec ai-edge-caddy caddy reload", activate)
        self.assertIn("https://bb.chinacedar.top:2305/health", activate)
        self.assertIn("https://bb.chinacedar.top:2305/v9-ops/health", activate)
        self.assertIn("assert_container_unchanged", activate)
        self.assertIn('OPS_MANAGEMENT_PORT=$port docker compose', activate)
        self.assertNotIn("docker restart", activate)
        self.assertNotIn("systemctl restart caddy", activate)
        self.assertNotIn("systemctl restart dingdang-expert", activate)

    def test_rollback_switches_to_previous_release_and_rechecks_all_services(self):
        content = (DEPLOY / "rollback.sh").read_text("utf-8")

        self.assertIn('if test -L "$APP_ROOT/previous"', content)
        self.assertIn('unlink "$APP_ROOT/current"', content)
        self.assertIn('compose_release "$CURRENT" "$CURRENT_IMAGE" "$CURRENT_PORT" down', content)
        self.assertIn("OPS_MANAGEMENT_IMAGE", content)
        self.assertIn("OPS_MANAGEMENT_IMAGE_ID", content)
        self.assertIn("assert_image_identity", content)
        self.assertIn("--project-name dingdang-ops-management", content)
        self.assertIn("docker exec ai-edge-caddy caddy validate", content)
        self.assertIn("docker exec ai-edge-caddy caddy reload", content)
        self.assertIn("https://bb.chinacedar.top:2305/health", content)
        self.assertIn("https://bb.chinacedar.top:2305/v9-ops/health", content)
        self.assertIn("assert_container_unchanged", content)
        self.assertIn('OPS_MANAGEMENT_PORT=$port docker compose', content)
        self.assertNotIn("docker restart", content)

    def test_health_check_covers_local_public_expert_and_v9_routes(self):
        content = (DEPLOY / "health-check.sh").read_text("utf-8")

        self.assertIn("127.0.0.1:${OPS_MANAGEMENT_PORT:-8788}/health", content)
        self.assertIn("https://${OPS_MANAGEMENT_DOMAIN}/health", content)
        self.assertIn("https://bb.chinacedar.top:2305/health", content)
        self.assertIn("https://bb.chinacedar.top:2305/v9-ops/health", content)
        self.assertIn("dingdang-ops-management-web", content)
        self.assertIn("expert-collab", content)
        self.assertIn("dingdang-v9-gateway", content)
        self.assertIn("json.load(sys.stdin)", content)
        self.assertNotIn('grep -Fq "\\\"service\\\"', content)

    def test_stage_activation_and_rollback_parse_health_as_json(self):
        stage = (DEPLOY / "stage.sh").read_text("utf-8")
        activate = (DEPLOY / "activate.sh").read_text("utf-8")
        rollback = (DEPLOY / "rollback.sh").read_text("utf-8")

        for content in (stage, activate, rollback):
            self.assertIn("json.load(sys.stdin)", content)
            self.assertNotIn('grep -Fq "\\\"service\\\"', content)


if __name__ == "__main__":
    unittest.main()
