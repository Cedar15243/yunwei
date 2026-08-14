import base64
import hashlib
import hmac
import io
import json
import tempfile
import unittest
import wave

from voiceprint_lifecycle import (
    VOICEPRINT_CONSENT_VERSION,
    VoiceprintLifecycleConfig,
    VoiceprintLifecycleService,
    VoiceprintStore,
)
from voiceprint_proxy import VoiceprintProviderUnavailable


class MutableClock:
    def __init__(self, now=1_800_000_000.0):
        self.now = now

    def __call__(self):
        return self.now


def tone_wav(seed, seconds=3):
    output = io.BytesIO()
    frames = bytearray()
    for index in range(seconds * 16000):
        sample = 3000 if ((index + seed) // (24 + seed)) % 2 == 0 else -3000
        frames.extend(int(sample).to_bytes(2, "little", signed=True))
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(bytes(frames))
    return output.getvalue()


def encoded(audio):
    return base64.b64encode(audio).decode("ascii")


class FakeVoiceprintProvider:
    def __init__(self, scores=None):
        self.scores = list(scores or [0.88])
        self.calls = []
        self.failure = None

    def _record(self, operation, group_id, feature_id="", audio=b""):
        self.calls.append((operation, group_id, feature_id, len(audio)))
        if self.failure:
            raise VoiceprintProviderUnavailable("private-provider-detail")

    def create_group(self, group_id):
        self._record("create_group", group_id)

    def create_feature(self, group_id, feature_id, audio):
        self._record("create_feature", group_id, feature_id, audio)

    def merge_feature(self, group_id, feature_id, audio):
        self._record("merge_feature", group_id, feature_id, audio)

    def verify_feature(self, group_id, feature_id, audio):
        self._record("verify_feature", group_id, feature_id, audio)
        return self.scores.pop(0)

    def delete_group(self, group_id):
        self._record("delete_group", group_id)


class VoiceprintLifecycleServiceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock = MutableClock()
        self.store = VoiceprintStore(
            f"{self.temp.name}/voiceprint.db", clock=self.clock
        )
        self.provider = FakeVoiceprintProvider()
        self.admin_token = "root-admin-token"
        self.service = VoiceprintLifecycleService(
            VoiceprintLifecycleConfig(
                organization_id="org-huafang",
                user_id="user-field-engineer",
                device_id="air3-YM00FCF3NW0031",
                admin_token_hash=hashlib.sha256(self.admin_token.encode()).hexdigest(),
                replay_hmac_key="server-only-replay-key",
            ),
            self.store,
            self.provider,
            clock=self.clock,
        )

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def consent(self, key="consent-1"):
        return self.service.consent(
            "air3-YM00FCF3NW0031",
            {
                "consentAccepted": True,
                "consentVersion": VOICEPRINT_CONSENT_VERSION,
                "idempotencyKey": key,
            },
        )

    def enroll_three(self):
        self.assertEqual(201, self.consent().status)
        responses = []
        for sample_index in range(1, 4):
            responses.append(
                self.service.enroll_sample(
                    "air3-YM00FCF3NW0031",
                    {
                        "sampleIndex": sample_index,
                        "audioBase64": encoded(tone_wav(sample_index)),
                        "idempotencyKey": f"sample-{sample_index}",
                    },
                )
            )
        return responses

    def test_requires_current_explicit_consent_and_binds_one_profile_to_identity(self):
        rejected = self.service.consent(
            "air3-YM00FCF3NW0031",
            {
                "consentAccepted": False,
                "consentVersion": VOICEPRINT_CONSENT_VERSION,
                "idempotencyKey": "declined",
            },
        )
        wrong_device = self.service.consent(
            "other-device",
            {
                "consentAccepted": True,
                "consentVersion": VOICEPRINT_CONSENT_VERSION,
                "idempotencyKey": "wrong-device",
            },
        )
        accepted = self.consent()
        duplicate = self.consent()

        self.assertEqual(400, rejected.status)
        self.assertEqual("voiceprint_consent_required", rejected.body["error"])
        self.assertEqual(403, wrong_device.status)
        self.assertEqual(201, accepted.status)
        self.assertEqual(200, duplicate.status)
        self.assertTrue(duplicate.body["duplicate"])
        profile = self.store.current_profile("air3-YM00FCF3NW0031")
        self.assertEqual("org-huafang", profile["organization_id"])
        self.assertEqual("user-field-engineer", profile["user_id"])
        self.assertEqual("enrolling", profile["status"])
        self.assertEqual(VOICEPRINT_CONSENT_VERSION, profile["consent_version"])

    def test_processes_three_samples_without_persisting_audio_then_requires_1_to_1(self):
        responses = self.enroll_three()

        self.assertEqual([201, 201, 201], [item.status for item in responses])
        self.assertEqual("pending_verification", responses[-1].body["status"])
        self.assertEqual(
            ["create_group", "create_feature", "merge_feature", "merge_feature"],
            [call[0] for call in self.provider.calls],
        )

        verification_audio = tone_wav(8)
        verified = self.service.verify(
            "air3-YM00FCF3NW0031",
            {
                "audioBase64": encoded(verification_audio),
                "idempotencyKey": "verify-1",
            },
        )

        self.assertEqual(200, verified.status)
        self.assertTrue(verified.body["verified"])
        self.assertEqual("active", verified.body["status"])
        with open(f"{self.temp.name}/voiceprint.db", "rb") as database_file:
            database_bytes = database_file.read()
        self.assertNotIn(verification_audio, database_bytes)
        self.assertNotIn(encoded(verification_audio).encode(), database_bytes)
        for sample_index in range(1, 4):
            self.assertNotIn(tone_wav(sample_index), database_bytes)
            self.assertNotIn(encoded(tone_wav(sample_index)).encode(), database_bytes)

    def test_rejects_exact_audio_replay_before_calling_provider(self):
        self.enroll_three()
        audio = tone_wav(8)
        first = self.service.verify(
            "air3-YM00FCF3NW0031",
            {"audioBase64": encoded(audio), "idempotencyKey": "verify-1"},
        )
        call_count = len(self.provider.calls)

        replay = self.service.verify(
            "air3-YM00FCF3NW0031",
            {"audioBase64": encoded(audio), "idempotencyKey": "verify-replay"},
        )

        self.assertEqual(200, first.status)
        self.assertEqual(409, replay.status)
        self.assertEqual("voiceprint_replay_detected", replay.body["error"])
        self.assertEqual(call_count, len(self.provider.calls))

    def test_completed_sample_and_verification_are_idempotent_before_state_or_replay_checks(self):
        self.assertEqual(201, self.consent().status)
        sample_payload = {
            "sampleIndex": 1,
            "audioBase64": encoded(tone_wav(1)),
            "idempotencyKey": "sample-1",
        }
        first_sample = self.service.enroll_sample(
            "air3-YM00FCF3NW0031", sample_payload
        )
        sample_call_count = len(self.provider.calls)
        duplicate_sample = self.service.enroll_sample(
            "air3-YM00FCF3NW0031", sample_payload
        )
        duplicate_sample_call_count = len(self.provider.calls)
        for sample_index in range(2, 4):
            self.service.enroll_sample(
                "air3-YM00FCF3NW0031",
                {
                    "sampleIndex": sample_index,
                    "audioBase64": encoded(tone_wav(sample_index)),
                    "idempotencyKey": f"sample-{sample_index}",
                },
            )
        verify_payload = {
            "audioBase64": encoded(tone_wav(8)),
            "idempotencyKey": "verify-1",
        }
        first_verify = self.service.verify(
            "air3-YM00FCF3NW0031", verify_payload
        )
        verify_call_count = len(self.provider.calls)
        duplicate_verify = self.service.verify(
            "air3-YM00FCF3NW0031", verify_payload
        )

        self.assertEqual(201, first_sample.status)
        self.assertEqual(200, duplicate_sample.status)
        self.assertTrue(duplicate_sample.body["duplicate"])
        self.assertEqual(sample_call_count, duplicate_sample_call_count)
        self.assertEqual(200, first_verify.status)
        self.assertEqual(200, duplicate_verify.status)
        self.assertTrue(duplicate_verify.body["duplicate"])
        self.assertEqual(verify_call_count, len(self.provider.calls))

    def test_locks_after_three_failed_verifications_and_does_not_fallback(self):
        self.provider.scores = [0.2, 0.3, 0.4]
        self.enroll_three()

        responses = []
        for attempt in range(1, 4):
            responses.append(
                self.service.verify(
                    "air3-YM00FCF3NW0031",
                    {
                        "audioBase64": encoded(tone_wav(10 + attempt)),
                        "idempotencyKey": f"failed-verify-{attempt}",
                    },
                )
            )
        locked = self.service.verify(
            "air3-YM00FCF3NW0031",
            {
                "audioBase64": encoded(tone_wav(20)),
                "idempotencyKey": "locked-attempt",
            },
        )

        self.assertEqual([False, False, False], [item.body["verified"] for item in responses])
        self.assertEqual("locked", responses[-1].body["status"])
        self.assertEqual(423, locked.status)
        self.assertEqual("voiceprint_locked", locked.body["error"])
        self.assertNotIn("fallback", json.dumps(locked.body))

    def test_reenrollment_deletes_old_template_and_starts_a_new_generation(self):
        self.enroll_three()
        old_profile = self.store.current_profile("air3-YM00FCF3NW0031")

        response = self.service.reenroll(
            "air3-YM00FCF3NW0031",
            {
                "consentAccepted": True,
                "consentVersion": VOICEPRINT_CONSENT_VERSION,
                "confirmation": "重新录入我的声纹",
                "idempotencyKey": "reenroll-1",
            },
        )

        new_profile = self.store.current_profile("air3-YM00FCF3NW0031")
        self.assertEqual(200, response.status)
        self.assertEqual("enrolling", response.body["status"])
        self.assertEqual(0, new_profile["enrollment_samples"])
        self.assertNotEqual(old_profile["provider_group_id"], new_profile["provider_group_id"])
        self.assertIn("delete_group", [call[0] for call in self.provider.calls])

    def test_device_delete_and_admin_revoke_remove_provider_template_and_audit(self):
        self.enroll_three()
        deleted = self.service.delete(
            "air3-YM00FCF3NW0031",
            {"confirmation": "删除我的声纹", "idempotencyKey": "delete-1"},
        )

        self.assertEqual(200, deleted.status)
        self.assertEqual("deleted", deleted.body["status"])
        profile = self.store.current_profile("air3-YM00FCF3NW0031")
        self.assertIsNone(profile["provider_group_id"])
        self.assertIsNone(profile["provider_feature_id"])

        self.consent("consent-2")
        revoked = self.service.admin_revoke(
            f"Bearer {self.admin_token}",
            self.store.current_profile("air3-YM00FCF3NW0031")["profile_id"],
            {
                "confirmation": "REVOKE VOICEPRINT",
                "reason": "设备归还",
                "idempotencyKey": "revoke-1",
            },
        )
        unauthorized = self.service.admin_revoke(
            "Bearer wrong",
            self.store.current_profile("air3-YM00FCF3NW0031")["profile_id"],
            {
                "confirmation": "REVOKE VOICEPRINT",
                "reason": "设备归还",
                "idempotencyKey": "revoke-2",
            },
        )

        self.assertEqual(200, revoked.status)
        self.assertEqual("revoked", revoked.body["status"])
        self.assertEqual(401, unauthorized.status)
        self.assertTrue(self.store.verify_audit_chain())
        event_types = self.store.audit_event_types()
        self.assertIn("voiceprint_deleted", event_types)
        self.assertIn("voiceprint_revoked", event_types)

    def test_admin_list_is_authorized_organization_scoped_and_redacted(self):
        self.enroll_three()

        listed = self.service.admin_list(f"Bearer {self.admin_token}")
        unauthorized = self.service.admin_list("Bearer wrong")

        self.assertEqual(200, listed.status)
        self.assertEqual(401, unauthorized.status)
        self.assertTrue(listed.body["auditChainValid"])
        self.assertEqual(1, len(listed.body["items"]))
        profile = listed.body["items"][0]
        self.assertEqual("org-huafang", profile["organizationId"])
        self.assertEqual("user-field-engineer", profile["userId"])
        self.assertEqual("air3-YM00FCF3NW0031", profile["deviceId"])
        self.assertEqual("pending_verification", profile["status"])
        self.assertEqual(3, profile["sampleCount"])
        serialized = json.dumps(listed.body)
        self.assertNotIn("providerGroup", serialized)
        self.assertNotIn("providerFeature", serialized)
        self.assertNotIn("provider_group", serialized)
        self.assertNotIn("provider_feature", serialized)

    def test_admin_token_rotation_accepts_current_and_previous_only(self):
        previous_token = "previous-root-admin-token"
        rotating_service = VoiceprintLifecycleService(
            VoiceprintLifecycleConfig(
                organization_id="org-huafang",
                user_id="user-field-engineer",
                device_id="air3-YM00FCF3NW0031",
                admin_token_hash=hashlib.sha256(self.admin_token.encode()).hexdigest(),
                admin_previous_token_hash=hashlib.sha256(
                    previous_token.encode()
                ).hexdigest(),
                replay_hmac_key="server-only-replay-key",
            ),
            self.store,
            self.provider,
            clock=self.clock,
        )

        current = rotating_service.admin_list(f"Bearer {self.admin_token}")
        previous = rotating_service.admin_list(f"Bearer {previous_token}")
        rejected = rotating_service.admin_list("Bearer unrelated-token")

        self.assertEqual(200, current.status)
        self.assertEqual(200, previous.status)
        self.assertEqual(401, rejected.status)

    def test_provider_failure_is_generic_and_profile_remains_recoverable(self):
        self.assertEqual(201, self.consent().status)
        self.provider.failure = True

        failed = self.service.enroll_sample(
            "air3-YM00FCF3NW0031",
            {
                "sampleIndex": 1,
                "audioBase64": encoded(tone_wav(1)),
                "idempotencyKey": "failed-sample",
            },
        )

        self.assertEqual(503, failed.status)
        self.assertEqual("voiceprint_unavailable", failed.body["error"])
        self.assertNotIn("private-provider-detail", json.dumps(failed.body))
        profile = self.store.current_profile("air3-YM00FCF3NW0031")
        self.assertEqual("enrolling", profile["status"])
        self.assertEqual(0, profile["enrollment_samples"])

    def test_replay_digest_is_server_keyed_not_plain_audio_sha256(self):
        self.enroll_three()
        audio = tone_wav(8)
        self.service.verify(
            "air3-YM00FCF3NW0031",
            {"audioBase64": encoded(audio), "idempotencyKey": "verify-1"},
        )
        stored = self.store.replay_digests()

        self.assertNotIn(hashlib.sha256(audio).hexdigest(), stored)
        expected = hmac.new(
            b"server-only-replay-key", audio, hashlib.sha256
        ).hexdigest()
        self.assertIn(expected, stored)

    def test_audit_chain_uses_one_timestamp_even_when_clock_moves_between_calls(self):
        class TickingClock:
            def __init__(self):
                self.value = 1_800_000_000.0

            def __call__(self):
                self.value += 0.001
                return self.value

        temp = tempfile.TemporaryDirectory()
        store = VoiceprintStore(f"{temp.name}/voiceprint.db", clock=TickingClock())
        try:
            profile = store.ensure_profile(
                "org-huafang",
                "user-field-engineer",
                "air3-YM00FCF3NW0031",
                "g" + "a" * 30,
                "f" + "b" * 30,
            )
            store.append_audit(
                profile["profile_id"], "voiceprint_test_event", "success"
            )

            self.assertTrue(store.verify_audit_chain())
        finally:
            store.close()
            temp.cleanup()


if __name__ == "__main__":
    unittest.main()
