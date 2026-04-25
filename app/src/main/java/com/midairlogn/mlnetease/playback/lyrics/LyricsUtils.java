package com.midairlogn.mlnetease.playback.lyrics;

import android.content.Context;
import android.content.res.Configuration;

import com.midairlogn.mlnetease.settings.SettingsManager;
import com.midairlogn.mlnetease.shared.model.LyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsUtils {
    private static final long MERGE_TOLERANCE_MS = 80L;
    private static final Pattern LEADING_BLOCK_PATTERN = Pattern.compile("^((?:\\s*\\[\\d{1,2}[:.]\\d{1,2}(?:[:.](\\d{1,3}|\\d{2}-1|00-1))?.*])+)(.*)$");
    private static final Pattern INDIVIDUAL_TIMESTAMP_PATTERN = Pattern.compile("\\[\\d{1,2}[:.]\\d{1,2}(?:[:.](\\d{1,3}|\\d{2}-1|00-1))?.*?]");
    private static final Pattern RAW_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,2})[:.](\\d{1,2})(?:[:.](\\d{1,3}|\\d{2}-1|00-1))?.*?]");
    private static final Pattern CANONICAL_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?]");
    private static final Pattern INLINE_TRANSLATION_PATTERN = Pattern.compile("^(.*?)(?:\\((?:Translation|translation|翻译)\\s*[:：]\\s*(.+?)\\))\\s*$");

    public static SplitLyricsResult splitInlineTranslatedLyrics(String lyrics) {
        String processedLyrics = preprocessLyrics(lyrics);
        if (processedLyrics.isEmpty()) {
            String plainLyrics = normalizePlainLyrics(lyrics);
            return new SplitLyricsResult(plainLyrics, "");
        }

        String[] rawLines = processedLyrics.split("\\n");
        StringBuilder originalBuilder = new StringBuilder();
        StringBuilder translatedBuilder = new StringBuilder();
        for (String rawLine : rawLines) {
            Matcher matcher = CANONICAL_TIMESTAMP_PATTERN.matcher(rawLine);
            if (!matcher.find()) {
                appendLine(originalBuilder, rawLine);
                continue;
            }

            String timestamp = matcher.group();
            String content = rawLine.substring(matcher.end()).trim();
            Matcher translationMatcher = INLINE_TRANSLATION_PATTERN.matcher(content);
            if (!translationMatcher.matches()) {
                appendLine(originalBuilder, rawLine);
                continue;
            }

            String originalText = translationMatcher.group(1) == null ? "" : translationMatcher.group(1).trim();
            String translatedText = sanitizeTranslationText(translationMatcher.group(2));
            if (!originalText.isEmpty()) {
                appendLine(originalBuilder, timestamp + originalText);
            }
            if (!translatedText.isEmpty()) {
                appendLine(translatedBuilder, timestamp + translatedText);
            }
        }

        String original = originalBuilder.length() == 0 ? processedLyrics : originalBuilder.toString();
        return new SplitLyricsResult(resolveTimestampConflicts(original), resolveTimestampConflicts(translatedBuilder.toString()));
    }

    public static boolean hasTimestampedLyrics(String lyrics) {
        String processedLyrics = preprocessLyrics(lyrics);
        if (processedLyrics.isEmpty()) {
            return false;
        }
        String[] rawLines = processedLyrics.split("\\n");
        for (String line : rawLines) {
            if (CANONICAL_TIMESTAMP_PATTERN.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    public static List<LyricLine> parseLyrics(String lyrics) {
        return toLyricLines(parseLyricEntries(lyrics));
    }

    public static List<LyricLine> mergeLyricsWithTranslation(String lyric, String tlyric) {
        List<ParsedLyricLine> originalParsedLines = parseLyricEntries(lyric);
        if (originalParsedLines.isEmpty()) {
            return new ArrayList<>();
        }

        List<ParsedLyricLine> translationParsedLines = parseLyricEntries(tlyric);
        if (translationParsedLines.isEmpty()) {
            return toLyricLines(originalParsedLines);
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
                applyTranslationToExpansionGroup(originalParsedLines, translationByOriginalIndex, original, sanitizeTranslationText(translation.text));
            }
        }

        for (int i = 0; i < originalParsedLines.size(); i++) {
            ParsedLyricLine original = originalParsedLines.get(i);
            String translation = translationByOriginalIndex[i];
            merged.add(new LyricLine(original.time, original.text, translation == null ? "" : translation));
        }

        return merged;
    }

    public static String buildMergedLrc(Context context, SettingsManager settingsManager, String lyric, String tlyric) {
        String processedLyric = preprocessLyrics(lyric == null ? "" : lyric);
        if (processedLyric.isEmpty()) {
            return "";
        }
        if (tlyric == null || tlyric.trim().isEmpty()) {
            return resolveTimestampConflicts(processedLyric);
        }

        List<LyricLine> lines = mergeLyricsWithTranslation(processedLyric, preprocessLyrics(tlyric));
        StringBuilder builder = new StringBuilder();
        String translationPrefix = usesChineseTranslationLabel(context, settingsManager)
                ? " (翻译："
                : " (Translation: ";

        for (LyricLine line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(formatLrcTimestamp(line.time)).append(line.text);
            String translation = sanitizeTranslationText(line.translation);
            if (!translation.isEmpty()) {
                builder.append(translationPrefix).append(translation).append(')');
            }
        }

        return resolveTimestampConflicts(builder.toString());
    }

    public static String preprocessLyrics(String lyrics) {
        if (lyrics == null || lyrics.trim().isEmpty()) {
            return "";
        }

        String[] rawLines = lyrics.split("\\n");
        List<ProcessedLyricLine> processedLines = new ArrayList<>();
        for (String rawLine : rawLines) {
            String trimmed = rawLine == null ? "" : rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher blockMatcher = LEADING_BLOCK_PATTERN.matcher(trimmed);
            if (blockMatcher.matches()) {
                String timestamps = blockMatcher.group(1);
                String content = blockMatcher.group(3).trim();
                Matcher timestampMatcher = INDIVIDUAL_TIMESTAMP_PATTERN.matcher(timestamps);
                while (timestampMatcher.find()) {
                    String normalized = normalizeTimestampToken(timestampMatcher.group());
                    if (!normalized.isEmpty()) {
                        processedLines.add(new ProcessedLyricLine(normalized, content, false));
                    }
                }
                continue;
            }

            if (trimmed.startsWith("[") && trimmed.contains(":") && !trimmed.matches("^\\[\\d.*")) {
                processedLines.add(new ProcessedLyricLine(trimmed, "", true));
                continue;
            }

            processedLines.add(new ProcessedLyricLine("", trimmed, false));
        }

        processedLines.sort((left, right) -> {
            if (left.isMetadata != right.isMetadata) {
                return left.isMetadata ? -1 : 1;
            }
            if (left.isMetadata) {
                return 0;
            }
            if (left.timeToken.isEmpty() != right.timeToken.isEmpty()) {
                return left.timeToken.isEmpty() ? 1 : -1;
            }
            return left.timeToken.compareTo(right.timeToken);
        });

        StringBuilder builder = new StringBuilder();
        for (ProcessedLyricLine line : processedLines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line.timeToken).append(line.content);
        }
        return resolveTimestampConflicts(builder.toString());
    }

    private static List<LyricLine> toLyricLines(List<ParsedLyricLine> parsedLines) {
        List<LyricLine> lines = new ArrayList<>(parsedLines.size());
        for (ParsedLyricLine parsedLine : parsedLines) {
            lines.add(new LyricLine(parsedLine.time, parsedLine.text));
        }
        return lines;
    }

    private static List<ParsedLyricLine> parseLyricEntries(String lyrics) {
        List<ParsedLyricLine> parsedLines = new ArrayList<>();
        String processedLyrics = preprocessLyrics(lyrics);
        if (processedLyrics.isEmpty()) {
            return parsedLines;
        }

        String[] rawLines = processedLyrics.split("\\n");
        int expansionGroupId = 0;
        for (int sourceLineIndex = 0; sourceLineIndex < rawLines.length; sourceLineIndex++) {
            String rawLine = rawLines[sourceLineIndex];
            Matcher matcher = CANONICAL_TIMESTAMP_PATTERN.matcher(rawLine);
            if (!matcher.find()) {
                continue;
            }

            Long normalizedTime = normalizeTimestamp(matcher.group(1), matcher.group(2), matcher.group(3));
            if (normalizedTime == null) {
                continue;
            }

            String text = rawLine.substring(matcher.end()).trim();
            if (text.isEmpty()) {
                continue;
            }

            parsedLines.add(new ParsedLyricLine(normalizedTime, text, sourceLineIndex, expansionGroupId++));
        }

        Collections.sort(parsedLines, Comparator.comparingLong(line -> line.time));
        return parsedLines;
    }

    private static String normalizeTimestampToken(String rawTimestamp) {
        Matcher matcher = RAW_TIMESTAMP_PATTERN.matcher(rawTimestamp);
        if (!matcher.matches()) {
            return "";
        }

        Long normalizedTime = normalizeTimestamp(matcher.group(1), matcher.group(2), matcher.group(3));
        if (normalizedTime == null) {
            return "";
        }
        return formatLrcTimestamp(normalizedTime);
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
                } else if (fractionPart.length() == 1) {
                    ms = Long.parseLong(fractionPart) * 100L;
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

    private static String resolveTimestampConflicts(String lyrics) {
        if (lyrics == null || lyrics.isEmpty()) {
            return "";
        }

        String[] lines = lyrics.split("\\n");
        StringBuilder builder = new StringBuilder();
        long previousTimestampMs = -1L;
        for (String line : lines) {
            String updatedLine = line;
            Matcher matcher = CANONICAL_TIMESTAMP_PATTERN.matcher(line);
            if (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                int millis = matcher.group(3) == null ? 0 : Integer.parseInt(padRight(matcher.group(3), 3).substring(0, 3));
                long baseMs = minutes * 60000L + seconds * 1000L;
                long currentMs = baseMs + millis;
                if (currentMs <= previousTimestampMs) {
                    currentMs = Math.min(baseMs + 999L, previousTimestampMs + 5L);
                }
                previousTimestampMs = currentMs;
                long currentOffsetMs = Math.max(0L, Math.min(999L, currentMs - baseMs));
                String normalizedTimestamp = String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, currentOffsetMs);
                updatedLine = matcher.replaceFirst(Matcher.quoteReplacement(normalizedTimestamp));
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(updatedLine);
        }
        return builder.toString().trim();
    }

    private static String formatLrcTimestamp(long timeMs) {
        long minutes = timeMs / 60000L;
        long seconds = (timeMs % 60000L) / 1000L;
        long millis = timeMs % 1000L;
        return String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, millis);
    }

    private static String padRight(String input, int targetLength) {
        String value = input == null ? "" : input;
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < targetLength) {
            builder.append('0');
        }
        return builder.toString();
    }

    private static String sanitizeTranslationText(String translationText) {
        if (translationText == null) {
            return "";
        }
        String sanitized = translationText.trim();
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1).trim();
        }
        return sanitized;
    }

    public static String normalizePlainLyrics(String lyrics) {
        if (lyrics == null || lyrics.trim().isEmpty()) {
            return "";
        }
        String[] lines = lyrics.replace("\r\n", "\n").replace('\r', '\n').split("\\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(trimmed);
        }
        return builder.toString();
    }

    private static boolean usesChineseTranslationLabel(Context context, SettingsManager settingsManager) {
        String language = settingsManager == null ? "system" : settingsManager.getAppLanguage();
        if ("zh".equals(language)) {
            return true;
        }
        if ("en".equals(language)) {
            return false;
        }
        if (context == null) {
            return false;
        }

        Locale locale;
        Configuration configuration = context.getResources().getConfiguration();
        if (configuration.getLocales() != null && !configuration.getLocales().isEmpty()) {
            locale = configuration.getLocales().get(0);
        } else {
            locale = configuration.locale;
        }
        return locale != null && locale.getLanguage() != null && locale.getLanguage().startsWith("zh");
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
            if (usedTranslation[i]) {
                continue;
            }
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
            if (usedTranslation[i]) {
                continue;
            }

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

    public static final class SplitLyricsResult {
        public final String lyric;
        public final String translatedLyric;

        SplitLyricsResult(String lyric, String translatedLyric) {
            this.lyric = lyric == null ? "" : lyric;
            this.translatedLyric = translatedLyric == null ? "" : translatedLyric;
        }
    }

    private static class ProcessedLyricLine {
        final String timeToken;
        final String content;
        final boolean isMetadata;

        ProcessedLyricLine(String timeToken, String content, boolean isMetadata) {
            this.timeToken = timeToken == null ? "" : timeToken;
            this.content = content == null ? "" : content;
            this.isMetadata = isMetadata;
        }
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line.trim());
    }
}
