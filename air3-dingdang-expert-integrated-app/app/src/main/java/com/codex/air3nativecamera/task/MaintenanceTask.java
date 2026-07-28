package com.codex.air3nativecamera.task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local, recoverable context for one field maintenance case. It deliberately has no Android
 * dependency so its evidence, repair flow, and prompt memory remain easy to test.
 */
public final class MaintenanceTask {
    public enum Phase {
        DIAGNOSIS,
        GUIDANCE,
        COMPLETED
    }

    public static final class Snapshot {
        private final Phase phase;
        private final int repairStepIndex;
        private final int diagnosisPageIndex;

        private Snapshot(Phase phase, int repairStepIndex, int diagnosisPageIndex) {
            this.phase = phase;
            this.repairStepIndex = repairStepIndex;
            this.diagnosisPageIndex = diagnosisPageIndex;
        }
    }

    private static final class Evidence {
        private final String label;
        private final String reference;

        private Evidence(String label, String reference) {
            this.label = label;
            this.reference = reference;
        }
    }

    private static final class Turn {
        private final String speaker;
        private final String text;

        private Turn(String speaker, String text) {
            this.speaker = speaker;
            this.text = text;
        }
    }

    private final String initialProblem;
    private final Map<String, String> facts = new LinkedHashMap<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Turn> turns = new ArrayList<>();
    private final List<String> repairSteps = new ArrayList<>();
    private String diagnosisTitle = "等待诊断";
    private String diagnosisText = "";
    private int confidence;
    private int repairStepIndex;
    private int diagnosisPageIndex;
    private int conversationPageIndex = Integer.MAX_VALUE;
    private Phase phase = Phase.DIAGNOSIS;

    private MaintenanceTask(String initialProblem) {
        this.initialProblem = clean(initialProblem, "请描述设备异常");
    }

    public static MaintenanceTask start(String initialProblem) {
        return new MaintenanceTask(initialProblem);
    }

    public void putFact(String key, String value) {
        facts.put(clean(key, "现场信息"), clean(value, "待确认"));
    }

    public void addEvidence(String label, String reference) {
        String safeLabel = clean(label, "现场照片");
        evidence.add(new Evidence(safeLabel, clean(reference, "local-photo")));
        addTurn("现场证据", safeLabel + "已添加");
    }

