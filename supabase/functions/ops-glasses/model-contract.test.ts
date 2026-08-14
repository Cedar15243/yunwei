import { assertEquals, assertThrows } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  assertV9ModelContract,
  IFLYTEK_VOICEPRINT_SERVICE_ID,
  modelContractConfigurationError,
  PREVIOUS_STABLE_AI_MODEL,
  PREVIOUS_STABLE_ASR_MODEL,
} from "./model-contract.ts";

Deno.test("V9 model contract accepts previous stable models and new iFlytek voiceprint id", () => {
  assertV9ModelContract({
    aiModel: PREVIOUS_STABLE_AI_MODEL,
    asrModel: PREVIOUS_STABLE_ASR_MODEL,
    dashScopeAsrModel: PREVIOUS_STABLE_ASR_MODEL,
  });
  assertEquals(IFLYTEK_VOICEPRINT_SERVICE_ID, "s1aa729d0");
});

Deno.test("V9 model contract rejects a changed AI model", () => {
  const error = assertThrows(() => assertV9ModelContract({
    aiModel: "qwen-max",
    asrModel: PREVIOUS_STABLE_ASR_MODEL,
  }));
  assertEquals(modelContractConfigurationError(error), "model_contract_ai_model_invalid");
});

Deno.test("V9 model contract rejects changed ASR providers", () => {
  const error = assertThrows(() => assertV9ModelContract({
    aiModel: PREVIOUS_STABLE_AI_MODEL,
    asrModel: "new-asr-model",
    dashScopeAsrModel: PREVIOUS_STABLE_ASR_MODEL,
  }));
  assertEquals(modelContractConfigurationError(error), "model_contract_asr_model_invalid");

  const dashScopeError = assertThrows(() => assertV9ModelContract({
    aiModel: PREVIOUS_STABLE_AI_MODEL,
    asrModel: PREVIOUS_STABLE_ASR_MODEL,
    dashScopeAsrModel: "new-asr-model",
  }));
  assertEquals(
    modelContractConfigurationError(dashScopeError),
    "model_contract_dashscope_asr_model_invalid",
  );
});
