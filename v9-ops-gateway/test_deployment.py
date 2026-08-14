from pathlib import Path
import importlib.util
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
DEPLOY = ROOT / "deploy"
SUPABASE = ROOT.parent / "supabase"
MERGE_CADDY_SPEC = importlib.util.spec_from_file_location(
    "dingdang_v9_merge_caddy", DEPLOY / "merge_caddy.py"
)
MERGE_CADDY = importlib.util.module_from_spec(MERGE_CADDY_SPEC)
MERGE_CADDY_SPEC.loader.exec_module(MERGE_CADDY)
merge = MERGE_CADDY.merge


class DeploymentContractTest(unittest.TestCase):
    def test_supabase_template_only_keeps_the_sync_token_digest_and_fixed_identity(self):
        content = (SUPABASE / ".env.example").read_text("utf-8")

        self.assertIn("V9_GATEWAY_SYNC_TOKEN_SHA256=", content)
        self.assertIn("V9_GATEWAY_SYNC_ORGANIZATION_ID=org-huafang", content)
        self.assertIn("V9_GATEWAY_SYNC_USER_ID=user-field-engineer", content)
        self.assertIn("V9_GATEWAY_SYNC_DEVICE_ID=air3-REPLACE_WITH_DEVICE_ID", content)
        self.assertIn("V9_KNOWLEDGE_PARSER_URL=https://", content)
        self.assertIn("V9_KNOWLEDGE_PARSER_TOKEN=", content)
        self.assertNotIn("V9_CONTENT_MANIFEST_SYNC_TOKEN=", content)
        self.assertNotIn("V9_KNOWLEDGE_PARSER_TOKEN_SHA256=", content)

    def test_environment_template_pins_previous_model_without_real_secrets(self):
        content = (DEPLOY / "dingdang-v9-gateway.env.example").read_text("utf-8")
        voiceprint = (DEPLOY / "dingdang-v9-voiceprint.env.example").read_text("utf-8")

        self.assertIn("V9_AI_MODEL=qwen3-vl-plus", content)
        self.assertIn("V9_ASR_MODEL=fun-asr-realtime", content)
        self.assertIn("V9_DEVICE_ID=air3-REPLACE_WITH_DEVICE_ID", content)
        self.assertIn("V9_ORGANIZATION_ID=org-huafang", content)
        self.assertIn("V9_USER_ID=user-field-engineer", content)
        self.assertIn(
            "V9_ASR_URL=wss://dashscope.aliyuncs.com/api-ws/v1/inference", content
        )
        self.assertIn("V9_LISTEN_HOST=127.0.0.1", content)
        self.assertIn("V9_LISTEN_PORT=8790", content)
        self.assertIn("V9_CONTENT_MANIFEST_SYNC_BASE_URL=", content)
        self.assertIn("V9_CONTENT_MANIFEST_SYNC_TOKEN=", content)
        self.assertIn("V9_CONTENT_MANIFEST_REFRESH_SECONDS=60", content)
        self.assertIn("V9_CONTENT_MANIFEST_MAX_BACKOFF_SECONDS=900", content)
        self.assertIn("V9_CONTROL_PLANE_SYNC_BASE_URL=", content)
        self.assertIn("V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN=", content)
        self.assertIn("V9_CONTROL_PLANE_SYNC_RETRY_BASE_SECONDS=5", content)
        self.assertIn("V9_CONTROL_PLANE_SYNC_MAX_BACKOFF_SECONDS=900", content)
        self.assertIn("V9_CONTROL_PLANE_SYNC_POLL_SECONDS=1", content)
        self.assertIn("V9_CONTROL_PLANE_SYNC_BATCH_SIZE=25", content)
        self.assertIn("V9_MVS_BASE_URL=", content)
        self.assertIn("V9_MVS_AUTHORIZATION=", content)
        self.assertIn("V9_MVS_ENGINEER_ID=", content)
        self.assertIn("V9_MVS_WRITE_ENABLED=false", content)
        self.assertIn("V9_MVS_TIMEOUT_SECONDS=8", content)
        self.assertIn("V9_MVS_RETRY_INTERVAL_SECONDS=", content)
        self.assertIn("V9_MVS_RETRY_BASE_SECONDS=", content)
        self.assertIn("V9_MVS_RETRY_STALE_SECONDS=", content)
        self.assertIn("V9_MVS_RETRY_MAX_ATTEMPTS=", content)
        self.assertIn("V9_MVS_RETRY_BATCH_SIZE=", content)
        self.assertIn("V9_KNOWLEDGE_PARSER_TOKEN_SHA256=", content)
        self.assertIn(
            "V9_VOICEPRINT_URL=https://api.xf-yun.com/v1/private/s1aa729d0",
            voiceprint,
        )
        self.assertIn("V9_VOICEPRINT_THRESHOLD=0.70", voiceprint)
        self.assertNotIn("sk-", content)
        self.assertNotRegex(content, r"V9_AI_API_KEY=\S{16,}")
        self.assertNotRegex(content, r"V9_ASR_API_KEY=\S{16,}")
        self.assertNotRegex(content, r"V9_CONTENT_MANIFEST_SYNC_TOKEN=\S{16,}")
        self.assertNotRegex(content, r"V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN=\S{16,}")
        self.assertNotRegex(content, r"V9_MVS_AUTHORIZATION=\S{16,}")
        self.assertNotRegex(content, r"V9_KNOWLEDGE_PARSER_TOKEN_SHA256=[0-9a-f]{64}")
        self.assertNotRegex(voiceprint, r"IFLYTEK_(?:APP_ID|API_KEY|API_SECRET)=\S{8,}")

    def test_systemd_service_is_isolated_and_reads_root_only_environment(self):
        content = (DEPLOY / "dingdang-v9-gateway.service").read_text("utf-8")

        self.assertIn("EnvironmentFile=/etc/dingdang-v9-gateway.env", content)
        self.assertIn("EnvironmentFile=/etc/dingdang-v9-voiceprint.env", content)
        self.assertIn("DynamicUser=yes", content)
        self.assertIn("StateDirectory=dingdang-v9-gateway", content)
        self.assertIn("ProtectSystem=strict", content)
        self.assertIn(
            "ExecStart=/opt/dingdang-v9-gateway/current/.venv/bin/python "
            "/opt/dingdang-v9-gateway/current/gateway.py",
            content,
        )
        self.assertNotIn("8787", content)

    def test_caddy_snippet_only_adds_the_v9_prefix_with_security_headers(self):
        content = (DEPLOY / "Caddyfile.snippet").read_text("utf-8")

        self.assertIn("@v9_ops_root path /v9-ops", content)
        self.assertIn("handle @v9_ops_root", content)
        self.assertIn("rewrite * /health", content)
        self.assertIn("handle_path /v9-ops/*", content)
        self.assertIn("reverse_proxy 127.0.0.1:8790", content)
        self.assertEqual(content.count("Content-Security-Policy"), 2)
        self.assertEqual(content.count("X-Frame-Options"), 2)
        self.assertEqual(content.count("Strict-Transport-Security"), 2)
        self.assertEqual(content.count("X-Content-Type-Options"), 2)
        self.assertEqual(content.count("Cache-Control"), 2)
        self.assertEqual(content.count("-Server"), 2)
        self.assertNotIn("handle /health", content)

    def test_caddy_merge_upgrades_the_legacy_v9_block_without_touching_expert_routes(self):
        legacy = """bb.chinacedar.top:2305 {
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
        expected_expert = """    handle /health {
        reverse_proxy 127.0.0.1:8787
    }
