package com.midairlogn.mlnetease;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.Toast;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    private SettingsManager settingsManager;
    private EditText inputMusicU;
    private EditText inputSearchLimit;
    private RadioGroup qualityGroup;
    private SeekBar seekbarAppVolume;
    private TextView textAppVolumeValue;
    private Spinner spinnerLanguage;
    private View layoutDownloadCustomize;
    private TextView textDownloadCustomizeSummary;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable saveRunnable;

    // Floating Window
    private Switch switchFloatingLyrics;
    private Switch switchTranslationIntegration;
    private LinearLayout layoutFloatingSettings;
    private Button btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow, btnColorPurple;
    private TextView textFontSize;
    private Button btnSizePlus, btnSizeMinus;
    private TextView textLyricPreviewCurrent;
    private TextView textLyricPreviewNext;

    private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    private int tempColor = 0;
    private float tempSize = 16f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settingsManager = new SettingsManager(requireContext());

        // Make root layout focusable to intercept clicks for keyboard hiding
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);

        View.OnTouchListener hideKeyboardTouchListener = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (getActivity() != null && getActivity().getCurrentFocus() != null) {
                    hideKeyboard(getActivity().getCurrentFocus());
                    getActivity().getCurrentFocus().clearFocus();
                }
            }
            return false;
        };

        view.setOnTouchListener(hideKeyboardTouchListener);

        inputMusicU = view.findViewById(R.id.input_music_u);
        inputSearchLimit = view.findViewById(R.id.input_search_limit);

        // Also apply to the inner container to ensure it covers all areas
        View innerContainer = view.findViewById(R.id.settings_inner_container);
        if (innerContainer != null) {
            innerContainer.setOnTouchListener(hideKeyboardTouchListener);
        }

        qualityGroup = view.findViewById(R.id.quality_group);
        seekbarAppVolume = view.findViewById(R.id.seekbar_app_volume);
        textAppVolumeValue = view.findViewById(R.id.text_app_volume_value);
        spinnerLanguage = view.findViewById(R.id.spinner_language);
        layoutDownloadCustomize = view.findViewById(R.id.layout_download_customize);
        textDownloadCustomizeSummary = view.findViewById(R.id.text_download_customize_summary);
        qualityGroup.setOnTouchListener(hideKeyboardTouchListener);

        // Floating Window Views
        switchFloatingLyrics = view.findViewById(R.id.switch_floating_lyrics);
        switchTranslationIntegration = view.findViewById(R.id.switch_translation_integration);
        layoutFloatingSettings = view.findViewById(R.id.layout_floating_settings);
        btnColorRed = view.findViewById(R.id.btn_color_red);
        btnColorBlue = view.findViewById(R.id.btn_color_blue);
        btnColorGreen = view.findViewById(R.id.btn_color_green);
        btnColorYellow = view.findViewById(R.id.btn_color_yellow);
        btnColorPurple = view.findViewById(R.id.btn_color_purple);
        textFontSize = view.findViewById(R.id.text_font_size);
        btnSizePlus = view.findViewById(R.id.btn_size_plus);
        btnSizeMinus = view.findViewById(R.id.btn_size_minus);
        textLyricPreviewCurrent = view.findViewById(R.id.text_lyric_preview_current);
        textLyricPreviewNext = view.findViewById(R.id.text_lyric_preview_next);

        TextView versionInfo = view.findViewById(R.id.text_version_info);
        versionInfo.setMovementMethod(LinkMovementMethod.getInstance());
        String versionName = "0.0.0";
        try {
            versionName = BuildConfig.VERSION_NAME;
        } catch (Exception e) {
            // Use default
        }
        String infoText = "Version: v" + versionName + " | Author: <a href=\"https://github.com/midairlogn\">Midairlogn</a><br>" +
                "<a href=\"https://github.com/midairlogn/ML-Netease_Android\">ML-Netease_Android</a> © 2026 | GPLv3 LICENSE";
        versionInfo.setText(Html.fromHtml(infoText, Html.FROM_HTML_MODE_LEGACY));

        // Init values
        inputMusicU.setText(settingsManager.getMusicU());
        inputSearchLimit.setText(String.valueOf(settingsManager.getSearchLimit()));

        // Listen for preference changes (e.g. from notification)
        preferenceChangeListener = (sharedPreferences, key) -> {
            if (getActivity() == null) return;
            getActivity().runOnUiThread(this::refreshSettingsUI);
        };
        settingsManager.getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        // Input Listeners
        setupInputListeners();
        layoutDownloadCustomize.setOnClickListener(v -> startActivity(new Intent(requireContext(), DownloadCustomizationActivity.class)));

        String currentQuality = settingsManager.getQuality();

        switch (currentQuality) {
            case "standard": qualityGroup.check(R.id.quality_standard); break;
            case "exhigh": qualityGroup.check(R.id.quality_exhigh); break;
            case "lossless": qualityGroup.check(R.id.quality_lossless); break;
            case "hires": qualityGroup.check(R.id.quality_hires); break;
            case "jyeffect": qualityGroup.check(R.id.quality_jyeffect); break;
            case "sky": qualityGroup.check(R.id.quality_sky); break;
            case "jymaster": qualityGroup.check(R.id.quality_jymaster); break;
            default: qualityGroup.check(R.id.quality_standard); break;
        }

        qualityGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String quality = "standard";
            if (checkedId == R.id.quality_exhigh) quality = "exhigh";
            else if (checkedId == R.id.quality_lossless) quality = "lossless";
            else if (checkedId == R.id.quality_hires) quality = "hires";
            else if (checkedId == R.id.quality_jyeffect) quality = "jyeffect";
            else if (checkedId == R.id.quality_sky) quality = "sky";
            else if (checkedId == R.id.quality_jymaster) quality = "jymaster";
            settingsManager.setQuality(quality);
            notifySettingsChanged();
        });

        // Language Spinner
        String currentLanguage = settingsManager.getAppLanguage();
        String[] languageOptions = getResources().getStringArray(R.array.language_options);
        int selection = 0; // Default to System Default
        if (currentLanguage.equals("en")) {
            selection = 1;
        } else if (currentLanguage.equals("zh")) {
            selection = 2;
        }
        spinnerLanguage.setSelection(selection);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLanguageCode;
                switch (position) {
                    case 0: selectedLanguageCode = "system"; break;
                    case 1: selectedLanguageCode = "en"; break;
                    case 2: selectedLanguageCode = "zh"; break;
                    default: selectedLanguageCode = "system"; break;
                }
                if (!settingsManager.getAppLanguage().equals(selectedLanguageCode)) {
                    settingsManager.setAppLanguage(selectedLanguageCode);
                    notifySettingsChanged();
                    // Trigger app locale change
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).setAppLocale(selectedLanguageCode);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        switchTranslationIntegration.setChecked(settingsManager.isTranslationIntegrationEnabled());
        switchTranslationIntegration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setTranslationIntegrationEnabled(isChecked);
            notifySettingsChanged();
        });

        int initialVolume = settingsManager.getAppVolume();
        seekbarAppVolume.setProgress(initialVolume);
        textAppVolumeValue.setText(initialVolume + "%");
        seekbarAppVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textAppVolumeValue.setText(progress + "%");
                if (!fromUser) {
                    return;
                }
                settingsManager.setAppVolume(progress);
                notifySettingsChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        textAppVolumeValue.setOnClickListener(v -> {
            int defaultVolume = SettingsManager.DEFAULT_APP_VOLUME;
            if (seekbarAppVolume.getProgress() == defaultVolume) {
                return;
            }
            seekbarAppVolume.setProgress(defaultVolume);
            textAppVolumeValue.setText(defaultVolume + "%");
            settingsManager.setAppVolume(defaultVolume);
            notifySettingsChanged();
        });

        // Floating Window Init
        boolean isFloatingEnabled = settingsManager.isFloatingLyricsEnabled();
        switchFloatingLyrics.setChecked(isFloatingEnabled);
        layoutFloatingSettings.setVisibility(isFloatingEnabled ? View.VISIBLE : View.GONE);

        tempColor = settingsManager.getLyricColor();
        if (tempColor == 0) tempColor = getResources().getColor(R.color.lyrics_color_blue, null);
        updateColorSelection();

        tempSize = settingsManager.getLyricSize();
        textFontSize.setText(String.valueOf((int)tempSize));
        if (textLyricPreviewCurrent != null) {
            textLyricPreviewCurrent.setTextSize(tempSize);
        }
        if (textLyricPreviewNext != null) {
            textLyricPreviewNext.setTextSize(Math.max(10f, tempSize - 2f));
        }

        switchFloatingLyrics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!Settings.canDrawOverlays(requireContext())) {
                    Toast.makeText(requireContext(), R.string.hint_grant_overlay, Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                    buttonView.setChecked(false);
                    return;
                }
            }
            settingsManager.setFloatingLyricsEnabled(isChecked);
            layoutFloatingSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            notifySettingsChanged();
        });

        // Color buttons
        btnColorRed.setOnClickListener(v -> {
            tempColor = getResources().getColor(R.color.lyrics_color_red, null);
            updateColorSelection();
            settingsManager.setLyricColor(tempColor);
            notifySettingsChanged();
        });
        btnColorBlue.setOnClickListener(v -> {
            tempColor = getResources().getColor(R.color.lyrics_color_blue, null);
            updateColorSelection();
            settingsManager.setLyricColor(tempColor);
            notifySettingsChanged();
        });
        btnColorGreen.setOnClickListener(v -> {
            tempColor = getResources().getColor(R.color.lyrics_color_green, null);
            updateColorSelection();
            settingsManager.setLyricColor(tempColor);
            notifySettingsChanged();
        });
        btnColorYellow.setOnClickListener(v -> {
            tempColor = getResources().getColor(R.color.lyrics_color_yellow, null);
            updateColorSelection();
            settingsManager.setLyricColor(tempColor);
            notifySettingsChanged();
        });
        btnColorPurple.setOnClickListener(v -> {
            tempColor = getResources().getColor(R.color.lyrics_color_purple, null);
            updateColorSelection();
            settingsManager.setLyricColor(tempColor);
            notifySettingsChanged();
        });

        // Size buttons
        btnSizePlus.setOnClickListener(v -> {
            tempSize = Math.min(30f, tempSize + 2);
            textFontSize.setText(String.valueOf((int)tempSize));
            if (textLyricPreviewCurrent != null) textLyricPreviewCurrent.setTextSize(tempSize);
            if (textLyricPreviewNext != null) textLyricPreviewNext.setTextSize(Math.max(10f, tempSize - 2f));
            settingsManager.setLyricSize(tempSize);
            notifySettingsChanged();
        });
        btnSizeMinus.setOnClickListener(v -> {
            tempSize = Math.max(10f, tempSize - 2);
            textFontSize.setText(String.valueOf((int)tempSize));
            if (textLyricPreviewCurrent != null) textLyricPreviewCurrent.setTextSize(tempSize);
            if (textLyricPreviewNext != null) textLyricPreviewNext.setTextSize(Math.max(10f, tempSize - 2f));
            settingsManager.setLyricSize(tempSize);
            notifySettingsChanged();
        });

        refreshDownloadCustomizeSummary();
    }

    @Override
    public void onDestroyView() {
        if (settingsManager != null && preferenceChangeListener != null) {
            settingsManager.getPrefs().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        super.onDestroyView();
    }

    private void setupInputListeners() {
        // MUSIC_U Cookie
        inputMusicU.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                scheduleSave(() -> settingsManager.setMusicU(s.toString().trim()));
            }
        });
        inputMusicU.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                saveAndClearFocus(inputMusicU);
                return true;
            }
            return false;
        });
        inputMusicU.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveAndClearFocus(inputMusicU);
            }
        });

        // Search Result Limit
        inputSearchLimit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                scheduleSave(() -> validateAndSaveSearchLimit(s.toString().trim(), false));
            }
        });
        inputSearchLimit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                saveAndClearFocus(inputSearchLimit);
                return true;
            }
            return false;
        });
        inputSearchLimit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveAndClearFocus(inputSearchLimit);
            }
        });
    }

    private void scheduleSave(Runnable saveTask) {
        if (saveRunnable != null) {
            debounceHandler.removeCallbacks(saveRunnable);
        }
        saveRunnable = () -> {
            saveTask.run();
            notifySettingsChanged();
        };
        debounceHandler.postDelayed(saveRunnable, 300); // 300ms debounce
    }

    private void saveAndClearFocus(EditText editText) {
        if (saveRunnable != null) {
            debounceHandler.removeCallbacks(saveRunnable);
            saveRunnable.run();
            saveRunnable = null;
        }
        if (editText == inputSearchLimit) {
            validateAndSaveSearchLimit(editText.getText().toString().trim(), true);
        } else if (editText == inputMusicU) {
            settingsManager.setMusicU(editText.getText().toString().trim());
        }
        notifySettingsChanged();
        hideKeyboard(editText);
        editText.clearFocus();
    }

    private void validateAndSaveSearchLimit(String limitStr, boolean updateUI) {
        if (limitStr.isEmpty()) {
            int defaultLimit = 10;
            settingsManager.setSearchLimit(defaultLimit);
            if (updateUI) {
                inputSearchLimit.setText(String.valueOf(defaultLimit));
            }
            return;
        }
        try {
            int limit = Integer.parseInt(limitStr);
            boolean changed = false;
            if (limit < 1) { limit = 1; changed = true; }
            if (limit > 100) { limit = 100; changed = true; }
            settingsManager.setSearchLimit(limit);
            if (updateUI && changed) {
                inputSearchLimit.setText(String.valueOf(limit));
            }
        } catch (NumberFormatException ignored) {}
    }

    private void notifySettingsChanged() {
        if (getActivity() == null) return;
        Intent intent = new Intent(requireContext(), MusicService.class);
        intent.setAction("ACTION_UPDATE_SETTINGS");
        requireContext().startService(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSettingsUI();
        if (switchFloatingLyrics != null && switchFloatingLyrics.isChecked()) {
            if (!Settings.canDrawOverlays(requireContext())) {
                switchFloatingLyrics.setChecked(false);
            }
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshSettingsUI();
        }
    }

    private void refreshSettingsUI() {
        if (settingsManager == null) return;

        // Refresh values from SharedPreferences in case they were changed elsewhere (e.g. Floating Window)
        inputMusicU.setText(settingsManager.getMusicU());
        inputSearchLimit.setText(String.valueOf(settingsManager.getSearchLimit()));
        int appVolume = settingsManager.getAppVolume();
        seekbarAppVolume.setProgress(appVolume);
        textAppVolumeValue.setText(appVolume + "%");

        String currentQuality = settingsManager.getQuality();
        switch (currentQuality) {
            case "standard": qualityGroup.check(R.id.quality_standard); break;
            case "exhigh": qualityGroup.check(R.id.quality_exhigh); break;
            case "lossless": qualityGroup.check(R.id.quality_lossless); break;
            case "hires": qualityGroup.check(R.id.quality_hires); break;
            case "jyeffect": qualityGroup.check(R.id.quality_jyeffect); break;
            case "sky": qualityGroup.check(R.id.quality_sky); break;
            case "jymaster": qualityGroup.check(R.id.quality_jymaster); break;
            default: qualityGroup.check(R.id.quality_standard); break;
        }

        boolean isFloatingEnabled = settingsManager.isFloatingLyricsEnabled();
        boolean isTranslationEnabled = settingsManager.isTranslationIntegrationEnabled();
        // Avoid triggering listeners if value is same
        switchFloatingLyrics.setOnCheckedChangeListener(null);
        switchFloatingLyrics.setChecked(isFloatingEnabled);
        switchFloatingLyrics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!Settings.canDrawOverlays(requireContext())) {
                    Toast.makeText(requireContext(), R.string.hint_grant_overlay, Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                    buttonView.setChecked(false);
                    return;
                }
            }
            settingsManager.setFloatingLyricsEnabled(isChecked);
            layoutFloatingSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            notifySettingsChanged();
        });

        switchTranslationIntegration.setOnCheckedChangeListener(null);
        switchTranslationIntegration.setChecked(isTranslationEnabled);
        switchTranslationIntegration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setTranslationIntegrationEnabled(isChecked);
            notifySettingsChanged();
        });

        layoutFloatingSettings.setVisibility(isFloatingEnabled ? View.VISIBLE : View.GONE);

        tempColor = settingsManager.getLyricColor();
        if (tempColor == 0) tempColor = getResources().getColor(R.color.lyrics_color_blue, null);
        updateColorSelection();

        tempSize = settingsManager.getLyricSize();
        textFontSize.setText(String.valueOf((int)tempSize));
        refreshDownloadCustomizeSummary();
    }

    private void refreshDownloadCustomizeSummary() {
        if (textDownloadCustomizeSummary == null || settingsManager == null) {
            return;
        }
        DownloadCustomizationSettings settings = settingsManager.getDownloadCustomizationSettings();
        Song previewSong = new Song("0", "Example Song", "Example Artist", "Example Album", "");
        String previewName = DownloadFileUtils.buildDisplayName(
                previewSong,
                DownloadFileUtils.getAudioExtensionForQuality(settingsManager.getQuality()),
                settings);
        String metadataState = settings.metadataEnabled
                ? getString(R.string.download_customize_metadata_on)
                : getString(R.string.download_customize_metadata_off);
        textDownloadCustomizeSummary.setText(getString(R.string.download_customize_summary, previewName, metadataState));
    }

    private void updateColorSelection() {
        btnColorRed.setAlpha(0.3f);
        btnColorBlue.setAlpha(0.3f);
        btnColorGreen.setAlpha(0.3f);
        btnColorYellow.setAlpha(0.3f);
        btnColorPurple.setAlpha(0.3f);
        btnColorRed.setText("");
        btnColorBlue.setText("");
        btnColorGreen.setText("");
        btnColorYellow.setText("");
        btnColorPurple.setText("");

        int finalColor = tempColor;
        if (tempColor == getResources().getColor(R.color.lyrics_color_red, null)) {
            btnColorRed.setAlpha(1.0f);
            btnColorRed.setText("✓");
            btnColorRed.setTextColor(Color.WHITE);
        } else if (tempColor == getResources().getColor(R.color.lyrics_color_blue, null)) {
            btnColorBlue.setAlpha(1.0f);
            btnColorBlue.setText("✓");
            btnColorBlue.setTextColor(Color.WHITE);
        } else if (tempColor == getResources().getColor(R.color.lyrics_color_green, null)) {
            btnColorGreen.setAlpha(1.0f);
            btnColorGreen.setText("✓");
            btnColorGreen.setTextColor(Color.WHITE);
        } else if (tempColor == getResources().getColor(R.color.lyrics_color_yellow, null)) {
            btnColorYellow.setAlpha(1.0f);
            btnColorYellow.setText("✓");
            btnColorYellow.setTextColor(Color.BLACK); // Yellow needs black text
        } else if (tempColor == getResources().getColor(R.color.lyrics_color_purple, null)) {
            btnColorPurple.setAlpha(1.0f);
            btnColorPurple.setText("✓");
            btnColorPurple.setTextColor(Color.WHITE);
        } else {
            finalColor = getResources().getColor(R.color.lyrics_color_blue, null); // Fallback for preview
        }

        if (textLyricPreviewCurrent != null) {
            textLyricPreviewCurrent.setTextColor(finalColor);
        }
    }

    private void hideKeyboard(View view) {
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }
}
