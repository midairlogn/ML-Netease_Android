package com.midairlogn.mlnetease;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsUtils {
    private static final long MERGE_TOLERANCE_MS = 80L;

    public static List<LyricLine> parseLyrics(String lyrics) {
        List<LyricLine> lines = new ArrayList<>();
        if (lyrics == null || lyrics.isEmpty()) return lines;

        // Pattern: [mm:ss.xx] or [mm:ss.xxx]
        Pattern pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)");

        String[] rawLines = lyrics.split("\n");
        for (String line : rawLines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                try {
                    long min = Long.parseLong(matcher.group(1));
                    long sec = Long.parseLong(matcher.group(2));
                    String msStr = matcher.group(3);
                    long ms = Long.parseLong(msStr);
                    if (msStr.length() == 2) ms *= 10;
                    if (msStr.length() == 1) ms *= 100;

                    long time = min * 60000 + sec * 1000 + ms;
                    String text = matcher.group(4).trim();

                    if (!text.isEmpty()) {
                        lines.add(new LyricLine(time, text));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        Collections.sort(lines, Comparator.comparingLong(line -> line.time));
        return lines;
    }

    public static List<LyricLine> mergeLyricsWithTranslation(String lyric, String tlyric) {
        List<LyricLine> originalLines = parseLyrics(lyric);
        if (originalLines.isEmpty()) {
            return originalLines;
        }

        List<LyricLine> translationLines = parseLyrics(tlyric);
        if (translationLines.isEmpty()) {
            return originalLines;
        }

        List<LyricLine> merged = new ArrayList<>(originalLines.size());
        boolean[] usedTranslation = new boolean[translationLines.size()];

        for (LyricLine original : originalLines) {
            int matchIndex = findExactMatchIndex(translationLines, usedTranslation, original.time);
            if (matchIndex == -1) {
                matchIndex = findToleranceMatchIndex(translationLines, usedTranslation, original.time, MERGE_TOLERANCE_MS);
            }

            if (matchIndex != -1) {
                usedTranslation[matchIndex] = true;
                merged.add(new LyricLine(original.time, original.text, translationLines.get(matchIndex).text));
            } else {
                merged.add(new LyricLine(original.time, original.text, ""));
            }
        }

        return merged;
    }

    private static int findExactMatchIndex(List<LyricLine> translationLines, boolean[] usedTranslation, long timeMs) {
        for (int i = 0; i < translationLines.size(); i++) {
            if (usedTranslation[i]) continue;
            if (translationLines.get(i).time == timeMs) {
                return i;
            }
        }
        return -1;
    }

    private static int findToleranceMatchIndex(List<LyricLine> translationLines, boolean[] usedTranslation, long timeMs, long toleranceMs) {
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
}
