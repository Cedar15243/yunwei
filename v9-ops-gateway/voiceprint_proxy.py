from __future__ import annotations

import base64
import hashlib
import hmac
import io
import json
import math
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import wave
from dataclasses import dataclass
from email.utils import formatdate
from typing import Callable, Optional


IFLYTEK_VOICEPRINT_SERVICE_ID = "s1aa729d0"
IFLYTEK_VOICEPRINT_URL = (
    "https://api.xf-yun.com/v1/private/" + IFLYTEK_VOICEPRINT_SERVICE_ID
)
MAX_PROVIDER_RESPONSE_BYTES = 1024 * 1024
MAX_VOICEPRINT_AUDIO_BYTES = 1024 * 1024


class VoiceprintProviderUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class VoiceprintAudioMetadata:
    sample_rate: int
    channels: int
    bit_depth: int
    duration_ms: int
    sha256: str


@dataclass(frozen=True)
class IflytekVoiceprintConfig:
    app_id: str
    api_key: str
    api_secret: str
    endpoint: str = IFLYTEK_VOICEPRINT_URL
    threshold: float = 0.70
    timeout_seconds: int = 8
    verification_timeout_seconds: float = 1.25

    def __post_init__(self) -> None:
        if self.endpoint != IFLYTEK_VOICEPRINT_URL:
            raise ValueError("voiceprint_url_not_iflytek_new")
        if not self.app_id.strip():
            raise ValueError("voiceprint_app_id_missing")
        if not self.api_key.strip():
            raise ValueError("voiceprint_api_key_missing")
        if not self.api_secret.strip():
            raise ValueError("voiceprint_api_secret_missing")
        if self.threshold < 0.6 or self.threshold > 0.95:
            raise ValueError("voiceprint_threshold_out_of_range")
        if self.timeout_seconds < 1 or self.timeout_seconds > 15:
            raise ValueError("voiceprint_timeout_out_of_range")
        if self.verification_timeout_seconds < 0.5 or self.verification_timeout_seconds > 1.25:
            raise ValueError("voiceprint_verification_timeout_out_of_range")


