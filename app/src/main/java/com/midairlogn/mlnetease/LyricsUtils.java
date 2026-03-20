package com.midairlogn.mlnetease;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsUtils {
    private static final long MERGE_TOLERANCE_MS = 80L;
    private static final Pattern LEADING_TIMESTAMP_PATTERN = Pattern.compile("^\\[(\\d{2}):(\\d{2})(?:[.:](\\d{2,3}|\\d{2}-1|00-1))?\\]");

    public static List<LyricLine> parseLyrics(String lyrics) {
        List<ParsedLyricLine> parsedLines = parseLyricEntries(lyrics);
        List<LyricLine> lines = new ArrayList<>(parsedLines.size());
        for (ParsedLyricLine parsedLine : parsedLines) {
            lines.add(new LyricLine(parsedLine.time, parsedLine.text));
        }
        return lines;
    }

    public static List<LyricLine> mergeLyricsWithTranslation(String lyric, String tlyric) {
        List<ParsedLyricLine> originalParsedLines = parseLyricEntries(lyric);
        if (originalParsedLines.isEmpty()) {
            return new ArrayList<>();
        }

        List<ParsedLyricLine> translationParsedLines = parseLyricEntries(tlyric);
        if (translationParsedLines.isEmpty()) {
            List<LyricLine> originalLines = new ArrayList<>(originalParsedLines.size());
            for (ParsedLyricLine original : originalParsedLines) {
                originalLines.add(new LyricLine(original.time, original.text));
            }
            return originalLines;
        }

        List<LyricLine> merged = new ArrayList<>(originalParsedLines.size());
        boolean[] usedTranslation = new boolean[translationParsedLines.size()];
        String[] translationByOriginalIndex = new String[originalParsedLines.size()];

        for (int i = 0; i < originalParsedLines.size(); i++) {
            ParsedLyricLine original = originalParsedLines.get(i);
            int matchIndex = findExactMatchIndex(translationParsedLines, usedTranslation, original.time);
            if (matchIndex == -1) {
                matchIndex = findToleranceMatchIndex(translationParsedLines, usedTranslation, original.time, MERGE_TOLERANCE_MS);
            }

            if (matchIndex != -1) {
                usedTranslation[matchIndex] = true;
                ParsedLyricLine translation = translationParsedLines.get(matchIndex);
                applyTranslationToExpansionGroup(originalParsedLines, translationByOriginalIndex, original, translation.text);
            }
        }

        for (int i = 0; i < originalParsedLines.size(); i++) {
            ParsedLyricLine original = originalParsedLines.get(i);
            String translation = translationByOriginalIndex[i];
            merged.add(new LyricLine(original.time, original.text, translation == null ? "" : translation));
        }

        return merged;
    }

    private static List<ParsedLyricLine> parseLyricEntries(String lyrics) {
        List<ParsedLyricLine> parsedLines = new ArrayList<>();
        if (lyrics == null || lyrics.isEmpty()) return parsedLines;

        String[] rawLines = lyrics.split("\\n");
        int expansionGroupId = 0;
        for (int sourceLineIndex = 0; sourceLineIndex < rawLines.length; sourceLineIndex++) {
            String rawLine = rawLines[sourceLineIndex];
            List<Long> timestamps = new ArrayList<>();
            String remaining = rawLine;
            boolean encounteredInvalidTimestamp = false;

            while (true) {
                if (remaining.startsWith("[")) {
                    Matcher matcher = LEADING_TIMESTAMP_PATTERN.matcher(remaining);
                    if (!matcher.find()) {
                        encounteredInvalidTimestamp = true;
                        break;
                    }

                    Long normalizedTime = normalizeTimestamp(matcher.group(1), matcher.group(2), matcher.group(3));
                    if (normalizedTime == null) {
                        encounteredInvalidTimestamp = true;
                        break;
                    }

                    timestamps.add(normalizedTime);
                    remaining = remaining.substring(matcher.end());
                    continue;
                }
                break;
            }

            String text = remaining.trim();
            if (encounteredInvalidTimestamp || timestamps.isEmpty() || text.isEmpty()) {
                continue;
            }

            int currentGroupId = expansionGroupId++;
            for (Long timestamp : timestamps) {
                parsedLines.add(new ParsedLyricLine(timestamp, text, sourceLineIndex, currentGroupId));
            }
        }

        Collections.sort(parsedLines, Comparator.comparingLong(line -> line.time));
        return parsedLines;
    }

    private static Long normalizeTimestamp(String minutePart, String secondPart, String fractionPart) {
        try {
            long min = Long.parseLong(minutePart);
            long sec = Long.parseLong(secondPart);
            long ms = 0L;

            if (fractionPart != null) {
                if (fractionPart.endsWith("-1")) {
                    String numericPart = fractionPart.substring(0, fractionPart.length() - 2);
                    if (min == 0 && sec == 0 && "00".equals(numericPart)) {
                        return null;
                    }
                    ms = Long.parseLong(numericPart) * 10L;
                } else if (fractionPart.length() == 2) {
                    ms = Long.parseLong(fractionPart) * 10L;
                } else if (fractionPart.length() == 3) {
                    ms = Long.parseLong(fractionPart);
                } else {
                    return null;
                }
            }

            return min * 60000L + sec * 1000L + ms;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void applyTranslationToExpansionGroup(List<ParsedLyricLine> originalLines, String[] translationByOriginalIndex,
                                                         ParsedLyricLine matchedOriginal, String translationText) {
        for (int i = 0; i < originalLines.size(); i++) {
            ParsedLyricLine candidate = originalLines.get(i);
            if (candidate.sourceLineIndex == matchedOriginal.sourceLineIndex
                    && candidate.expansionGroupId == matchedOriginal.expansionGroupId
                    && candidate.text.equals(matchedOriginal.text)) {
                translationByOriginalIndex[i] = translationText;
            }
        }
    }

    private static int findExactMatchIndex(List<ParsedLyricLine> translationLines, boolean[] usedTranslation, long timeMs) {
        for (int i = 0; i < translationLines.size(); i++) {
            if (usedTranslation[i]) continue;
            if (translationLines.get(i).time == timeMs) {
                return i;
            }
        }
        return -1;
    }

    private static int findToleranceMatchIndex(List<ParsedLyricLine> translationLines, boolean[] usedTranslation, long timeMs, long toleranceMs) {
        int bestIndex = -1;
        long bestDiff = Long.MAX_VALUE;

        for (int i = 0; i < translationLines.size(); i++) {
            if (usedTranslation[i]) continue;

            long diff = Math.abs(translationLines.get(i).time - timeMs);
            if (diff <= toleranceMs && diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    private static class ParsedLyricLine {
        final long time;
        final String text;
        final int sourceLineIndex;
        final int expansionGroupId;

        ParsedLyricLine(long time, String text, int sourceLineIndex, int expansionGroupId) {
            this.time = time;
            this.text = text;
            this.sourceLineIndex = sourceLineIndex;
            this.expansionGroupId = expansionGroupId;
        }
    }
}
