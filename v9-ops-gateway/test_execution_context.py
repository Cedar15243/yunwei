import sqlite3
import threading
import unittest
from datetime import datetime, timezone

from execution_context import ExecutionContextRepository


class MutableClock:
    def __init__(self, value: float = 1_800_000_000.0):
        self.value = value

    def __call__(self) -> float:
        return self.value


class ExecutionContextRepositoryTest(unittest.TestCase):
    def setUp(self):
        self.clock = MutableClock()
        self.connection = sqlite3.connect(":memory:")
        self.connection.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.connection.executescript(
            """
            PRAGMA foreign_keys=ON;
            CREATE TABLE ops_projects (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                local_project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                UNIQUE(device_id, local_project_id)
            );
            CREATE TABLE maintenance_tasks (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                project_id TEXT NOT NULL REFERENCES ops_projects(id),
                local_task_id TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL,
                UNIQUE(device_id, local_task_id)
            );
            """
        )
        self.connection.executemany(
            """
            INSERT INTO ops_projects(
                id, device_id, local_project_id, title, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'active', ?, ?)
            """,
            [
                ("project-a", "air3-a", "project-local-a", "冷站维护", 1.0, 1.0),
                ("project-b", "air3-b", "project-local-b", "其他项目", 1.0, 1.0),
            ],
        )
        self.connection.executemany(
            """
            INSERT INTO maintenance_tasks(
                id, device_id, project_id, local_task_id, title, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                ("task-a", "air3-a", "project-a", "task-local-a", "冷机报警", "active", 1.0, 1.0),
                ("task-b", "air3-b", "project-b", "task-local-b", "其他任务", "active", 1.0, 1.0),
            ],
        )
        self.connection.commit()
        self.repository = ExecutionContextRepository(
            self.connection, self.lock, self.clock
        )

    def tearDown(self):
        self.connection.close()

    def skill_command(self):
        return {
            "versionId": "skill-hvac@1.0.0",
            "organizationId": "org-huafang",
            "skillId": "skill-hvac",
            "version": "1.0.0",
            "name": "冷机控制器诊断 Skill",
            "status": "published",
            "rules": {
                "applicableWhen": {"systems": ["hvac"]},
                "excludedWhen": {"assetStates": ["decommissioned"]},
                "requiredInputs": ["description", "photo"],
                "evidenceSchema": {"required": ["photo"]},
                "steps": [
                    {
                        "id": "check-controller-power",
                        "instruction": "检查控制器电源指示灯",
                        "risk": "low",
                    }
                ],
                "safety": {
                    "forbiddenActions": ["bypass_interlock"],
                    "expertEscalation": ["live_voltage"],
                },
                "outputConstraints": {"maxStepsPerTurn": 1},
                "knowledgeScopes": ["hvac-manuals"],
            },
            "publishedBy": "ops-admin-a",
            "publishedAt": "2026-08-03T01:00:00Z",
        }

    def iso_timestamp(self, value):
        return datetime.fromtimestamp(value, tz=timezone.utc).isoformat().replace(
            "+00:00", "Z"
        )

    def authoritative_manifest(
        self,
        manifest_version=7,
        skill_version="1.0.0",
        skill_version_id="skill-hvac@1.0.0",
        expires_at=None,
        organization_id="org-huafang",
        skills=None,
        knowledge=None,
    ):
        rules = self.skill_command()["rules"]
        rules["outputConstraints"] = {
            "maxStepsPerTurn": 1,
            "requiredKnowledgeScopes": ["hvac-manuals"],
            "optionalKnowledgeScopes": ["hvac-bulletins"],
        }
        rules["knowledgeScopes"] = ["hvac-manuals", "hvac-bulletins"]
        if skills is None:
            skills = [
                {
                    "skillId": "skill-hvac",
                    "versionId": skill_version_id,
                    "version": skill_version,
                    "name": "冷机控制器诊断 Skill",
                    "description": "按受控步骤诊断冷机控制器。",
                    "status": "published",
                    "contentSha256": "a" * 64,
                    "rules": rules,
                    "authorizationScope": "project",
                }
            ]
        if knowledge is None:
            knowledge = [
                {
                    "knowledgeId": "knowledge-controller-manual",
                    "versionId": "knowledge-controller-manual@3",
                    "version": 3,
                    "title": "冷机控制器手册",
                    "summary": "控制器供电、报警代码与复位前检查。",
                    "language": "zh-CN",
                    "sensitivity": "internal",
                    "sourceType": "manual",
                    "sourceReference": "manuals/controller-v3.pdf",
                    "contentSha256": "b" * 64,
                    "knowledgeScopes": ["hvac-manuals"],
                    "validFrom": None,
                    "expiresAt": None,
                    "authorizationScope": "skill_version",
                }
            ]
        return {
            "manifestVersion": manifest_version,
            "etag": format(manifest_version, "064x"),
            "organizationId": organization_id,
            "userId": "user-field-a",
            "deviceId": "air3-a",
            "projectId": "project-a",
            "generatedAt": self.iso_timestamp(self.clock.value - 5),
            "expiresAt": self.iso_timestamp(
                expires_at if expires_at is not None else self.clock.value + 900
            ),
            "skills": skills,
            "knowledge": knowledge,
        }

    def sync_manifest(self, manifest=None):
        return self.repository.sync_authoritative_manifest(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            manifest=manifest or self.authoritative_manifest(),
        )

    def test_persists_manifest_sync_state_without_secret_material(self):
        self.assertEqual(
            ["project-local-a"],
            self.repository.list_content_manifest_sync_projects("air3-a"),
        )
        self.assertIsNone(
            self.repository.content_manifest_sync_state(
                "org-huafang", "user-field-a", "air3-a", "project-local-a"
            )
        )

        payload = self.authoritative_manifest()
        self.sync_manifest(payload)
        state = self.repository.content_manifest_sync_state(
            "org-huafang", "user-field-a", "air3-a", "project-local-a"
        )
        self.assertEqual(payload["manifestVersion"], state["manifestVersion"])
        self.assertEqual(payload["etag"], state["etag"])
        self.assertEqual(payload["expiresAt"], state["expiresAt"])

        self.repository.record_content_manifest_sync_status(
            "air3-a",
            "project-local-a",
            "failed",
            "content_sync_network_failed",
            2,
            self.clock.value + 120,
        )
        row = self.connection.execute(
            "SELECT * FROM content_manifest_sync_status WHERE local_project_id = ?",
            ("project-local-a",),
        ).fetchone()
        self.assertEqual("failed", row["status"])
        self.assertEqual("content_sync_network_failed", row["error_code"])
        self.assertEqual(2, row["failure_count"])
        self.assertNotIn("token", str(dict(row)).lower())
        persisted = self.repository.content_manifest_sync_state(
            "org-huafang", "user-field-a", "air3-a", "project-local-a"
        )
        self.assertEqual("failed", persisted["syncStatus"])
        self.assertEqual("content_sync_network_failed", persisted["errorCode"])
        self.assertEqual(2, persisted["failureCount"])
        self.assertEqual(self.clock.value + 120, persisted["nextRetryAt"])

    def test_authoritative_manifest_creates_an_immutable_project_identity_binding(self):
        first_manifest = self.authoritative_manifest(manifest_version=7)
        first_manifest["projectId"] = "project-cloud-a"

        self.sync_manifest(first_manifest)
        first = self.repository.project_identity_binding(
            "org-huafang", "user-field-a", "air3-a", "project-local-a"
        )

        self.assertEqual("project-a", first["gatewayProjectId"])
        self.assertEqual("project-cloud-a", first["authoritativeProjectId"])
        self.assertEqual(7, first["sourceManifestVersion"])
        self.assertEqual(first_manifest["etag"], first["sourceManifestEtag"])
        self.assertEqual("active", first["status"])

        refreshed_manifest = self.authoritative_manifest(manifest_version=8)
        refreshed_manifest["projectId"] = "project-cloud-a"
        self.sync_manifest(refreshed_manifest)
        refreshed = self.repository.project_identity_binding(
            "org-huafang", "user-field-a", "air3-a", "project-local-a"
        )

        self.assertEqual(first["gatewayProjectId"], refreshed["gatewayProjectId"])
        self.assertEqual(first["authoritativeProjectId"], refreshed["authoritativeProjectId"])
        self.assertEqual(8, refreshed["sourceManifestVersion"])
        audit = self.connection.execute(
            """
            SELECT event_type, source_manifest_version
            FROM project_identity_audit_events
            ORDER BY created_at, rowid
            """
        ).fetchall()
        self.assertEqual(
            [("bound", 7), ("refreshed", 8)],
            [(row["event_type"], row["source_manifest_version"]) for row in audit],
        )

    def test_authoritative_manifest_rejects_project_identity_rebinding(self):
        first_manifest = self.authoritative_manifest(manifest_version=7)
        first_manifest["projectId"] = "project-cloud-a"
        self.sync_manifest(first_manifest)

        forward_conflict = self.authoritative_manifest(manifest_version=8)
        forward_conflict["projectId"] = "project-cloud-b"
        with self.assertRaisesRegex(ValueError, "project_identity_conflict"):
            self.sync_manifest(forward_conflict)

        self.connection.execute(
            """
            INSERT INTO ops_projects(
                id, device_id, local_project_id, title, status, created_at, updated_at
            ) VALUES ('project-c', 'air3-a', 'project-local-c', '同设备其他项目', 'active', 1, 1)
            """
        )
        self.connection.commit()
        reverse_conflict = self.authoritative_manifest(manifest_version=9)
        reverse_conflict["projectId"] = "project-cloud-a"
        with self.assertRaisesRegex(ValueError, "project_identity_conflict"):
            self.repository.sync_authoritative_manifest(
                organization_id="org-huafang",
                user_id="user-field-a",
                device_id="air3-a",
                local_project_id="project-local-c",
                manifest=reverse_conflict,
            )

        binding = self.repository.project_identity_binding(
            "org-huafang", "user-field-a", "air3-a", "project-local-a"
        )
        self.assertEqual("project-cloud-a", binding["authoritativeProjectId"])
        manifest_count = self.connection.execute(
            "SELECT COUNT(*) AS count FROM authoritative_content_manifests"
        ).fetchone()["count"]
        self.assertEqual(1, manifest_count)

    def test_runtime_context_requires_and_audits_the_authoritative_project_binding(self):
        self.repository.require_authoritative_manifest = True
        with self.assertRaisesRegex(ValueError, "content_manifest_unavailable"):
            self.repository.build_context(
                "org-huafang", "user-field-a", "air3-a",
                "project-local-a", "task-local-a", "检查控制器", [], {}
            )

        manifest = self.authoritative_manifest(manifest_version=7, skills=[], knowledge=[])
        manifest["projectId"] = "project-cloud-a"
        self.sync_manifest(manifest)
        context = self.repository.build_context(
            "org-huafang", "user-field-a", "air3-a",
            "project-local-a", "task-local-a", "检查控制器", [], {}
        )

        self.assertEqual("project-a", context.project_id)
        self.assertEqual("project-cloud-a", context.authoritative_project_id)
        self.assertEqual(
            "project-cloud-a",
            context.to_provider_payload()["identity"]["authoritativeProjectId"],
        )
        detail = self.repository.project_detail(
            "org-huafang", "air3-a", "project-local-a"
        )
        self.assertEqual("project-cloud-a", detail["authoritativeProjectId"])

        self.repository.start_execution_run("trace-project-binding", context, "qwen3-vl-plus")
        audit = self.connection.execute(
            "SELECT authoritative_project_id FROM execution_context_runs WHERE trace_id = ?",
            ("trace-project-binding",),
        ).fetchone()
        self.assertEqual("project-cloud-a", audit["authoritative_project_id"])

        self.connection.execute(
            """
            UPDATE project_identity_bindings
            SET authoritative_project_id = 'project-cloud-corrupt'
            WHERE local_project_id = 'project-local-a'
            """
        )
        self.connection.commit()
        with self.assertRaisesRegex(ValueError, "project_identity_conflict"):
            self.repository.build_context(
                "org-huafang", "user-field-a", "air3-a",
                "project-local-a", "task-local-a", "继续检查", [], {}
            )

    def test_device_manifest_rejects_a_conflicting_project_identity_binding(self):
        self.sync_manifest()
        self.connection.execute(
            """
            UPDATE project_identity_bindings
            SET authoritative_project_id = 'project-cloud-corrupt'
            WHERE local_project_id = 'project-local-a'
            """
        )
        self.connection.commit()

        with self.assertRaisesRegex(ValueError, "project_identity_conflict"):
            self.repository.device_content_manifest(
                "org-huafang", "user-field-a", "air3-a", "project-local-a"
            )

    def test_task_skill_activation_rejects_a_conflicting_project_identity_binding(self):
        self.sync_manifest()
        self.connection.execute(
            """
            UPDATE project_identity_bindings
            SET authoritative_project_id = 'project-cloud-corrupt'
            WHERE local_project_id = 'project-local-a'
            """
        )
        self.connection.commit()

        with self.assertRaisesRegex(ValueError, "project_identity_conflict"):
            self.repository.activate_task_skill(
                organization_id="org-huafang",
                user_id="user-field-a",
                device_id="air3-a",
                local_task_id="task-local-a",
                skill_version_id="skill-hvac@1.0.0",
                local_project_id="project-local-a",
            )

    def publish_assign_activate(self):
        self.sync_manifest()
        return self.repository.activate_task_skill(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_task_id="task-local-a",
            skill_version_id="skill-hvac@1.0.0",
        )

    def test_builds_context_from_immutable_skill_memory_and_matching_instructions(self):
        snapshot = self.publish_assign_activate()
        first = self.repository.update_project_memory(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            expected_revision=0,
            summary="控制器间歇报警，尚未确认根因。",
            confirmed_facts=["控制器已上电"],
            excluded_facts=["整机断电"],
            risks=["带电检查需要绝缘防护"],
        )
        self.assertEqual(1, first["revision"])
        self.repository.put_project_instruction(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            instruction_id="instruction-controller-reset",
            expected_version=0,
            status="active",
            condition={"systems": ["hvac"], "containsAny": ["控制器", "冷机"]},
            action="先拍摄报警代码，再决定是否复位。",
            exceptions=["存在烧焦气味时禁止复位"],
            source_trace_id="trace-source-a",
        )
        self.repository.put_project_instruction(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            instruction_id="instruction-other-asset",
            expected_version=0,
            status="active",
            condition={"assetIds": ["asset-other"]},
            action="不应进入当前上下文。",
            exceptions=[],
            source_trace_id="trace-source-b",
        )

        context = self.repository.build_context(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            local_task_id="task-local-a",
            user_text="冷机控制器仍然报警",
            recent_messages=[
                {"role": "user", "content": "上一轮描述"},
                {"role": "assistant", "content": "请查看控制器状态"},
            ],
            attributes={"system": "hvac", "assetId": "asset-controller-a"},
        )

        self.assertEqual("project-a", context.project_id)
        self.assertEqual("task-a", context.task_id)
        self.assertIsNotNone(context.skill)
        self.assertEqual("skill-hvac@1.0.0", context.skill.version_id)
        self.assertEqual(snapshot.digest, context.skill.digest)
        self.assertEqual(1, context.memory_revision)
        self.assertEqual(("控制器已上电",), context.confirmed_facts)
        self.assertEqual(("整机断电",), context.excluded_facts)
        self.assertEqual(("带电检查需要绝缘防护",), context.risks)
        self.assertEqual(
            ("instruction-controller-reset",),
            tuple(item.instruction_id for item in context.instructions),
        )
        provider_payload = context.to_provider_payload()
        self.assertEqual("skill-hvac@1.0.0", provider_payload["skill"]["versionId"])
        self.assertEqual(7, provider_payload["contentAuthorization"]["manifestVersion"])
        self.assertEqual(
            ["knowledge-controller-manual@3"],
            [
                item["versionId"]
                for item in provider_payload["knowledge"]["references"]
            ],
        )
        self.assertEqual(
            [{"scope": "hvac-bulletins", "reason": "not_authorized_or_unavailable"}],
            provider_payload["knowledge"]["omissions"],
        )
        self.assertEqual(1, provider_payload["projectMemory"]["revision"])
        self.assertNotIn("不应进入当前上下文", str(provider_payload))

        self.repository.start_execution_run("trace-a", context, "qwen3-vl-plus")
        self.repository.finish_execution_run("trace-a", "success", 812)
        audit = self.connection.execute(
            "SELECT * FROM execution_context_runs WHERE trace_id = 'trace-a'"
        ).fetchone()
        self.assertEqual("success", audit["status"])
        self.assertEqual(snapshot.digest, audit["skill_snapshot_sha256"])
        self.assertEqual(1, audit["memory_revision"])
        self.assertEqual(812, audit["latency_ms"])

    def test_recent_messages_accept_display_whitespace_and_markdown(self):
        assistant_reply = (
            "## 初步判断\r\n\r\n"
            "- 设备：H3C UniServer R4900 G5\r\n"
            "- 故障：服务器无法启动\n\n"
            "\t请先检查电源指示灯。"
        )

        context = self.repository.build_context(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            local_task_id="task-local-a",
            user_text="刚才的设备型号和故障是什么？",
            recent_messages=[
                {
                    "role": "user",
                    "content": "服务器无法启动，设备型号为 H3C UniServer R4900 G5。",
                },
                {"role": "assistant", "content": assistant_reply},
            ],
            attributes={},
        )

        self.assertEqual(assistant_reply, context.recent_messages[1]["content"])

    def test_recent_messages_reject_non_display_control_characters(self):
        for character in ("\x00", "\x1b", "\x7f"):
            with self.subTest(character=repr(character)):
                with self.assertRaisesRegex(ValueError, "recent_messages_invalid"):
                    self.repository.build_context(
                        organization_id="org-huafang",
                        user_id="user-field-a",
                        device_id="air3-a",
                        local_project_id="project-local-a",
                        local_task_id="task-local-a",
                        user_text="继续检查",
                        recent_messages=[
                            {"role": "assistant", "content": f"正常文本{character}异常"}
                        ],
                        attributes={},
                    )

    def test_user_text_accepts_multiline_display_content(self):
        user_text = "现场现象：服务器无法启动。\n\n已确认：\t管理口仍可连通。"

        context = self.repository.build_context(
            organization_id="org-huafang",
            user_id="user-field-a",
            device_id="air3-a",
            local_project_id="project-local-a",
            local_task_id="task-local-a",
            user_text=user_text,
            recent_messages=[],
            attributes={},
        )

        self.assertEqual(user_text, context.user_text)

    def test_user_text_rejects_non_display_control_characters(self):
        for character in ("\x00", "\x1b", "\x7f"):
            with self.subTest(character=repr(character)):
                with self.assertRaisesRegex(ValueError, "user_text_invalid"):
                    self.repository.build_context(
                        organization_id="org-huafang",
                        user_id="user-field-a",
                        device_id="air3-a",
                        local_project_id="project-local-a",
                        local_task_id="task-local-a",
                        user_text=f"正常文本{character}异常",
                        recent_messages=[],
                        attributes={},
                    )

    def test_later_manifest_does_not_replace_active_task_skill_or_knowledge_snapshot(self):
        snapshot = self.publish_assign_activate()

        next_manifest = self.authoritative_manifest(
            manifest_version=8,
            skill_version="2.0.0",
            skill_version_id="skill-hvac@2.0.0",
        )
        next_manifest["skills"][0]["contentSha256"] = "c" * 64
        next_manifest["knowledge"][0]["versionId"] = "knowledge-controller-manual@4"
        next_manifest["knowledge"][0]["version"] = 4
        next_manifest["knowledge"][0]["contentSha256"] = "d" * 64
        self.sync_manifest(next_manifest)

        context = self.repository.build_context(
            "org-huafang", "user-field-a", "air3-a",
            "project-local-a", "task-local-a", "继续检查控制器", [], {}
        )

        self.assertEqual("skill-hvac@1.0.0", context.skill.version_id)
        self.assertEqual(snapshot.digest, context.skill.digest)
        self.assertEqual(8, context.manifest_version)
        self.assertEqual(
            ("knowledge-controller-manual@3",),
            tuple(item.version_id for item in context.knowledge_references),
        )

    def test_required_knowledge_is_rejected_and_optional_knowledge_is_recorded(self):
        missing_required = self.authoritative_manifest(knowledge=[])
        self.sync_manifest(missing_required)
        with self.assertRaisesRegex(ValueError, "required_knowledge_unavailable"):
            self.repository.activate_task_skill(
                "org-huafang", "user-field-a", "air3-a", "task-local-a",
                "skill-hvac@1.0.0", "project-local-a"
            )

        optional_only_rules = self.skill_command()["rules"]
        optional_only_rules["outputConstraints"] = {
            "maxStepsPerTurn": 1,
            "requiredKnowledgeScopes": [],
            "optionalKnowledgeScopes": ["hvac-manuals"],
        }
        optional_manifest = self.authoritative_manifest(
            manifest_version=8,
            skill_version="2.0.0",
            skill_version_id="skill-hvac@2.0.0",
            knowledge=[],
        )
        optional_manifest["skills"][0]["rules"] = optional_only_rules
        optional_manifest["skills"][0]["contentSha256"] = "c" * 64
        self.sync_manifest(optional_manifest)
        self.repository.activate_task_skill(
            "org-huafang", "user-field-a", "air3-a", "task-local-a",
            "skill-hvac@2.0.0", "project-local-a"
        )

        context = self.repository.build_context(
            "org-huafang", "user-field-a", "air3-a",
            "project-local-a", "task-local-a", "继续检查", [], {}
        )
        self.assertEqual((), context.knowledge_references)
        self.assertEqual(
            ({"scope": "hvac-manuals", "reason": "not_authorized_or_unavailable"},),
            context.knowledge_omissions,
        )

    def test_required_knowledge_is_rejected_when_current_authorization_version_is_expired(self):
        self.publish_assign_activate()
        expired_knowledge_manifest = self.authoritative_manifest(
            manifest_version=8,
            skill_version="2.0.0",
            skill_version_id="skill-hvac@2.0.0",
        )
        expired_knowledge_manifest["skills"][0]["contentSha256"] = "c" * 64
        expired_knowledge_manifest["knowledge"][0]["expiresAt"] = self.iso_timestamp(
            self.clock.value - 1
        )
        self.sync_manifest(expired_knowledge_manifest)

        with self.assertRaisesRegex(ValueError, "required_knowledge_unavailable"):
            self.repository.build_context(
                "org-huafang", "user-field-a", "air3-a",
                "project-local-a", "task-local-a", "继续检查", [], {}
            )

    def test_rejects_expired_cross_organization_and_unauthorized_manifests(self):
        with self.assertRaisesRegex(PermissionError, "content_manifest_identity_mismatch"):
            self.sync_manifest(
                self.authoritative_manifest(organization_id="org-other")
            )

        with self.assertRaisesRegex(ValueError, "content_manifest_expired"):
            self.sync_manifest(
                self.authoritative_manifest(expires_at=self.clock.value - 1)
            )

        self.sync_manifest(
            self.authoritative_manifest(manifest_version=9, skills=[])
        )
        with self.assertRaisesRegex(PermissionError, "skill_not_authorized"):
            self.repository.activate_task_skill(
                "org-huafang", "user-field-a", "air3-a", "task-local-a",
                "skill-hvac@1.0.0", "project-local-a"
            )

    def test_strict_gateway_rejects_a_legacy_task_snapshot_during_activation(self):
        published = self.repository.publish_skill_version(self.skill_command())
        self.repository.assign_skill(
            {
                "assignmentId": "assignment-legacy-a",
                "organizationId": "org-huafang",
                "skillVersionId": published["versionId"],
                "userId": "user-field-a",
                "deviceId": "air3-a",
                "projectId": "project-a",
                "status": "active",
                "activeFrom": self.clock.value - 10,
                "expiresAt": self.clock.value + 900,
            }
        )
        self.repository.activate_task_skill(
            "org-huafang", "user-field-a", "air3-a", "task-local-a",
            published["versionId"], "project-local-a"
        )
        manifest = self.authoritative_manifest(knowledge=[])
        manifest["skills"][0]["rules"] = self.skill_command()["rules"]
        manifest["skills"][0]["contentSha256"] = published["sha256"]
        self.sync_manifest(manifest)
        self.repository.require_authoritative_manifest = True

        with self.assertRaisesRegex(ValueError, "content_manifest_snapshot_missing"):
            self.repository.activate_task_skill(
                "org-huafang", "user-field-a", "air3-a", "task-local-a",
                published["versionId"], "project-local-a"
            )

    def test_ending_a_task_deactivates_skill_and_content_snapshots(self):
        self.publish_assign_activate()

        self.repository.end_task(
            "org-huafang",
            "user-field-a",
            "air3-a",
            "project-local-a",
            "task-local-a",
            "completed",
            "人工确认维修完成。",
            0,
            ["报警已清除"],
            [],
            [],
        )

        active_skill = self.connection.execute(
            """
            SELECT COUNT(*) AS count FROM task_skill_snapshots
            WHERE task_id = 'task-a' AND deactivated_at IS NULL
            """
        ).fetchone()["count"]
        active_content = self.connection.execute(
            """
            SELECT COUNT(*) AS count FROM task_content_snapshots
            WHERE task_id = 'task-a' AND deactivated_at IS NULL
            """
        ).fetchone()["count"]
        self.assertEqual(0, active_skill)
        self.assertEqual(0, active_content)

    def test_rejects_unpublished_unknown_and_mutated_skill_versions(self):
        unpublished = self.skill_command()
        unpublished["status"] = "draft"
        with self.assertRaisesRegex(ValueError, "skill_version_not_published"):
            self.repository.publish_skill_version(unpublished)

        unknown = self.skill_command()
        unknown["secretKey"] = "must-not-be-accepted"
        with self.assertRaisesRegex(ValueError, "skill_version_unknown_field"):
            self.repository.publish_skill_version(unknown)

        published = self.repository.publish_skill_version(self.skill_command())
        self.assertEqual("skill-hvac@1.0.0", published["versionId"])
        changed = self.skill_command()
        changed["name"] = "被篡改的名称"
        with self.assertRaisesRegex(ValueError, "skill_version_immutable_conflict"):
            self.repository.publish_skill_version(changed)

    def test_rejects_expired_assignment_cross_device_and_closed_task(self):
        published = self.repository.publish_skill_version(self.skill_command())
        self.repository.assign_skill(
            {
                "assignmentId": "assignment-expired",
                "organizationId": "org-huafang",
                "skillVersionId": published["versionId"],
                "userId": "user-field-a",
                "deviceId": "air3-a",
                "projectId": "project-a",
                "status": "active",
                "activeFrom": self.clock.value - 100,
                "expiresAt": self.clock.value - 1,
            }
        )
        with self.assertRaisesRegex(PermissionError, "skill_not_authorized"):
            self.repository.activate_task_skill(
                "org-huafang", "user-field-a", "air3-a", "task-local-a",
                published["versionId"]
            )

        with self.assertRaisesRegex(LookupError, "project_not_found"):
            self.repository.build_context(
                "org-huafang", "user-field-a", "air3-a",
                "project-local-b", "task-local-b", "跨设备", [], {}
            )

        self.connection.execute(
            "UPDATE maintenance_tasks SET status = 'closed' WHERE id = 'task-a'"
        )
        self.connection.commit()
        with self.assertRaisesRegex(ValueError, "task_not_active"):
            self.repository.build_context(
                "org-huafang", "user-field-a", "air3-a",
                "project-local-a", "task-local-a", "继续旧任务", [], {}
            )

    def test_enforces_memory_revision_and_project_instruction_versions(self):
        self.repository.update_project_memory(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            0, "第一版", ["事实一"], [], []
        )
        with self.assertRaisesRegex(ValueError, "project_memory_revision_conflict"):
            self.repository.update_project_memory(
                "org-huafang", "user-field-a", "air3-a", "project-local-a",
                0, "过期写入", [], [], []
            )

        first = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-a", 0, "active", {}, "先记录报警代码", [], "trace-a"
        )
        self.assertEqual(1, first["version"])
        second = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-a", 1, "disabled", {}, "先记录报警代码", [], "trace-b"
        )
        self.assertEqual(2, second["version"])
        with self.assertRaisesRegex(ValueError, "project_instruction_version_conflict"):
            self.repository.put_project_instruction(
                "org-huafang", "user-field-a", "air3-a", "project-local-a",
                "instruction-a", 1, "active", {}, "错误覆盖", [], "trace-c"
            )

        context = self.repository.build_context(
            "org-huafang", "user-field-a", "air3-a",
            "project-local-a", "task-local-a", "继续检查", [], {}
        )
        self.assertEqual((), context.instructions)

    def test_project_instruction_lifecycle_rejects_invalid_transitions_and_noop_versions(self):
        with self.assertRaisesRegex(
            ValueError, "project_instruction_transition_invalid"
        ):
            self.repository.put_project_instruction(
                "org-huafang", "user-field-a", "air3-a", "project-local-a",
                "instruction-lifecycle", 0, "disabled", {"containsAny": ["报警"]},
                "先记录报警代码", [], "trace-invalid-create"
            )

        created = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-lifecycle", 0, "active", {"containsAny": ["报警"]},
            "先记录报警代码", [], "trace-created"
        )
        self.assertEqual(
            {"instructionId": "instruction-lifecycle", "version": 1, "status": "active"},
            created,
        )

        with self.assertRaisesRegex(ValueError, "project_instruction_no_change"):
            self.repository.put_project_instruction(
                "org-huafang", "user-field-a", "air3-a", "project-local-a",
                "instruction-lifecycle", 1, "active", {"containsAny": ["报警"]},
                "先记录报警代码", [], "trace-noop"
            )

        edited = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-lifecycle", 1, "active", {"containsAny": ["报警"]},
            "先记录报警代码，再检查控制器供电", [], "trace-edited"
        )
        disabled = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-lifecycle", 2, "disabled", {"containsAny": ["报警"]},
            "先记录报警代码，再检查控制器供电", [], "trace-disabled"
        )
        edited_while_disabled = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-lifecycle", 3, "disabled", {"containsAny": ["报警"]},
            "先读取报警代码，再检查控制器供电", [], "trace-disabled-edit"
        )
        enabled = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-lifecycle", 4, "active", {"containsAny": ["报警"]},
            "先读取报警代码，再检查控制器供电", [], "trace-enabled"
        )
        deleted = self.repository.put_project_instruction(
            "org-huafang", "user-field-a", "air3-a", "project-local-a",
            "instruction-lifecycle", 5, "deleted", {"containsAny": ["报警"]},
            "先读取报警代码，再检查控制器供电", [], "trace-deleted"
        )

        self.assertEqual(2, edited["version"])
        self.assertEqual(3, disabled["version"])
        self.assertEqual(4, edited_while_disabled["version"])
        self.assertEqual(5, enabled["version"])
        self.assertEqual(6, deleted["version"])
        with self.assertRaisesRegex(
            ValueError, "project_instruction_transition_invalid"
        ):
            self.repository.put_project_instruction(
                "org-huafang", "user-field-a", "air3-a", "project-local-a",
                "instruction-lifecycle", 6, "active", {"containsAny": ["报警"]},
                "错误恢复已删除指令", [], "trace-resurrect"
            )

        detail = self.repository.project_detail(
            "org-huafang", "air3-a", "project-local-a"
        )
        latest = next(
            item for item in detail["projectInstructions"]
            if item["instructionId"] == "instruction-lifecycle"
        )
        self.assertEqual(6, latest["version"])
        self.assertEqual("deleted", latest["status"])

        context = self.repository.build_context(
            "org-huafang", "user-field-a", "air3-a",
            "project-local-a", "task-local-a", "控制器报警", [], {}
        )
        self.assertNotIn(
            "instruction-lifecycle",
            tuple(item.instruction_id for item in context.instructions),
        )


if __name__ == "__main__":
    unittest.main()
