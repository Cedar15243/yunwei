package com.codex.air3nativecamera.task;

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

    public void setDiagnosis(String title, String text, int confidence) {
        diagnosisTitle = clean(title, "AI 诊断结果");
        diagnosisText = text == null ? "" : text.trim();
        this.confidence = Math.max(0, Math.min(100, confidence));
        facts.put("当前判断", diagnosisTitle);
        if (this.confidence > 0) {
            facts.put("异常概率", this.confidence + "%");
        }
        diagnosisPageIndex = 0;
        phase = Phase.DIAGNOSIS;
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
        repairSteps.clear();
        if (steps != null) {
            for (String step : steps) {
                String cleanStep = step == null ? "" : step.trim();
                if (cleanStep.length() > 0) {
                    repairSteps.add(cleanStep);
                }
            }
        }
        repairStepIndex = 0;
        phase = repairSteps.isEmpty() ? Phase.DIAGNOSIS : Phase.GUIDANCE;
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
            if (current.length() > 0 && current.length() + sentence.length() > limit) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (sentence.length() > limit) {
                for (int index = 0; index < sentence.length(); index += limit) {
                    int end = Math.min(sentence.length(), index + limit);
                    if (current.length() > 0) {
                        result.add(current.toString());
                        current.setLength(0);
                    }
                    result.add(sentence.substring(index, end));
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
