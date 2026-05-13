package com.midairlogn.mlnetease.download.tag;

import com.midairlogn.mlnetease.download.model.DownloadTagData;
import com.midairlogn.mlnetease.shared.metadata.VolumeMetadataTags;
import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.ID3v24Frame;
import com.mpatric.mp3agic.ID3v2FrameSet;
import com.mpatric.mp3agic.Mp3File;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;

public final class Mp3TagWriter {
    private Mp3TagWriter() {}

    public static void writeTaggedFile(File input, File output, DownloadTagData tagData) throws Exception {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output files are required");
        }

        Mp3File mp3File = new Mp3File(input.getAbsolutePath());
        ID3v24Tag tag = new ID3v24Tag();
        tag.setTitle(tagData.title);
        tag.setArtist(tagData.artist);
        tag.setAlbum(tagData.album);
        tag.setComment(tagData.comment);
        if (tagData.lyrics != null && !tagData.lyrics.trim().isEmpty()) {
            tag.setLyrics(normalizeLyricsForId3(tagData.lyrics));
        }
        if (tagData.coverData != null && tagData.coverData.length > 0) {
            tag.setAlbumImage(tagData.coverData, tagData.coverMimeType);
        }
        writeVolumeFrames(tag, tagData);
        mp3File.setId3v2Tag(tag);
        mp3File.save(output.getAbsolutePath());
    }

    private static void writeVolumeFrames(ID3v24Tag tag, DownloadTagData tagData) {
        if (tag == null || tagData == null) {
            return;
        }
        if (tagData.hasClosedGain) {
            addTextFrame(tag, VolumeMetadataTags.REPLAYGAIN_TRACK_GAIN, VolumeMetadataTags.formatGainDb(tagData.closedGainDb));
            addTextFrame(tag, VolumeMetadataTags.NETEASE_CLOSED_GAIN, VolumeMetadataTags.formatNumber(tagData.closedGainDb));
        } else if (tagData.hasGain) {
            addTextFrame(tag, VolumeMetadataTags.REPLAYGAIN_TRACK_GAIN, VolumeMetadataTags.formatGainDb(tagData.gainDb));
        }
        if (tagData.hasClosedPeak && tagData.closedPeak > 0f) {
            addTextFrame(tag, VolumeMetadataTags.REPLAYGAIN_TRACK_PEAK, VolumeMetadataTags.formatNumber(tagData.closedPeak));
            addTextFrame(tag, VolumeMetadataTags.NETEASE_CLOSED_PEAK, VolumeMetadataTags.formatNumber(tagData.closedPeak));
        } else if (tagData.hasPeak && tagData.peak > 0f) {
            addTextFrame(tag, VolumeMetadataTags.REPLAYGAIN_TRACK_PEAK, VolumeMetadataTags.formatNumber(tagData.peak));
        }
        if (tagData.hasGain) {
            addTextFrame(tag, VolumeMetadataTags.NETEASE_GAIN, VolumeMetadataTags.formatNumber(tagData.gainDb));
        }
        if (tagData.hasPeak && tagData.peak > 0f) {
            addTextFrame(tag, VolumeMetadataTags.NETEASE_PEAK, VolumeMetadataTags.formatNumber(tagData.peak));
        }
    }

    private static void addTextFrame(ID3v24Tag tag, String frameId, String value) {
        if (frameId == null || value == null || value.trim().isEmpty()) {
            return;
        }
        byte[] frameData = buildUserTextFrameData(frameId, value);
        Map<String, ID3v2FrameSet> frameSets = tag.getFrameSets();
        ID3v2FrameSet frameSet = frameSets.get("TXXX");
        if (frameSet == null) {
            frameSet = new ID3v2FrameSet("TXXX");
            frameSets.put("TXXX", frameSet);
        }
        frameSet.addFrame(new ID3v24Frame("TXXX", frameData));
    }

    private static byte[] buildUserTextFrameData(String description, String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(3); // ID3v2.4 UTF-8 encoding marker.
        byte[] descriptionBytes = description.getBytes(StandardCharsets.UTF_8);
        output.write(descriptionBytes, 0, descriptionBytes.length);
        output.write(0);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(valueBytes, 0, valueBytes.length);
        return output.toByteArray();
    }

    private static String normalizeLyricsForId3(String lyrics) {
        String normalized = lyrics.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        return String.format(Locale.US, "%s", normalized);
    }
}
