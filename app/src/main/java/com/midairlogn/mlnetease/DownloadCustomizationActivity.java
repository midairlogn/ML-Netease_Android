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

    private static final String VARIABLE_TITLE = "${title}";
    private static final String VARIABLE_ARTIST = "${artist}";
    private static final String VARIABLE_ALBUM = "${album}";
    private static final String TEMPLATE_PRESET_DEFAULT = VARIABLE_TITLE + "_" + VARIABLE_ARTIST + "_" + VARIABLE_ALBUM;
    private static final String TEMPLATE_PRESET_TITLE_ARTIST = VARIABLE_TITLE + "_" + VARIABLE_ARTIST;
    private static final String TEMPLATE_PRESET_ARTIST_TITLE = VARIABLE_ARTIST + "_" + VARIABLE_TITLE;
    private static final String TEMPLATE_PRESET_TITLE_ONLY = VARIABLE_TITLE;
    private static final String[] FALLBACK_VARIABLES = new String[]{VARIABLE_TITLE, VARIABLE_ARTIST, VARIABLE_ALBUM};

    private SettingsManager settingsManager;
    private EditText inputTemplate;
    private RadioGroup separatorGroup;
    private TextView textFilenamePreview;
    private TextView chipPresetTitleArtistAlbum;
    private TextView chipPresetTitleArtist;
    private TextView chipPresetArtistTitle;
    private TextView chipPresetTitle;
    private Switch switchMetadataEnabled;
    private CheckBox checkboxMetadataTitle;
    private CheckBox checkboxMetadataArtist;
    private CheckBox checkboxMetadataAlbum;
    private CheckBox checkboxMetadataLyrics;
    private CheckBox checkboxMetadataCover;
    private CheckBox checkboxMetadataExtra;
    private TextView textMetadataPreview;
    private boolean isBinding;
    private boolean isUpdatingTemplateText;
    private String lastSelectedSeparator = SettingsManager.DEFAULT_DOWNLOAD_FILENAME_SEPARATOR;

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
        chipPresetTitleArtistAlbum = findViewById(R.id.chip_preset_title_artist_album);
        chipPresetTitleArtist = findViewById(R.id.chip_preset_title_artist);
        chipPresetArtistTitle = findViewById(R.id.chip_preset_artist_title);
        chipPresetTitle = findViewById(R.id.chip_preset_title);
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

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindCurrentSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindCurrentSettings();
    }

    private void bindCurrentSettings() {
        isBinding = true;
        DownloadCustomizationSettings settings = settingsManager.getDownloadCustomizationSettings();
        String template = settings.fileNameTemplate;
        if (isBuiltInDefaultTemplate(template)) {
            setTemplateText("");
        } else {
            setTemplateText(template);
        }

        separatorGroup.check("-".equals(settings.separator)
                ? R.id.separator_hyphen
                : R.id.separator_underscore);
        lastSelectedSeparator = getSelectedSeparator();
        refreshFilenameUi();

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
                if (isBinding || isUpdatingTemplateText) {
                    return;
                }
                persistSettings();
            }
        });

        inputTemplate.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                persistSettings();
            }
        });

        separatorGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isBinding) {
                return;
            }
            handleSeparatorChanged(getSelectedSeparator());
            persistSettings();
        });

        findViewById(R.id.chip_variable_title).setOnClickListener(v -> insertVariable(VARIABLE_TITLE));
        findViewById(R.id.chip_variable_artist).setOnClickListener(v -> insertVariable(VARIABLE_ARTIST));
        findViewById(R.id.chip_variable_album).setOnClickListener(v -> insertVariable(VARIABLE_ALBUM));
        chipPresetTitleArtistAlbum.setOnClickListener(v -> applyPreset(TEMPLATE_PRESET_DEFAULT));
        chipPresetTitleArtist.setOnClickListener(v -> applyPreset(TEMPLATE_PRESET_TITLE_ARTIST));
        chipPresetArtistTitle.setOnClickListener(v -> applyPreset(TEMPLATE_PRESET_ARTIST_TITLE));
        chipPresetTitle.setOnClickListener(v -> applyPreset(TEMPLATE_PRESET_TITLE_ONLY));
        findViewById(R.id.btn_clear_template).setOnClickListener(v -> {
            setTemplateText("");
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
        setTemplateText(rewritePresetWithSeparator(preset, getSelectedSeparator()));
        persistSettings();
    }

    private String rewritePresetWithSeparator(String preset, String separator) {
        return preset.replace("_", separator);
    }

    private void handleSeparatorChanged(String newSeparator) {
        String previousSeparator = lastSelectedSeparator;
        lastSelectedSeparator = newSeparator;
        rewriteBuiltInTemplateForSeparatorChange(previousSeparator, newSeparator);
        refreshFilenameUi();
    }

    private void rewriteBuiltInTemplateForSeparatorChange(String previousSeparator, String newSeparator) {
        String currentValue = inputTemplate.getText().toString().trim();
        if (currentValue.isEmpty() || previousSeparator.equals(newSeparator)) {
            return;
        }
        String rewritten = rewriteBuiltInTemplate(currentValue, previousSeparator, newSeparator);
        if (!currentValue.equals(rewritten)) {
            setTemplateText(rewritten);
        }
    }

    private String rewriteBuiltInTemplate(String template, String previousSeparator, String newSeparator) {
        String[][] builtInTemplates = new String[][]{
                {TEMPLATE_PRESET_DEFAULT, TEMPLATE_PRESET_DEFAULT},
                {TEMPLATE_PRESET_TITLE_ARTIST, TEMPLATE_PRESET_TITLE_ARTIST},
                {TEMPLATE_PRESET_ARTIST_TITLE, TEMPLATE_PRESET_ARTIST_TITLE},
                {TEMPLATE_PRESET_TITLE_ONLY, TEMPLATE_PRESET_TITLE_ONLY}
        };
        for (String[] builtInTemplate : builtInTemplates) {
            String currentBuiltIn = rewritePresetWithSeparator(builtInTemplate[0], previousSeparator);
            if (currentBuiltIn.equals(template)) {
                return rewritePresetWithSeparator(builtInTemplate[1], newSeparator);
            }
        }
        return template;
    }

    private void persistSettings() {
        if (isBinding || isUpdatingTemplateText) {
            return;
        }
        DownloadCustomizationSettings settings = new DownloadCustomizationSettings();
        settings.fileNameTemplate = resolveCurrentTemplate();
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
        String template = resolveCurrentTemplate();
        String preview = template
                .replace(VARIABLE_TITLE, "Example Song")
                .replace(VARIABLE_ARTIST, "Example Artist")
                .replace(VARIABLE_ALBUM, "Example Album");
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

    private void refreshFilenameUi() {
        String separator = getSelectedSeparator();
        inputTemplate.setHint(getString(R.string.download_filename_hint).replace("_", separator));
        chipPresetTitleArtistAlbum.setText(getString(R.string.download_preset_title_artist_album).replace("_", separator));
        chipPresetTitleArtist.setText(getString(R.string.download_preset_title_artist).replace("_", separator));
        chipPresetArtistTitle.setText(getString(R.string.download_preset_artist_title).replace("_", separator));
        chipPresetTitle.setText(getString(R.string.download_preset_title));
        updatePreviews();
    }

    private String resolveCurrentTemplate() {
        String currentValue = inputTemplate.getText().toString().trim();
        if (currentValue.isEmpty()) {
            return rewritePresetWithSeparator(TEMPLATE_PRESET_DEFAULT, getSelectedSeparator());
        }
        return maybeAppendFallbackVariable(currentValue);
    }

    private String maybeAppendFallbackVariable(String template) {
        for (String variable : FALLBACK_VARIABLES) {
            if (template.contains(variable)) {
                return template;
            }
        }
        String normalized = template.trim();
        if (normalized.isEmpty()) {
            return VARIABLE_TITLE;
        }
        return normalized + getSelectedSeparator() + VARIABLE_TITLE;
    }

    private boolean isBuiltInDefaultTemplate(String template) {
        return rewritePresetWithSeparator(TEMPLATE_PRESET_DEFAULT, SettingsManager.DEFAULT_DOWNLOAD_FILENAME_SEPARATOR).equals(template)
                || rewritePresetWithSeparator(TEMPLATE_PRESET_DEFAULT, "-").equals(template);
    }

    private void setTemplateText(String value) {
        isUpdatingTemplateText = true;
        inputTemplate.setText(value);
        inputTemplate.setSelection(inputTemplate.getText().length());
        isUpdatingTemplateText = false;
    }
}