"""
        with tempfile.TemporaryDirectory() as directory:
            caddyfile = Path(directory) / "Caddyfile"
            caddyfile.write_text(legacy, "utf-8")

            merge(str(caddyfile), str(DEPLOY / "Caddyfile.snippet"))
            upgraded = caddyfile.read_text("utf-8")

        self.assertIn(expected_expert, upgraded)
        self.assertIn("@v9_ops_root path /v9-ops", upgraded)
        self.assertIn("rewrite * /health", upgraded)
        self.assertIn("Content-Security-Policy", upgraded)
        self.assertEqual(upgraded.count("handle_path /v9-ops/*"), 1)
        self.assertNotEqual(upgraded, legacy)

    def test_caddy_merge_refreshes_the_managed_v9_block_idempotently(self):
        managed = """bb.chinacedar.top:2305 {
    handle /health {
        reverse_proxy 127.0.0.1:8787
    }

    @v9_ops_root path /v9-ops
    handle @v9_ops_root {
        header Cache-Control "no-store"
        rewrite * /health
        reverse_proxy 127.0.0.1:8790
    }

    handle_path /v9-ops/* {
        header Cache-Control "no-store"
        reverse_proxy 127.0.0.1:8790
    }

    handle {
        root * /srv/dingdang-expert-collab/web
        file_server
    }
}
"""
        expected_expert = """    handle /health {
        reverse_proxy 127.0.0.1:8787
    }
"""
        expected_fallback = """    handle {
        root * /srv/dingdang-expert-collab/web
        file_server
    }
"""
        with tempfile.TemporaryDirectory() as directory:
            caddyfile = Path(directory) / "Caddyfile"
            caddyfile.write_text(managed, "utf-8")

            merge(str(caddyfile), str(DEPLOY / "Caddyfile.snippet"))
            upgraded = caddyfile.read_text("utf-8")
            merge(str(caddyfile), str(DEPLOY / "Caddyfile.snippet"))
            repeated = caddyfile.read_text("utf-8")

        self.assertIn(expected_expert, upgraded)
        self.assertIn(expected_fallback, upgraded)
        self.assertEqual(upgraded.count("-Server"), 2)
        self.assertEqual(upgraded.count("handle_path /v9-ops/*"), 1)
        self.assertEqual(repeated, upgraded)

    def test_install_and_rollback_validate_expert_health(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")
        rollback = (DEPLOY / "rollback.sh").read_text("utf-8")

        self.assertIn("trap rollback_failed_install EXIT", install)
        self.assertIn("wait_for_health http://127.0.0.1:8790/health", install)
        self.assertIn("sleep 1", install)
        self.assertIn(
            'python3 "$SOURCE_ROOT/deploy/merge_caddy.py"',
            install,
        )
        self.assertNotIn(
            "if ! grep -Fq 'handle_path /v9-ops/*'",
            install,
        )
        self.assertGreaterEqual(
            install.count("systemctl restart dingdang-v9-gateway.service"), 2
        )
        for content in (install, rollback):
            self.assertIn("https://bb.chinacedar.top:2305/health", content)
            self.assertIn("caddy validate", content)

    def test_install_includes_the_standard_library_asr_proxy(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        self.assertIn('test -f "$SOURCE_ROOT/asr_proxy.py"', install)
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/asr_proxy.py" "$RELEASE_DIR/asr_proxy.py"',
            install,
        )

    def test_install_builds_a_release_venv_for_the_private_document_parser(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")
        requirements = (ROOT / "requirements.txt").read_text("utf-8")

        self.assertIn("pypdf==6.15.0", requirements)
        self.assertIn('test -f "$SOURCE_ROOT/knowledge_document_parser.py"', install)
        self.assertIn('test -f "$SOURCE_ROOT/requirements.txt"', install)
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/knowledge_document_parser.py" '
            '"$RELEASE_DIR/knowledge_document_parser.py"',
            install,
        )
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/requirements.txt" '
            '"$RELEASE_DIR/requirements.txt"',
            install,
        )

    def test_install_preflights_python_venv_before_creating_release(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")
        preflight = "python3 -c 'import ensurepip, venv'"

        self.assertIn(preflight, install)
        self.assertLess(
            install.index(preflight),
            install.index('install -d -m 0755 "$RELEASE_DIR" "$STATE_DIR"'),
        )
        self.assertIn('python3 -m venv "$RELEASE_DIR/.venv"', install)
        self.assertIn(
            '"$RELEASE_DIR/.venv/bin/python" -m pip install --requirement '
            '"$RELEASE_DIR/requirements.txt"',
            install,
        )

    def test_install_includes_voiceprint_modules_and_root_only_environment(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        for module in ("voiceprint_proxy.py", "voiceprint_lifecycle.py"):
            self.assertIn(f'test -f "$SOURCE_ROOT/{module}"', install)
            self.assertIn(
                f'install -m 0644 "$SOURCE_ROOT/{module}" "$RELEASE_DIR/{module}"',
                install,
            )
        self.assertIn("test -f /etc/dingdang-v9-voiceprint.env", install)
        self.assertIn(
            'test "$(stat -c %a /etc/dingdang-v9-voiceprint.env)" = "600"',
            install,
        )

    def test_production_overlay_release_is_staged_activated_and_rollback_safe(self):
        stage = (DEPLOY / "stage-production-overlay.sh").read_text("utf-8")
        activate = (DEPLOY / "activate-production-overlay.sh").read_text("utf-8")
        rollback = (DEPLOY / "rollback-production-overlay.sh").read_text("utf-8")
        merger = (DEPLOY / "merge_production_overlay.py").read_text("utf-8")

        for content in (stage, activate, rollback):
            self.assertIn("/etc/dingdang-v9-gateway.env", content)
            self.assertIn("/etc/dingdang-v9-voiceprint.env", content)
            self.assertNotIn("ai-edge-caddy", content)
            self.assertNotIn("dingdang-expert-collab", content)

        self.assertIn("V9_CONTENT_MANIFEST_SYNC_TOKEN", merger)
        self.assertIn("V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN", merger)
        self.assertIn("V9_KNOWLEDGE_PARSER_TOKEN_SHA256", merger)
        self.assertIn("V9_VOICEPRINT_ADMIN_TOKEN_SHA256", merger)
        self.assertIn("APPROVED_AI_MODEL=qwen3-vl-plus", stage)
        self.assertIn("APPROVED_ASR_MODEL=fun-asr-realtime", stage)
        self.assertIn("APPROVED_VOICEPRINT_SERVICE=s1aa729d0", stage)
        self.assertIn('grep -Fx "V9_AI_MODEL=$APPROVED_AI_MODEL"', stage)
        self.assertIn('grep -Fx "V9_ASR_MODEL=$APPROVED_ASR_MODEL"', stage)
        self.assertIn('stat -c %U:%G "$GATEWAY_OVERLAY"', stage)
        self.assertIn('stat -c %a "$VOICEPRINT_OVERLAY"', stage)
        self.assertIn("candidate", stage)
        self.assertNotIn("systemctl restart", stage)
        for tool in (
            "merge_production_overlay.py",
            "stage-production-overlay.sh",
            "activate-production-overlay.sh",
            "rollback-production-overlay.sh",
        ):
            self.assertIn(f'install -m 0700 "$SOURCE_ROOT/{tool}"', stage)
            self.assertIn(tool, stage[stage.index("sha256sum") :])

        self.assertIn("systemctl restart dingdang-v9-gateway.service", activate)
        self.assertIn("LOCAL_BASE=http://127.0.0.1:8790", activate)
        self.assertIn("PUBLIC_V9_BASE=https://bb.chinacedar.top:2305/v9-ops", activate)
        self.assertIn("EXPERT_HEALTH=https://bb.chinacedar.top:2305/health", activate)
        self.assertIn('wait_for_health "$LOCAL_BASE/health"', activate)
        self.assertIn('wait_for_health "$LOCAL_BASE/ready"', activate)
        self.assertIn('wait_for_health "$PUBLIC_V9_BASE/health"', activate)
        self.assertIn('wait_for_health "$PUBLIC_V9_BASE/ready"', activate)
        self.assertIn('wait_for_health "$EXPERT_HEALTH"', activate)
        self.assertNotIn('"$SOURCE_ROOT/rollback-production-overlay.sh"', activate)
        self.assertIn("trap rollback_failed_activation EXIT", activate)
        self.assertIn("trap interrupt_activation HUP INT TERM", activate)
        self.assertIn("CHANGES_STARTED=1", activate)
        self.assertIn('(cd "$CURRENT" && sha256sum -c SHA256SUMS)', activate)
        self.assertIn('atomic_link "$CURRENT" "$CONFIG_ROOT/current"', activate)
        self.assertIn('atomic_link "$PREVIOUS_TARGET" "$CONFIG_ROOT/previous"', activate)

        self.assertIn("previous", rollback)
        self.assertIn("systemctl restart dingdang-v9-gateway.service", rollback)
        self.assertIn("trap restore_current_on_failure EXIT", rollback)
        self.assertIn("trap interrupt_rollback HUP INT TERM", rollback)
        self.assertIn('atomic_link "$CURRENT" "$CONFIG_ROOT/current"', rollback)
        self.assertIn('atomic_link "$PREVIOUS" "$CONFIG_ROOT/previous"', rollback)

    def test_runtime_update_only_switches_v9_code_with_backup_and_rollback(self):
        update = (DEPLOY / "update-runtime.sh").read_text("utf-8")

        self.assertIn('test -f "$SOURCE_ROOT/gateway.py"', update)
        self.assertIn('test -f "$SOURCE_ROOT/voiceprint_lifecycle.py"', update)
        self.assertIn('"$CURRENT/.venv/bin/python" "$CURRENT/backup_restore.py" backup', update)
        self.assertIn('"$CURRENT/.venv/bin/python" "$CURRENT/backup_restore.py" verify', update)
        self.assertIn('cp -a "$CURRENT/." "$STAGING/"', update)
        self.assertIn('install -m 0644 "$SOURCE_ROOT/gateway.py"', update)
        self.assertIn('install -m 0644 "$SOURCE_ROOT/voiceprint_lifecycle.py"', update)
        self.assertIn('"$STAGING/.venv/bin/python" -m py_compile', update)
        self.assertIn('"$STAGING/gateway.py"', update)
        self.assertIn('"$STAGING/voiceprint_lifecycle.py"', update)
        self.assertIn('GATEWAY_ENV_FILE=${V9_GATEWAY_ENV_FILE:-/etc/dingdang-v9-gateway.env}', update)
        self.assertIn('VOICEPRINT_ENV_FILE=${V9_VOICEPRINT_ENV_FILE:-/etc/dingdang-v9-voiceprint.env}', update)
        self.assertIn('. "$GATEWAY_ENV_FILE"', update)
        self.assertIn('. "$VOICEPRINT_ENV_FILE"', update)
        self.assertIn("configuration_from_environment(os.environ)", update)
        self.assertIn("voiceprint_runtime_configuration_from_environment", update)
        self.assertIn('ln -sfn "$CURRENT" "$APP_ROOT/previous"', update)
        self.assertIn('ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"', update)
        self.assertIn('PREVIOUS_TARGET=$(readlink -f "$APP_ROOT/previous")', update)
        self.assertIn('ln -sfn "$PREVIOUS_TARGET" "$APP_ROOT/previous"', update)
        self.assertIn("trap rollback_failed_update EXIT", update)
        self.assertIn("trap interrupt_update HUP INT TERM", update)
        self.assertIn("systemctl restart dingdang-v9-gateway.service", update)
        self.assertIn('wait_for_health "$LOCAL_BASE/ready"', update)
        self.assertIn('wait_for_health "$PUBLIC_V9_BASE/ready"', update)
        self.assertIn('wait_for_health "$EXPERT_HEALTH"', update)
        self.assertIn("BACKUP_DIR", update)
        self.assertNotIn(" restore ", update)
        for forbidden in (
            "ai-edge-caddy",
            "caddy reload",
            "docker restart",
            "/etc/dingdang-v9-gateway.env.next",
            "/etc/dingdang-v9-voiceprint.env.next",
            "cp /etc/dingdang-v9-gateway.env",
            "cp /etc/dingdang-v9-voiceprint.env",
        ):
            self.assertNotIn(forbidden, update)

    def test_install_includes_execution_context_and_managed_identity_guards(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        self.assertIn('test -f "$SOURCE_ROOT/execution_context.py"', install)
        self.assertIn('test -f "$SOURCE_ROOT/content_manifest_sync.py"', install)
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/execution_context.py" "$RELEASE_DIR/execution_context.py"',
            install,
        )
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/content_manifest_sync.py" "$RELEASE_DIR/content_manifest_sync.py"',
            install,
        )
        self.assertIn("V9_ORGANIZATION_ID", install)
        self.assertIn("V9_USER_ID", install)
        self.assertIn("V9_CONTENT_MANIFEST_SYNC_BASE_URL", install)
        self.assertIn("V9_CONTENT_MANIFEST_SYNC_TOKEN", install)

    def test_install_includes_the_control_plane_outbox_relay(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        self.assertIn('test -f "$SOURCE_ROOT/control_plane_sync.py"', install)
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/control_plane_sync.py" "$RELEASE_DIR/control_plane_sync.py"',
            install,
        )
        self.assertIn("V9_CONTROL_PLANE_SYNC_BASE_URL", install)
        self.assertIn("V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN", install)

    def test_install_includes_the_mvs_server_side_connector(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        self.assertIn('test -f "$SOURCE_ROOT/mvs_work_order.py"', install)
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/mvs_work_order.py" "$RELEASE_DIR/mvs_work_order.py"',
            install,
        )
        self.assertIn("V9_MVS_BASE_URL", install)
        self.assertIn("V9_MVS_AUTHORIZATION", install)
        self.assertIn("V9_MVS_ENGINEER_ID", install)
        self.assertIn("V9_MVS_WRITE_ENABLED", install)

    def test_install_snapshots_and_restores_sqlite_before_release_switch(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")

        self.assertIn("DB_BACKUP_DIR", install)
        self.assertIn("backup_databases", install)
        self.assertIn("restore_databases", install)
        self.assertIn("sqlite3", install)
        self.assertLess(
            install.index("backup_databases"),
            install.index('ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"'),
        )
        rollback_block = install[install.index("rollback_failed_install") :]
        self.assertIn("restore_databases", rollback_block)

    def test_backup_and_restore_drill_assets_are_installed_and_scheduled(self):
        install = (DEPLOY / "install.sh").read_text("utf-8")
        backup = (DEPLOY / "backup.sh").read_text("utf-8")
        drill = (DEPLOY / "restore-drill.sh").read_text("utf-8")
        service = (DEPLOY / "dingdang-v9-backup.service").read_text("utf-8")
        timer = (DEPLOY / "dingdang-v9-backup.timer").read_text("utf-8")

        self.assertIn('test -f "$SOURCE_ROOT/backup_restore.py"', install)
        self.assertIn("BACKUP_ROOT=${V9_BACKUP_DIR:-/var/backups/dingdang-v9-gateway}", install)
        self.assertIn('install -d -m 0700 "$BACKUP_ROOT"', install)
        self.assertLess(
            install.index('install -d -m 0700 "$BACKUP_ROOT"'),
            install.index("systemctl enable --now dingdang-v9-backup.timer"),
        )
        self.assertIn("dingdang-v9-backup.timer", install)
        self.assertIn("systemctl enable --now dingdang-v9-backup.timer", install)
        self.assertIn("backup_restore.py backup", backup)
        self.assertIn("backup_restore.py verify", drill)
        self.assertIn("PRAGMA quick_check", drill)
        self.assertIn("RESTORE_DRILL_SECONDS", drill)
        self.assertIn("EVIDENCE_FILES_EXPECTED", drill)
        self.assertIn("BACKUP_MANIFEST_SHA256", drill)
        self.assertIn("GATEWAY_DB_QUICK_CHECK", drill)
        self.assertIn("VOICEPRINT_DB_QUICK_CHECK", drill)
        self.assertIn("PUBLIC_HEALTH", drill)
        self.assertIn("EXPERT_HEALTH", drill)
        self.assertIn("/health", drill)
        self.assertIn("/ready", drill)
        self.assertIn(
            "ReadWritePaths=/var/lib/dingdang-v9-gateway "
            "/var/backups/dingdang-v9-gateway",
            service,
        )
        self.assertIn("Persistent=true", timer)
        self.assertIn("OnCalendar=*-*-* 02:15:00", timer)

    def test_monitoring_assets_probe_v9_readiness_without_touching_expert_routes(self):
        health_check = (DEPLOY / "health-check.sh").read_text("utf-8")
        alerts = (DEPLOY / "monitoring" / "prometheus-rules.yml").read_text("utf-8")
        monitor_service = (DEPLOY / "dingdang-v9-monitor.service").read_text("utf-8")
        monitor_timer = (DEPLOY / "dingdang-v9-monitor.timer").read_text("utf-8")
        monitor_install = (DEPLOY / "install-monitoring.sh").read_text("utf-8")
        monitor_rollback = (DEPLOY / "rollback-monitoring.sh").read_text("utf-8")
        monitor_environment = (DEPLOY / "dingdang-v9-monitor.env.example").read_text(
            "utf-8"
        )

        self.assertIn("/v9-ops/health", health_check)
        self.assertIn("/v9-ops/ready", health_check)
        self.assertIn("/health", health_check)
        self.assertIn("DingdangV9GatewayUnavailable", alerts)
        self.assertIn("DingdangV9GatewayReadinessFailed", alerts)
        self.assertIn("DingdangV9ProbeLatencyHigh", alerts)
        self.assertNotIn("handle /health", (DEPLOY / "Caddyfile.snippet").read_text("utf-8"))
        self.assertIn("EnvironmentFile=-/etc/dingdang-v9-monitor.env", monitor_service)
        self.assertIn("User=root", monitor_service)
        self.assertIn(
            "ExecStart=/usr/bin/python3 /opt/dingdang-v9-monitor/current/monitoring_probe.py",
            monitor_service,
        )
        self.assertIn("ProtectSystem=strict", monitor_service)
        self.assertIn("ReadWritePaths=/var/lib/dingdang-v9-monitor", monitor_service)
        self.assertIn("OnUnitActiveSec=1m", monitor_timer)
        self.assertIn("Persistent=true", monitor_timer)
        self.assertIn('test -f "$SOURCE_ROOT/monitoring_probe.py"', monitor_install)
        self.assertIn('RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID', monitor_install)
        self.assertIn("release_id_invalid", monitor_install)
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-monitor.service" '
            '"$RELEASE_DIR/dingdang-v9-monitor.service"',
            monitor_install,
        )
        self.assertIn(
            'install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-monitor.timer" '
            '"$RELEASE_DIR/dingdang-v9-monitor.timer"',
            monitor_install,
        )
        self.assertIn(
            'install -m 0644 "$OLD_CURRENT/dingdang-v9-monitor.service"',
            monitor_install,
        )
        self.assertIn("systemctl enable --now dingdang-v9-monitor.timer", monitor_install)
        self.assertIn("systemctl start dingdang-v9-monitor.service", monitor_install)
        self.assertIn("systemctl disable --now dingdang-v9-monitor.timer", monitor_install)
        self.assertNotIn("caddy", monitor_install.lower())
        self.assertNotIn("dingdang-expert-collab", monitor_install)
        self.assertIn('PREVIOUS=$(readlink -f "$APP_ROOT/previous")', monitor_rollback)
        self.assertIn(
            'install -m 0644 "$PREVIOUS/dingdang-v9-monitor.service"',
            monitor_rollback,
        )
        self.assertIn(
            'install -m 0644 "$PREVIOUS/dingdang-v9-monitor.timer"',
            monitor_rollback,
        )
        self.assertIn("V9_MONITOR_WEBHOOK_URL=", monitor_environment)
        self.assertIn("V9_MONITOR_WEBHOOK_BEARER_TOKEN=", monitor_environment)
        self.assertNotRegex(monitor_environment, r"V9_MONITOR_WEBHOOK_BEARER_TOKEN=\S+")


if __name__ == "__main__":
    unittest.main()
