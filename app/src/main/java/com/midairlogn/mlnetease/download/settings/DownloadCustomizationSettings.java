package com.midairlogn.mlnetease.download.settings;

import com.midairlogn.mlnetease.settings.SettingsManager;

public class DownloadCustomizationSettings {
    public String fileNameTemplate = SettingsManager.DEFAULT_DOWNLOAD_FILENAME_TEMPLATE;
    public String separator = SettingsManager.DEFAULT_DOWNLOAD_FILENAME_SEPARATOR;
    public boolean metadataEnabled = true;
    public boolean writeTitle = true;
    public boolean writeArtist = true;
    public boolean writeAlbum = true;
    public boolean writeLyrics = true;
    public boolean writeCover = true;
    public boolean writeExtra = true;
    public boolean writeVolumeMetadata = true;
}
