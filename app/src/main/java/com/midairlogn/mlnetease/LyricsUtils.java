package com.midairlogn.mlnetease;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LyricsUtils {
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
                    // Normalize ms
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
        return lines;
    }
}
