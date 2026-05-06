package com.midairlogn.mlnetease.download.core;

import java.io.File;

public class PreparedAudioFile {
    public final File file;
    public final String displayName;
    public final String mimeType;
    public final String extension;

    public PreparedAudioFile(File file, String displayName, String mimeType, String extension) {
        this.file = file;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.extension = extension;
    }
}
