package com.codex.air3nativecamera.text;

import java.util.ArrayList;
import java.util.List;

/** Converts common AI Markdown into stable plain text for the glasses HUD. */
public final class HudTextNormalizer {
    private HudTextNormalizer() {}

    public static String normalize(String markdown) {
        if (markdown == null || markdown.length() == 0) {
            return "";
        }
        String source = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = source.split("\n", -1);
        List<String> normalizedLines = new ArrayList<>();
        boolean fencedCode = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (isFence(trimmed)) {
                fencedCode = !fencedCode;
                continue;
            }
            String normalized = fencedCode ? trimTrailingWhitespace(line) : normalizeLine(line);
            if (normalized.trim().length() == 0) {
                if (!normalizedLines.isEmpty()
                        && normalizedLines.get(normalizedLines.size() - 1).length() > 0) {
                    normalizedLines.add("");
                }
            } else {
                normalizedLines.add(normalized);
            }
        }
        while (!normalizedLines.isEmpty()
                && normalizedLines.get(normalizedLines.size() - 1).length() == 0) {
            normalizedLines.remove(normalizedLines.size() - 1);
        }
        return String.join("\n", normalizedLines);
    }

    private static String normalizeLine(String line) {
        String content = line == null ? "" : line.trim();
        content = stripHeading(content);
        while (content.startsWith(">")) {
            content = content.substring(1).trim();
        }
        if (isUnorderedListItem(content)) {
            content = "• " + stripTaskMarker(content.substring(2).trim());
        }
        return normalizeInline(content).trim();
    }

    private static String stripHeading(String text) {
        int count = 0;
        while (count < text.length() && count < 6 && text.charAt(count) == '#') {
            count++;
        }
        if (count > 0 && count < text.length() && Character.isWhitespace(text.charAt(count))) {
            return text.substring(count).trim();
        }
        return text;
    }

    private static boolean isUnorderedListItem(String text) {
        return text.length() > 1
                && (text.charAt(0) == '-' || text.charAt(0) == '*' || text.charAt(0) == '+')
                && Character.isWhitespace(text.charAt(1));
    }

    private static String stripTaskMarker(String text) {
        if (text.length() > 3 && text.charAt(0) == '[' && text.charAt(2) == ']'
                && (text.charAt(1) == ' ' || text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
            return text.substring(3).trim();
        }
        return text;
    }

    private static String normalizeInline(String text) {
        StringBuilder result = new StringBuilder();
        boolean inlineCode = false;
        for (int index = 0; index < text.length();) {
            char character = text.charAt(index);
            if (character == '`') {
                int runEnd = index + 1;
                while (runEnd < text.length() && text.charAt(runEnd) == '`') {
                    runEnd++;
                }
                inlineCode = !inlineCode;
                index = runEnd;
                continue;
            }
            if (!inlineCode) {
                int imageOffset = character == '!' && index + 1 < text.length()
                        && text.charAt(index + 1) == '[' ? 1 : 0;
                int linkStart = index + imageOffset;
                if (linkStart < text.length() && text.charAt(linkStart) == '[') {
                    int labelEnd = text.indexOf(']', linkStart + 1);
                    int targetStart = labelEnd < 0 ? -1 : labelEnd + 1;
                    if (labelEnd > linkStart && targetStart < text.length()
                            && text.charAt(targetStart) == '(') {
                        int targetEnd = text.indexOf(')', targetStart + 1);
                        if (targetEnd > targetStart) {
                            result.append(normalizeInline(text.substring(linkStart + 1, labelEnd)));
                            index = targetEnd + 1;
                            continue;
                        }
                    }
                }
                if (startsWith(text, index, "**") && isDoubleEmphasisMarker(text, index, '*')) {
                    index += 2;
                    continue;
                }
                if (startsWith(text, index, "__") && isDoubleEmphasisMarker(text, index, '_')) {
                    index += 2;
                    continue;
                }
                if (character == '*' && isSingleEmphasisMarker(text, index)) {
                    index++;
                    continue;
                }
            }
            result.append(character);
            index++;
        }
        return result.toString();
    }

    private static boolean isDoubleEmphasisMarker(String text, int index, char marker) {
        int nextIndex = index + 2;
        char previous = index == 0 ? ' ' : text.charAt(index - 1);
        char next = nextIndex >= text.length() ? ' ' : text.charAt(nextIndex);
        if (next == '/' || next == '\\') {
            return false;
        }
        String pair = new String(new char[]{marker, marker});
        boolean hasLaterPair = text.indexOf(pair, nextIndex) >= 0;
        boolean openingBoundary = index == 0 || Character.isWhitespace(previous) || isPunctuation(previous);
        boolean closingBoundary = nextIndex >= text.length()
                || Character.isWhitespace(next) || isPunctuation(next);
        return hasLaterPair || openingBoundary || closingBoundary;
    }

    private static boolean isSingleEmphasisMarker(String text, int index) {
        char previous = index == 0 ? ' ' : text.charAt(index - 1);
        char next = index + 1 >= text.length() ? ' ' : text.charAt(index + 1);
        if (Character.isLetterOrDigit(previous) && Character.isLetterOrDigit(next)) {
            return false;
        }
        boolean hasLaterMarker = text.indexOf('*', index + 1) >= 0;
        boolean openingBoundary = index == 0 || Character.isWhitespace(previous) || isPunctuation(previous);
        boolean closingBoundary = index + 1 >= text.length()
                || Character.isWhitespace(next) || isPunctuation(next);
        return hasLaterMarker || openingBoundary || closingBoundary;
    }

    private static boolean isPunctuation(char character) {
        return "，。！？：；、,.!?:;()（）[]【】{}<>《》\"'".indexOf(character) >= 0;
    }

    private static boolean isFence(String text) {
        return text.startsWith("```") || text.startsWith("~~~");
    }

    private static boolean startsWith(String text, int index, String value) {
        return index + value.length() <= text.length() && text.startsWith(value, index);
    }

    private static String trimTrailingWhitespace(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
