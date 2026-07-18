package com.midairlogn.mlnetease.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

public final class CoverUtils {
    private static final int MAX_SIDE_LENGTH = 640;
    private static final int MAX_IMAGE_BYTES = 500 * 1024;

    private CoverUtils() {}

    public static byte[] resizeCover(byte[] input) {
        if (input == null || input.length == 0) {
            return null;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_LENGTH, MAX_SIDE_LENGTH);
        Bitmap bitmap = BitmapFactory.decodeByteArray(input, 0, input.length, options);
        if (bitmap == null) {
            return input;
        }

        Bitmap scaled = scaleDown(bitmap, MAX_SIDE_LENGTH);
        if (scaled != bitmap) {
            bitmap.recycle();
        }

        int quality = 92;
        byte[] result = toJpegBytes(scaled, quality);
        while (result.length > MAX_IMAGE_BYTES && quality > 35) {
            quality -= 10;
            result = toJpegBytes(scaled, quality);
        }
        scaled.recycle();
        return result;
    }

    public static String getCoverMimeType() {
        return "image/jpeg";
    }

    public static String getCoverMimeType(byte[] input) {
        if (input == null || input.length == 0) {
            return getCoverMimeType();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, options);
        return options.outMimeType == null || options.outMimeType.trim().isEmpty()
                ? getCoverMimeType()
                : options.outMimeType;
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxSide) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSide) {
            return bitmap;
        }
        float scale = (float) maxSide / (float) longest;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private static byte[] toJpegBytes(Bitmap bitmap, int quality) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
        return outputStream.toByteArray();
    }

    private static int calculateInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        while ((height / inSampleSize) > reqHeight * 2 || (width / inSampleSize) > reqWidth * 2) {
            inSampleSize *= 2;
        }
        return Math.max(1, inSampleSize);
    }
}
