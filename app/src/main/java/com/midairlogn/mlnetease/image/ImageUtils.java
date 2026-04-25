package com.midairlogn.mlnetease.image;

public class ImageUtils {
    /**
     * Normalizes a Netease music image URL by removing the mirror subdomain (e.g., p1, p2, p3, p4).
     * This helps in identifying if two different URLs are actually pointing to the same image.
     * Example: https://p1.music.126.net/abc.jpg -> https://music.126.net/abc.jpg
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return url;

        // Match patterns like https://p1.music.126.net/ or http://p4.music.126.net/
        // and replace them with https://music.126.net/
        return url.replaceAll("https?://p\\d\\.music\\.126\\.net/", "https://music.126.net/");
    }

    /**
     * Compares two Netease music image URLs for equality after normalization.
     */
    public static boolean isSameImage(String url1, String url2) {
        if (url1 == null || url2 == null) return url1 == url2;
        return normalizeUrl(url1).equals(normalizeUrl(url2));
    }
}