def validate_voiceprint_wav(
    content: bytes,
    minimum_seconds: int = 3,
    maximum_seconds: int = 10,
) -> VoiceprintAudioMetadata:
    if not isinstance(content, bytes) or not content or len(content) > MAX_VOICEPRINT_AUDIO_BYTES:
        raise ValueError("voiceprint_audio_invalid")
    try:
        with wave.open(io.BytesIO(content), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            frame_count = audio.getnframes()
            compression = audio.getcomptype()
            frames = audio.readframes(frame_count)
    except (EOFError, wave.Error):
        raise ValueError("voiceprint_audio_invalid") from None
    duration_seconds = frame_count / sample_rate if sample_rate else 0
    if (
        channels != 1
        or sample_width != 2
        or sample_rate != 16000
        or compression != "NONE"
        or duration_seconds < minimum_seconds
        or duration_seconds > maximum_seconds
    ):
        raise ValueError("voiceprint_audio_invalid")
    samples = [value[0] for value in struct.iter_unpack("<h", frames)]
    if not samples:
        raise ValueError("voiceprint_audio_invalid")
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
    if rms < 300:
        raise ValueError("voiceprint_audio_invalid")
    return VoiceprintAudioMetadata(
        sample_rate=sample_rate,
        channels=channels,
        bit_depth=sample_width * 8,
        duration_ms=round(duration_seconds * 1000),
        sha256=hashlib.sha256(content).hexdigest(),
    )


class IflytekVoiceprintProvider:
    def __init__(
        self,
        config: IflytekVoiceprintConfig,
        opener: Optional[Callable[[urllib.request.Request, float], object]] = None,
        clock: Optional[Callable[[], float]] = None,
        sleeper: Callable[[float], None] = time.sleep,
    ):
        self.config = config
        self.opener = opener or (
            lambda request, timeout: urllib.request.urlopen(request, timeout=timeout)
        )
        self.clock = clock or time.time
        self.sleeper = sleeper

    def authenticated_url(self) -> str:
        parsed = urllib.parse.urlsplit(self.config.endpoint)
        date = formatdate(self.clock(), usegmt=True)
        signature_origin = (
            f"host: {parsed.hostname}\n"
            f"date: {date}\n"
            f"POST {parsed.path} HTTP/1.1"
        )
        signature = base64.b64encode(
            hmac.new(
                self.config.api_secret.encode("utf-8"),
                signature_origin.encode("utf-8"),
                hashlib.sha256,
            ).digest()
        ).decode("ascii")
        authorization_origin = (
            f'api_key="{self.config.api_key}", algorithm="hmac-sha256", '
            f'headers="host date request-line", signature="{signature}"'
        )
        query = urllib.parse.urlencode(
            {
                "host": parsed.hostname,
                "date": date,
                "authorization": base64.b64encode(
                    authorization_origin.encode("utf-8")
                ).decode("ascii"),
            }
        )
        return self.config.endpoint + "?" + query

    def create_group(self, group_id: str) -> None:
        self._call(
            "createGroup",
            {
                "groupId": group_id,
                "groupName": "managed_voiceprint",
                "groupInfo": "dingdang_v9",
            },
        )

    def create_feature(self, group_id: str, feature_id: str, audio: bytes) -> None:
        self._call(
            "createFeature",
            {
                "groupId": group_id,
                "featureId": feature_id,
                "featureInfo": "managed_voiceprint",
            },
            audio,
        )

    def merge_feature(self, group_id: str, feature_id: str, audio: bytes) -> None:
        self._call(
            "updateFeature",
            {
                "groupId": group_id,
                "featureId": feature_id,
                "featureInfo": "managed_voiceprint",
                "cover": False,
            },
            audio,
        )

    def verify_feature(self, group_id: str, feature_id: str, audio: bytes) -> float:
        result = None
        for attempt in range(2):
            try:
                result = self._call(
                    "searchScoreFea",
                    {"groupId": group_id, "dstFeatureId": feature_id},
                    audio,
                    timeout_seconds=self.config.verification_timeout_seconds,
                )
                break
            except VoiceprintProviderUnavailable as error:
                if attempt == 0 and str(error) == "voiceprint_provider_unavailable":
                    self.sleeper(0.25)
                    continue
                raise
        if result is None:
            raise VoiceprintProviderUnavailable("voiceprint_provider_unavailable")
        score = result.get("score")
        if (
            not isinstance(score, (int, float))
            or isinstance(score, bool)
            or not -1 <= float(score) <= 1
            or result.get("featureId") != feature_id
        ):
            raise VoiceprintProviderUnavailable("voiceprint_response_invalid")
        return float(score)

    def delete_group(self, group_id: str) -> None:
        self._call("deleteGroup", {"groupId": group_id})

    def _call(
        self,
        operation: str,
        parameters: dict[str, object],
        audio: bytes = b"",
        timeout_seconds: Optional[float] = None,
    ) -> dict:
        response_name = operation + "Res"
        body: dict[str, object] = {
            "header": {"app_id": self.config.app_id, "status": 3},
            "parameter": {
                IFLYTEK_VOICEPRINT_SERVICE_ID: {
                    "func": operation,
                    **parameters,
                    response_name: {
                        "encoding": "utf8",
                        "compress": "raw",
                        "format": "json",
                    },
                }
            },
        }
        if audio:
            validate_voiceprint_wav(audio)
            body["payload"] = {
                "resource": {
                    "encoding": "raw",
                    "sample_rate": 16000,
                    "channels": 1,
                    "bit_depth": 16,
                    "status": 3,
                    "audio": base64.b64encode(audio).decode("ascii"),
                }
            }
        request = urllib.request.Request(
            self.authenticated_url(),
            data=json.dumps(body, separators=(",", ":")).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        response = None
        try:
            response = self.opener(
                request,
                self.config.timeout_seconds
                if timeout_seconds is None
                else timeout_seconds,
            )
            raw = response.read(MAX_PROVIDER_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            error.read()
            raise VoiceprintProviderUnavailable("voiceprint_provider_unavailable") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise VoiceprintProviderUnavailable("voiceprint_provider_unavailable") from None
        finally:
            if response is not None:
                response.close()
        if len(raw) > MAX_PROVIDER_RESPONSE_BYTES:
            raise VoiceprintProviderUnavailable("voiceprint_response_invalid")
        try:
            envelope = json.loads(raw.decode("utf-8"))
            header = envelope["header"]
            if header.get("code") != 0:
                raise VoiceprintProviderUnavailable("voiceprint_provider_rejected")
            encoded_result = envelope["payload"][response_name]["text"]
            result = json.loads(base64.b64decode(encoded_result, validate=True).decode("utf-8"))
        except VoiceprintProviderUnavailable:
            raise
        except (KeyError, TypeError, ValueError, UnicodeDecodeError):
            raise VoiceprintProviderUnavailable("voiceprint_response_invalid") from None
        if not isinstance(result, dict):
            raise VoiceprintProviderUnavailable("voiceprint_response_invalid")
        return result