    public void addTurn(String speaker, String text) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.length() > 0) {
            String cleanSpeaker = clean(speaker, "现场人员");
            turns.add(new Turn(cleanSpeaker, cleanText));
            if ("AI".equals(cleanSpeaker)) {
                conversationPageIndex = 0;
            }
        }
    }

    public int aiTurnCount() {
        int count = 0;
        for (Turn turn : turns) {
            if ("AI".equals(turn.speaker)) {
                count++;
            }
        }
        return count;
    }

    public void setDiagnosis(String title, String text, int confidence) {
        diagnosisTitle = clean(title, "AI 诊断结果");
        diagnosisText = text == null ? "" : text.trim();
        this.confidence = Math.max(0, Math.min(100, confidence));
        facts.put("当前判断", diagnosisTitle);
        if (this.confidence > 0) {
            facts.put("异常概率", this.confidence + "%");
        }
        diagnosisPageIndex = 0;
        if (phase != Phase.COMPLETED) {
            phase = repairSteps.isEmpty() ? Phase.DIAGNOSIS : Phase.GUIDANCE;
        }
    }

    public String diagnosisTitle() {
        return diagnosisTitle;
    }

    public String initialProblem() {
        return initialProblem;
    }

    public Map<String, String> facts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    public List<String> evidenceLabels() {
        ArrayList<String> labels = new ArrayList<>();
        for (Evidence item : evidence) {
            labels.add(item.label);
        }
        return Collections.unmodifiableList(labels);
    }

    public int confidence() {
        return confidence;
    }

    public void replaceRepairSteps(String[] steps) {
        Phase previousPhase = phase;
        int previousStepIndex = repairStepIndex;
        repairSteps.clear();
        if (steps != null) {
            for (String step : steps) {
                String cleanStep = step == null ? "" : step.trim();
                if (cleanStep.length() > 0) {
                    repairSteps.add(cleanStep);
                }
            }
        }
        if (repairSteps.isEmpty()) {
            repairStepIndex = 0;
            phase = previousPhase == Phase.COMPLETED ? Phase.COMPLETED : Phase.DIAGNOSIS;
            return;
        }
        repairStepIndex = previousPhase == Phase.GUIDANCE || previousPhase == Phase.COMPLETED
                ? Math.max(0, Math.min(previousStepIndex, repairSteps.size() - 1))
                : 0;
        phase = previousPhase == Phase.COMPLETED ? Phase.COMPLETED : Phase.GUIDANCE;
    }

    public int responsePageCount(int maxCharacters) {
        return pages(maxCharacters).size();
    }

    public String responsePage(int pageIndex, int maxCharacters) {
        List<String> pages = pages(maxCharacters);
        if (pages.isEmpty()) {
            return "暂无可展示的诊断内容。";
        }
        int safeIndex = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        return pages.get(safeIndex);
    }

    public int diagnosisPageIndex() {
        return diagnosisPageIndex;
    }

    public boolean nextDiagnosisPage(int maxCharacters) {
        int last = Math.max(0, responsePageCount(maxCharacters) - 1);
        if (diagnosisPageIndex >= last) {
            return false;
        }
        diagnosisPageIndex++;
        return true;
    }

    public boolean previousDiagnosisPage() {
        if (diagnosisPageIndex <= 0) {
            return false;
        }
        diagnosisPageIndex--;
        return true;
    }

    public int conversationPageCount(int maxCharacters) {
        return conversationPages(maxCharacters).size();
    }

    public int conversationPageIndex(int maxCharacters) {
        List<String> pages = conversationPages(maxCharacters);
        if (pages.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(conversationPageIndex, pages.size() - 1));
    }

    public String conversationPage(int pageIndex, int maxCharacters) {
        List<String> pages = conversationPages(maxCharacters);
        if (pages.isEmpty()) {
            return "等待现场输入。";
        }
        int safeIndex = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        return pages.get(safeIndex);
    }

    public boolean nextConversationPage(int maxCharacters) {
        int current = conversationPageIndex(maxCharacters);
        int last = Math.max(0, conversationPageCount(maxCharacters) - 1);
        if (current >= last) {
            return false;
        }
        conversationPageIndex = current + 1;
        return true;
    }

    public boolean previousConversationPage(int maxCharacters) {
        int current = conversationPageIndex(maxCharacters);
        if (current <= 0) {
            return false;
        }
        conversationPageIndex = current - 1;
        return true;
    }

    public int repairStepCount() {
        return repairSteps.size();
    }

    public int currentRepairStepNumber() {
        return repairSteps.isEmpty() ? 0 : repairStepIndex + 1;
    }

    public String currentRepairStep() {
        return repairSteps.isEmpty() ? "等待 AI 生成维修步骤" : repairSteps.get(repairStepIndex);
    }

    public boolean advanceRepairStep() {
        if (repairSteps.isEmpty()) {
            return false;
        }
        if (repairStepIndex + 1 >= repairSteps.size()) {
            phase = Phase.COMPLETED;
            return false;
        }
        repairStepIndex++;
        phase = Phase.GUIDANCE;
        return true;
    }

    public void complete() {
        phase = Phase.COMPLETED;
    }

    public Phase phase() {
        return phase;
    }

    public Snapshot snapshot() {
        return new Snapshot(phase, repairStepIndex, diagnosisPageIndex);
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        phase = snapshot.phase;
        repairStepIndex = repairSteps.isEmpty() ? 0
                : Math.max(0, Math.min(snapshot.repairStepIndex, repairSteps.size() - 1));
        diagnosisPageIndex = Math.max(0, snapshot.diagnosisPageIndex);
    }

    public String buildPromptMemory(int recentTurnLimit) {
        StringBuilder result = new StringBuilder();
        result.append("维修任务：").append(initialProblem).append('\n');
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            result.append(fact.getKey()).append('：').append(fact.getValue()).append('\n');
        }
        if (!evidence.isEmpty()) {
            result.append("现场证据：");
            for (int i = 0; i < evidence.size(); i++) {
                if (i > 0) {
                    result.append('、');
                }
                result.append(evidence.get(i).label);
            }
            result.append('\n');
        }
        if (!repairSteps.isEmpty()) {
            result.append("当前维修步骤：").append(currentRepairStep()).append('\n');
        }
        int start = Math.max(0, turns.size() - Math.max(0, recentTurnLimit));
        if (start > 0) {
            result.append("较早对话摘要：");
            int remaining = 900;
            for (int i = 0; i < start && remaining > 0; i++) {
                Turn turn = turns.get(i);
                String item = turn.speaker + "：" + turn.text + "；";
                if (item.length() > remaining) {
                    result.append(item, 0, remaining);
                    remaining = 0;
                } else {
                    result.append(item);
                    remaining -= item.length();
                }
            }
            result.append('\n');
        }
        for (int i = start; i < turns.size(); i++) {
            Turn turn = turns.get(i);
            result.append(turn.speaker).append('：').append(turn.text).append('\n');
        }
        return result.toString().trim();
    }

    public List<String> evidenceReferences() {
        ArrayList<String> references = new ArrayList<>();
        for (Evidence item : evidence) {
            references.add(item.reference);
        }
        return Collections.unmodifiableList(references);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray factArray = new JSONArray();
        JSONArray evidenceArray = new JSONArray();
        JSONArray turnArray = new JSONArray();
        JSONArray stepArray = new JSONArray();
        try {
            for (Map.Entry<String, String> fact : facts.entrySet()) {
                factArray.put(new JSONObject().put("key", fact.getKey()).put("value", fact.getValue()));
            }
            for (Evidence item : evidence) {
                evidenceArray.put(new JSONObject()
                        .put("label", item.label)
                        .put("reference", item.reference));
            }
            for (Turn item : turns) {
                turnArray.put(new JSONObject().put("speaker", item.speaker).put("text", item.text));
            }
            for (String step : repairSteps) {
                stepArray.put(step);
            }
            json.put("initial_problem", initialProblem);
            json.put("facts", factArray);
            json.put("evidence", evidenceArray);
            json.put("turns", turnArray);
            json.put("diagnosis_title", diagnosisTitle);
            json.put("diagnosis_text", diagnosisText);
            json.put("confidence", confidence);
            json.put("repair_steps", stepArray);
            json.put("repair_step_index", repairStepIndex);
            json.put("diagnosis_page_index", diagnosisPageIndex);
            json.put("conversation_page_index", conversationPageIndex);
            json.put("phase", phase.name());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize maintenance task", exception);
        }
        return json;
    }

    public static MaintenanceTask fromJson(JSONObject json) {
        MaintenanceTask task = start(json == null ? "" : json.optString("initial_problem", ""));
        if (json == null) {
            return task;
        }
        JSONArray factArray = json.optJSONArray("facts");
        for (int i = 0; factArray != null && i < factArray.length(); i++) {
            JSONObject item = factArray.optJSONObject(i);
            if (item != null) {
                task.putFact(item.optString("key", ""), item.optString("value", ""));
            }
        }
        JSONArray evidenceArray = json.optJSONArray("evidence");
        for (int i = 0; evidenceArray != null && i < evidenceArray.length(); i++) {
            JSONObject item = evidenceArray.optJSONObject(i);
            if (item != null) {
                task.evidence.add(new Evidence(
                        clean(item.optString("label", ""), "现场照片"),
                        clean(item.optString("reference", ""), "local-photo")));
            }
        }
        JSONArray turnArray = json.optJSONArray("turns");
        for (int i = 0; turnArray != null && i < turnArray.length(); i++) {
            JSONObject item = turnArray.optJSONObject(i);
            if (item != null) {
                task.addTurn(item.optString("speaker", ""), item.optString("text", ""));
            }
        }
        task.diagnosisTitle = clean(json.optString("diagnosis_title", ""), "等待诊断");
        task.diagnosisText = json.optString("diagnosis_text", "").trim();
        task.confidence = Math.max(0, Math.min(100, json.optInt("confidence", 0)));
        JSONArray stepArray = json.optJSONArray("repair_steps");
        for (int i = 0; stepArray != null && i < stepArray.length(); i++) {
            String step = stepArray.optString(i, "").trim();
            if (step.length() > 0) {
                task.repairSteps.add(step);
            }
        }
        task.repairStepIndex = task.repairSteps.isEmpty() ? 0
                : Math.max(0, Math.min(json.optInt("repair_step_index", 0), task.repairSteps.size() - 1));
        task.diagnosisPageIndex = Math.max(0, json.optInt("diagnosis_page_index", 0));
        task.conversationPageIndex = Math.max(0, json.optInt("conversation_page_index", 0));
        try {
            task.phase = Phase.valueOf(json.optString("phase", Phase.DIAGNOSIS.name()));
        } catch (IllegalArgumentException ignored) {
            task.phase = Phase.DIAGNOSIS;
        }
        if (task.phase == Phase.DIAGNOSIS && !task.repairSteps.isEmpty()) {
            task.phase = Phase.GUIDANCE;
        }
        return task;
    }

    private List<String> conversationPages(int maxCharacters) {
        String latestAnswer = "";
        for (int index = turns.size() - 1; index >= 0; index--) {
            Turn turn = turns.get(index);
            if ("AI".equals(turn.speaker)) {
                latestAnswer = turn.text;
                break;
            }
        }
        if (latestAnswer.length() == 0 && !turns.isEmpty()) {
            latestAnswer = turns.get(turns.size() - 1).text;
        }
        return paginate(latestAnswer, maxCharacters, "等待 AI 回复。");
    }

    private List<String> pages(int maxCharacters) {
        return paginate(diagnosisText, maxCharacters, "暂无可展示的诊断内容。");
    }

    private List<String> paginate(String text, int maxCharacters, String fallback) {
        int limit = Math.max(1, maxCharacters);
        String source = text == null || text.length() == 0 ? fallback : text;
        ArrayList<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : splitSentences(source)) {
            int sentenceBudget = displayBudget(sentence);
            if (current.length() > 0 && displayBudget(current.toString()) + sentenceBudget > limit) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (sentenceBudget > limit) {
                int chunkStart = 0;
                int chunkBudget = 0;
                for (int index = 0; index < sentence.length(); index++) {
                    int characterBudget = sentence.charAt(index) == '\n' ? 25 : 1;
                    if (chunkBudget > 0 && chunkBudget + characterBudget > limit) {
                        if (current.length() > 0) {
                            result.add(current.toString());
                            current.setLength(0);
                        }
                        result.add(sentence.substring(chunkStart, index));
                        chunkStart = index;
                        chunkBudget = 0;
                    }
                    chunkBudget += characterBudget;
                }
                if (chunkStart < sentence.length()) {
                    if (current.length() > 0) {
                        result.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(sentence.substring(chunkStart));
                }
            } else {
                current.append(sentence);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static int displayBudget(String text) {
        int budget = 0;
        for (int index = 0; index < text.length(); index++) {
            budget += text.charAt(index) == '\n' ? 25 : 1;
        }
        return budget;
    }

    private static List<String> splitSentences(String text) {
        ArrayList<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            current.append(character);
            if (character == '。' || character == '！' || character == '？' || character == '\n') {
                result.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().length() == 0) {
            return fallback;
        }
        return value.trim();
    }
}
