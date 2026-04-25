package com.midairlogn.mlnetease.home.shortcut;

public final class HomeShortcutIdParser {
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

        if (normalized.contains("music.163.com")) {
            int index = normalized.indexOf("id=");
            if (index != -1) {
                String sub = normalized.substring(index + 3);
                int end = sub.indexOf("&");
                if (end != -1) {
                    sub = sub.substring(0, end);
                }
                normalized = sub.trim();
            }
        }

        return normalized;
    }

    public static boolean isNumericId(String input) {
        return normalizeId(input).matches("\\d+");
    }
}
