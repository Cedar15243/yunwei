export const PREVIOUS_STABLE_AI_MODEL = "qwen3-vl-plus";
export const PREVIOUS_STABLE_ASR_MODEL = "fun-asr-realtime";
export const IFLYTEK_VOICEPRINT_SERVICE_ID = "s1aa729d0";

export type V9ModelContractInput = {
  aiModel: string;
  asrModel: string;
  dashScopeAsrModel?: string;
};

export function assertV9ModelContract(input: V9ModelContractInput): void {
  if (input.aiModel !== PREVIOUS_STABLE_AI_MODEL) {
    throw new Error("model_contract_ai_model_invalid");
  }
  if (input.asrModel !== PREVIOUS_STABLE_ASR_MODEL) {
    throw new Error("model_contract_asr_model_invalid");
  }
  if (input.dashScopeAsrModel && input.dashScopeAsrModel !== PREVIOUS_STABLE_ASR_MODEL) {
    throw new Error("model_contract_dashscope_asr_model_invalid");
  }
}

export function modelContractConfigurationError(error: unknown): string | null {
  const code = error instanceof Error ? error.message : "";
  return code.startsWith("model_contract_") ? code : null;
}
