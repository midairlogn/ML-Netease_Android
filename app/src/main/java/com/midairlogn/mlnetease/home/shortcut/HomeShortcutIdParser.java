package com.midairlogn.mlnetease.home.shortcut;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HomeShortcutIdParser {
    private static final Pattern STANDALONE_ID_PATTERN = Pattern.compile("^(\\d+)$");
    private static final Pattern RESOURCE_PATH_ID_PATTERN = Pattern.compile("(?:^|[^\\w])(?:album|playlist|song)/(\\d+)(?:/|\\b)");
    private static final Pattern RESOURCE_QUERY_ID_PATTERN = Pattern.compile("(?:^|[^\\w])(?:album|playlist|song)\\?id=(\\d+)(?:[&#/?]|$)");

    private HomeShortcutIdParser() {
    }

    public static String normalizeId(String input) {
        if (input == null) {
            return "";
        }

        String normalized = input.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        Matcher standaloneMatcher = STANDALONE_ID_PATTERN.matcher(normalized);
        if (standaloneMatcher.matches()) {
            return standaloneMatcher.group(1);
        }

        Matcher resourcePathMatcher = RESOURCE_PATH_ID_PATTERN.matcher(normalized);
        if (resourcePathMatcher.find()) {
            return resourcePathMatcher.group(1);
        }

        Matcher resourceQueryMatcher = RESOURCE_QUERY_ID_PATTERN.matcher(normalized);
        if (resourceQueryMatcher.find()) {
            return resourceQueryMatcher.group(1);
        }

        return normalized;
    }

    public static boolean isNumericId(String input) {
        return normalizeId(input).matches("\\d+");
    }
}
