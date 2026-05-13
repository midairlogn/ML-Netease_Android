package com.midairlogn.mlnetease.download.core;

import java.io.File;

public class PreparedAudioFile {
    public final File file;
    public final String displayName;
    public final String mimeType;
    public final String extension;
    public final boolean skippedExisting;

    public PreparedAudioFile(File file, String displayName, String mimeType, String extension) {
        this(file, displayName, mimeType, extension, false);
    }

    public PreparedAudioFile(File file, String displayName, String mimeType, String extension, boolean skippedExisting) {
        this.file = file;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.extension = extension;
        this.skippedExisting = skippedExisting;
    }
}
