import base64
import hashlib
import json
import unittest
import urllib.parse
import urllib.error
from unittest import mock

from voiceprint_proxy import (
    IFLYTEK_VOICEPRINT_SERVICE_ID,
    IFLYTEK_VOICEPRINT_URL,
    IflytekVoiceprintConfig,
    IflytekVoiceprintProvider,
    VoiceprintProviderUnavailable,
    validate_voiceprint_wav,
)


class FakeHttpResponse:
    def __init__(self, payload):
        self.payload = json.dumps(payload).encode("utf-8")
        self.closed = False

    def read(self, maximum=None):
        if maximum is None:
            return self.payload
        return self.payload[:maximum]

    def close(self):
        self.closed = True


class RecordingOpener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, request, timeout):
        self.calls.append((request, timeout))
        return self.responses.pop(0)


def provider_response(result_name, result):
    encoded = base64.b64encode(json.dumps(result).encode("utf-8")).decode("ascii")
    return {
        "header": {"code": 0, "message": "success", "sid": "provider-sid"},
        "payload": {result_name: {"text": encoded}},
    }


def wave_bytes(seconds=3, sample_rate=16000, channels=1, sample_width=2, amplitude=2000):
    import io
    import wave

    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(sample_width)
        wav.setframerate(sample_rate)
        if sample_width == 2:
            positive = int(amplitude).to_bytes(2, "little", signed=True)
            negative = int(-amplitude).to_bytes(2, "little", signed=True)
            frame = (positive + negative) * channels
            wav.writeframes(frame * (seconds * sample_rate // 2))
        else:
            wav.writeframes(b"\x80" * (seconds * sample_rate * channels * sample_width))
    return output.getvalue()


class IflytekVoiceprintProviderTest(unittest.TestCase):
    def config(self):
        return IflytekVoiceprintConfig(
            app_id="server-app-id",
            api_key="server-api-key",
            api_secret="server-api-secret",
            threshold=0.70,
        )

    def test_locks_provider_to_new_iflytek_voiceprint_contract(self):
        self.assertEqual("s1aa729d0", IFLYTEK_VOICEPRINT_SERVICE_ID)
        self.assertEqual(
            "https://api.xf-yun.com/v1/private/s1aa729d0",
            IFLYTEK_VOICEPRINT_URL,
        )
        with self.assertRaisesRegex(ValueError, "voiceprint_url_not_iflytek_new"):
            IflytekVoiceprintConfig(
                app_id="server-app-id",
                api_key="server-api-key",
                api_secret="server-api-secret",
                endpoint="https://api.xf-yun.com/v1/private/s782b4996",
            )

    def test_signs_the_exact_new_voiceprint_post_request(self):
        provider = IflytekVoiceprintProvider(
            self.config(),
            opener=RecordingOpener([]),
            clock=lambda: 1_619_144_147.0,
        )

        signed = provider.authenticated_url()

        parsed = urllib.parse.urlsplit(signed)
        query = urllib.parse.parse_qs(parsed.query)
        authorization = base64.b64decode(query["authorization"][0]).decode("utf-8")
        self.assertEqual("api.xf-yun.com", query["host"][0])
        self.assertIn("GMT", query["date"][0])
        self.assertIn('api_key="server-api-key"', authorization)
        self.assertIn('algorithm="hmac-sha256"', authorization)
        self.assertIn('headers="host date request-line"', authorization)
        self.assertIn('signature="', authorization)
        self.assertNotIn("server-api-secret", signed)

    def test_sends_1_to_1_request_and_returns_only_the_score(self):
        response = FakeHttpResponse(
            provider_response(
                "searchScoreFeaRes",
                {
                    "score": 0.83,
                    "featureId": "feature000000000000000000000001",
                    "featureInfo": "must-not-be-returned",
                },
            )
        )
        opener = RecordingOpener([response])
        provider = IflytekVoiceprintProvider(self.config(), opener=opener)
        audio = wave_bytes()

        score = provider.verify_feature(
            "group0000000000000000000000001",
            "feature000000000000000000000001",
            audio,
        )

        self.assertEqual(0.83, score)
        request, timeout = opener.calls[0]
        body = json.loads(request.data.decode("utf-8"))
        parameters = body["parameter"][IFLYTEK_VOICEPRINT_SERVICE_ID]
        resource = body["payload"]["resource"]
        self.assertEqual("searchScoreFea", parameters["func"])
        self.assertEqual("feature000000000000000000000001", parameters["dstFeatureId"])
        self.assertEqual("raw", resource["encoding"])
        self.assertEqual(16000, resource["sample_rate"])
        self.assertEqual(audio, base64.b64decode(resource["audio"]))
        self.assertEqual(1.25, timeout)
        self.assertTrue(response.closed)

    def test_retries_one_temporary_verification_failure_within_latency_budget(self):
        response = FakeHttpResponse(
            provider_response(
                "searchScoreFeaRes",
                {
                    "score": 0.91,
                    "featureId": "feature000000000000000000000001",
                },
            )
        )

        class TemporaryFailureOpener(RecordingOpener):
            def __call__(self, request, timeout):
                self.calls.append((request, timeout))
                if len(self.calls) == 1:
                    raise urllib.error.URLError("temporary")
                return response

        opener = TemporaryFailureOpener([])
        sleeps = []
        provider = IflytekVoiceprintProvider(
            self.config(), opener=opener, sleeper=sleeps.append
        )

        score = provider.verify_feature(
            "group0000000000000000000000001",
            "feature000000000000000000000001",
            wave_bytes(),
        )

        self.assertEqual(0.91, score)
        self.assertEqual(2, len(opener.calls))
        self.assertEqual([1.25, 1.25], [call[1] for call in opener.calls])
        self.assertEqual([0.25], sleeps)

    def test_default_network_opener_passes_timeout_as_a_keyword(self):
        response = FakeHttpResponse(
            provider_response("createGroupRes", {"groupId": "group-a"})
        )
        provider = IflytekVoiceprintProvider(self.config())

        with mock.patch(
            "voiceprint_proxy.urllib.request.urlopen", return_value=response
        ) as urlopen:
            provider.create_group("group0000000000000000000000001")

        request = urlopen.call_args.args[0]
        self.assertEqual("POST", request.method)
        self.assertEqual((), urlopen.call_args.args[1:])
        self.assertEqual(8, urlopen.call_args.kwargs["timeout"])

    def test_rejects_malformed_or_failed_provider_responses_without_disclosure(self):
        responses = [
            FakeHttpResponse(
                {"header": {"code": 23005, "message": "private vendor detail"}}
            ),
            FakeHttpResponse(
                provider_response(
                    "searchScoreFeaRes",
                    {"score": 0.9, "featureId": "different-feature"},
                )
            ),
        ]
        provider = IflytekVoiceprintProvider(
            self.config(), opener=RecordingOpener(responses)
        )

        for expected in ("voiceprint_provider_rejected", "voiceprint_response_invalid"):
            with self.assertRaisesRegex(VoiceprintProviderUnavailable, expected) as raised:
                provider.verify_feature(
                    "group0000000000000000000000001",
                    "feature000000000000000000000001",
                    wave_bytes(),
                )
            self.assertNotIn("private vendor detail", str(raised.exception))

    def test_validates_clear_16khz_mono_pcm_wav_without_retaining_audio(self):
        audio = wave_bytes(seconds=3)

        metadata = validate_voiceprint_wav(audio, minimum_seconds=3, maximum_seconds=10)

        self.assertEqual(16000, metadata.sample_rate)
        self.assertEqual(1, metadata.channels)
        self.assertEqual(16, metadata.bit_depth)
        self.assertEqual(3000, metadata.duration_ms)
        self.assertEqual(hashlib.sha256(audio).hexdigest(), metadata.sha256)

        for invalid in (
            b"not-wave",
            wave_bytes(sample_rate=8000),
            wave_bytes(channels=2),
            wave_bytes(sample_width=1),
            wave_bytes(seconds=2),
            wave_bytes(amplitude=0),
        ):
            with self.assertRaisesRegex(ValueError, "voiceprint_audio_invalid"):
                validate_voiceprint_wav(
                    invalid, minimum_seconds=3, maximum_seconds=10
                )


if __name__ == "__main__":
    unittest.main()
