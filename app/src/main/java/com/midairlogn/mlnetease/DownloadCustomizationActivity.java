package com.midairlogn.mlnetease;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class DownloadCustomizationActivity extends AppCompatActivity {

    private SettingsManager settingsManager;
    private EditText inputTemplate;
    private RadioGroup separatorGroup;
    private TextView textFilenamePreview;
    private Switch switchMetadataEnabled;
    private CheckBox checkboxMetadataTitle;
    private CheckBox checkboxMetadataArtist;
    private CheckBox checkboxMetadataAlbum;
    private CheckBox checkboxMetadataLyrics;
    private CheckBox checkboxMetadataCover;
    private CheckBox checkboxMetadataExtra;
    private TextView textMetadataPreview;
    private boolean isBinding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_customization);

        settingsManager = new SettingsManager(this);

        View buttonBack = findViewById(R.id.btn_back);
        buttonBack.setOnClickListener(v -> finish());

        inputTemplate = findViewById(R.id.input_filename_template);
        separatorGroup = findViewById(R.id.separator_group);
        textFilenamePreview = findViewById(R.id.text_filename_preview);
        switchMetadataEnabled = findViewById(R.id.switch_metadata_enabled);
        checkboxMetadataTitle = findViewById(R.id.checkbox_metadata_title);
        checkboxMetadataArtist = findViewById(R.id.checkbox_metadata_artist);
        checkboxMetadataAlbum = findViewById(R.id.checkbox_metadata_album);
        checkboxMetadataLyrics = findViewById(R.id.checkbox_metadata_lyrics);
        checkboxMetadataCover = findViewById(R.id.checkbox_metadata_cover);
        checkboxMetadataExtra = findViewById(R.id.checkbox_metadata_extra);
        textMetadataPreview = findViewById(R.id.text_metadata_preview);

        bindCurrentSettings();
        setupListeners();
    }

    private void bindCurrentSettings() {
        isBinding = true;
        DownloadCustomizationSettings settings = settingsManager.getDownloadCustomizationSettings();
        String template = settings.fileNameTemplate;
        if (SettingsManager.DEFAULT_DOWNLOAD_FILENAME_TEMPLATE.equals(template)) {
            inputTemplate.setText("");
        } else {
            inputTemplate.setText(template);
        }

        separatorGroup.check("-".equals(settings.separator)
                ? R.id.separator_hyphen
                : R.id.separator_underscore);

        switchMetadataEnabled.setChecked(settings.metadataEnabled);
        checkboxMetadataTitle.setChecked(settings.writeTitle);
        checkboxMetadataArtist.setChecked(settings.writeArtist);
        checkboxMetadataAlbum.setChecked(settings.writeAlbum);
        checkboxMetadataLyrics.setChecked(settings.writeLyrics);
        checkboxMetadataCover.setChecked(settings.writeCover);
        checkboxMetadataExtra.setChecked(settings.writeExtra);
        updateMetadataControlsState();
        updatePreviews();
        isBinding = false;
    }

    private void setupListeners() {
        inputTemplate.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                persistSettings();
            }
        });

        inputTemplate.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                maybeAppendFallbackVariable();
                persistSettings();
            }
        });

        separatorGroup.setOnCheckedChangeListener((group, checkedId) -> {
            maybeAppendFallbackVariable();
            persistSettings();
        });

        findViewById(R.id.chip_variable_title).setOnClickListener(v -> insertVariable("${title}"));
        findViewById(R.id.chip_variable_artist).setOnClickListener(v -> insertVariable("${artist}"));
        findViewById(R.id.chip_variable_album).setOnClickListener(v -> insertVariable("${album}"));
        findViewById(R.id.chip_preset_title_artist_album).setOnClickListener(v -> applyPreset("${title}_${artist}_${album}"));
        findViewById(R.id.chip_preset_title_artist).setOnClickListener(v -> applyPreset("${title}_${artist}"));
        findViewById(R.id.chip_preset_artist_title).setOnClickListener(v -> applyPreset("${artist}_${title}"));
        findViewById(R.id.chip_preset_title).setOnClickListener(v -> applyPreset("${title}"));
        findViewById(R.id.btn_clear_template).setOnClickListener(v -> {
            inputTemplate.setText("");
            persistSettings();
        });
        findViewById(R.id.btn_reset_download_customize).setOnClickListener(v -> {
            settingsManager.setDownloadCustomizationSettings(new DownloadCustomizationSettings());
            bindCurrentSettings();
        });

        View.OnClickListener metadataListener = v -> {
            updateMetadataControlsState();
            persistSettings();
        };
        switchMetadataEnabled.setOnClickListener(metadataListener);
        checkboxMetadataTitle.setOnClickListener(metadataListener);
        checkboxMetadataArtist.setOnClickListener(metadataListener);
        checkboxMetadataAlbum.setOnClickListener(metadataListener);
        checkboxMetadataLyrics.setOnClickListener(metadataListener);
        checkboxMetadataCover.setOnClickListener(metadataListener);
        checkboxMetadataExtra.setOnClickListener(metadataListener);
    }

    private void insertVariable(String variable) {
        int start = Math.max(inputTemplate.getSelectionStart(), 0);
        int end = Math.max(inputTemplate.getSelectionEnd(), 0);
        inputTemplate.getText().replace(Math.min(start, end), Math.max(start, end), variable);
    }

    private void applyPreset(String preset) {
        inputTemplate.setText(rewritePresetWithSeparator(preset, getSelectedSeparator()));
        inputTemplate.setSelection(inputTemplate.getText().length());
    }

    private String rewritePresetWithSeparator(String preset, String separator) {
        return preset.replace("_", separator);
    }

    private void maybeAppendFallbackVariable() {
        String currentValue = inputTemplate.getText().toString().trim();
        if (currentValue.isEmpty()) {
            return;
        }
        if (currentValue.matches(".*\\$\\{(title|artist|album)}.*")) {
            return;
        }
        String separator = getSelectedSeparator();
        String nextValue = currentValue + separator + "${title}";
        inputTemplate.setText(nextValue);
        inputTemplate.setSelection(nextValue.length());
    }

    private void persistSettings() {
        if (isBinding) {
            return;
        }
        DownloadCustomizationSettings settings = new DownloadCustomizationSettings();
        settings.fileNameTemplate = inputTemplate.getText().toString().trim();
        settings.separator = getSelectedSeparator();
        settings.metadataEnabled = switchMetadataEnabled.isChecked();
        settings.writeTitle = checkboxMetadataTitle.isChecked();
        settings.writeArtist = checkboxMetadataArtist.isChecked();
        settings.writeAlbum = checkboxMetadataAlbum.isChecked();
        settings.writeLyrics = checkboxMetadataLyrics.isChecked();
        settings.writeCover = checkboxMetadataCover.isChecked();
        settings.writeExtra = checkboxMetadataExtra.isChecked();
        settingsManager.setDownloadCustomizationSettings(settings);
        updatePreviews();
    }

    private String getSelectedSeparator() {
        return separatorGroup.getCheckedRadioButtonId() == R.id.separator_hyphen ? "-" : "_";
    }

    private void updateMetadataControlsState() {
        boolean enabled = switchMetadataEnabled.isChecked();
        checkboxMetadataTitle.setEnabled(enabled);
        checkboxMetadataArtist.setEnabled(enabled);
        checkboxMetadataAlbum.setEnabled(enabled);
        checkboxMetadataLyrics.setEnabled(enabled);
        checkboxMetadataCover.setEnabled(enabled);
        checkboxMetadataExtra.setEnabled(enabled);
        float alpha = enabled ? 1f : 0.45f;
        checkboxMetadataTitle.setAlpha(alpha);
        checkboxMetadataArtist.setAlpha(alpha);
        checkboxMetadataAlbum.setAlpha(alpha);
        checkboxMetadataLyrics.setAlpha(alpha);
        checkboxMetadataCover.setAlpha(alpha);
        checkboxMetadataExtra.setAlpha(alpha);
    }

    private void updatePreviews() {
        textFilenamePreview.setText(buildFilenamePreview());
        textMetadataPreview.setText(buildMetadataPreview());
    }

    private String buildFilenamePreview() {
        String template = settingsManager.getDownloadFileNameTemplate();
        String preview = template
                .replace("${title}", "Example Song")
                .replace("${artist}", "Example Artist")
                .replace("${album}", "Example Album");
        preview = DownloadFileUtils.sanitizeFileName(preview);
        if (preview.isEmpty()) {
            preview = "netease_example";
        }
        return preview;
    }

    private String buildMetadataPreview() {
        if (!switchMetadataEnabled.isChecked()) {
            return getString(R.string.download_metadata_preview_none);
        }
        List<String> items = new ArrayList<>();
        if (checkboxMetadataTitle.isChecked()) {
            items.add(getString(R.string.download_metadata_title));
        }
        if (checkboxMetadataArtist.isChecked()) {
            items.add(getString(R.string.download_metadata_artist));
        }
        if (checkboxMetadataAlbum.isChecked()) {
            items.add(getString(R.string.download_metadata_album));
        }
        if (checkboxMetadataLyrics.isChecked()) {
            items.add(getString(R.string.download_metadata_lyrics));
        }
        if (checkboxMetadataCover.isChecked()) {
            items.add(getString(R.string.download_metadata_cover));
        }
        if (checkboxMetadataExtra.isChecked()) {
            items.add(getString(R.string.download_metadata_extra));
        }
        if (items.isEmpty()) {
            return getString(R.string.download_metadata_preview_none);
        }
        return getString(R.string.download_metadata_preview_format, android.text.TextUtils.join(" / ", items));
    }
}
