package com.midairlogn.mlnetease.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.Toast;
import android.content.SharedPreferences;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.midairlogn.mlnetease.download.DownloadCustomizationActivity;
import com.midairlogn.mlnetease.BuildConfig;
import com.midairlogn.mlnetease.download.settings.DownloadCustomizationSettings;
import com.midairlogn.mlnetease.download.file.DownloadFileUtils;
import com.midairlogn.mlnetease.hearing.HearingProtectionController;
import com.midairlogn.mlnetease.playback.core.MusicPlayerManager;
import com.midairlogn.mlnetease.playback.core.MusicService;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.MainActivity;
import com.midairlogn.mlnetease.shared.model.Song;
import com.midairlogn.mlnetease.shared.ui.UiLaunchGuards;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.crypto.AEADBadTagException;

public class SettingsFragment extends Fragment {

    private static final String BACKUP_FILE_EXTENSION = ".mlns";
    private static final long APP_VOLUME_UPDATE_DEBOUNCE_MS = 80L;

    private SettingsManager settingsManager;
    private EditText inputMusicU;
    private EditText inputSearchLimit;
    private View layoutAudioQuality;
    private TextView textAudioQualityValue;
    private SeekBar seekbarAppVolume;
    private TextView textAppVolumeValue;
    private Switch switchDynamicVolume;
    private Spinner spinnerLanguage;
    private View layoutDownloadCustomize;
    private TextView textDownloadCustomizeSummary;
    private Switch switchHearingProtection;
    private View layoutHearingProtectionSettings;
    private View layoutHearingProtectionListenDuration;
    private View layoutHearingProtectionRestDuration;
    private TextView textHearingProtectionSummary;
    private TextView textHearingProtectionProgress;
    private TextView textHearingProtectionListenDurationValue;
    private TextView textHearingProtectionRestDurationValue;
    private ActivityResultLauncher<String> createSettingsBackupLauncher;
    private ActivityResultLauncher<String[]> importSettingsBackupLauncher;
    private PendingBackupAction pendingBackupAction;
    private boolean lastImportSkippedFloatingLyrics;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final Handler hearingProtectionUiHandler = new Handler(Looper.getMainLooper());
    private Runnable musicUSaveRunnable;
    private Runnable searchLimitSaveRunnable;
    private Runnable appVolumeUpdateRunnable;
    private final Runnable hearingProtectionUiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || getView() == null || settingsManager == null) {
                return;
            }
            refreshHearingProtectionSummary();
            scheduleHearingProtectionUiRefresh();
        }
    };

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
    private boolean isUpdatingLanguageSpinner;
    private boolean isRefreshingSettingsUi;
    private AlertDialog activeDialog;

    private int tempColor = 0;
    private float tempSize = 16f;

    private static class PendingBackupAction {
        final boolean export;
        final String password;
        final Uri fileUri;

        PendingBackupAction(boolean export, String password, @Nullable Uri fileUri) {
            this.export = export;
            this.password = password;
            this.fileUri = fileUri;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createSettingsBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                this::handleExportDestinationSelected
        );
        importSettingsBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleImportSourceSelected
        );
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

        layoutAudioQuality = view.findViewById(R.id.layout_audio_quality);
        textAudioQualityValue = view.findViewById(R.id.text_audio_quality_value);
        seekbarAppVolume = view.findViewById(R.id.seekbar_app_volume);
        textAppVolumeValue = view.findViewById(R.id.text_app_volume_value);
        switchDynamicVolume = view.findViewById(R.id.switch_dynamic_volume);
        spinnerLanguage = view.findViewById(R.id.spinner_language);
        layoutDownloadCustomize = view.findViewById(R.id.layout_download_customize);
        textDownloadCustomizeSummary = view.findViewById(R.id.text_download_customize_summary);
        switchHearingProtection = view.findViewById(R.id.switch_hearing_protection);
        layoutHearingProtectionSettings = view.findViewById(R.id.layout_hearing_protection_settings);
        layoutHearingProtectionListenDuration = view.findViewById(R.id.layout_hearing_protection_listen_duration);
        layoutHearingProtectionRestDuration = view.findViewById(R.id.layout_hearing_protection_rest_duration);
        textHearingProtectionSummary = view.findViewById(R.id.text_hearing_protection_summary);
        textHearingProtectionProgress = view.findViewById(R.id.text_hearing_protection_progress);
        textHearingProtectionListenDurationValue = view.findViewById(R.id.text_hearing_protection_listen_duration_value);
        textHearingProtectionRestDurationValue = view.findViewById(R.id.text_hearing_protection_rest_duration_value);
        View btnSettingsBackup = view.findViewById(R.id.btn_settings_backup);
        layoutAudioQuality.setOnTouchListener(hideKeyboardTouchListener);

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
            getActivity().runOnUiThread(() -> {
                refreshSettingsUI();
                if (settingsManager != null && settingsManager.isHearingProtectionEnabled()) {
                    scheduleHearingProtectionUiRefresh();
                } else {
                    stopHearingProtectionUiRefresh();
                }
            });
        };
        settingsManager.getPrefs().registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        // Input Listeners
        setupInputListeners();
        layoutDownloadCustomize.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), DownloadCustomizationActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });
        btnSettingsBackup.setOnClickListener(v -> showDataBackupActions());

        updateAudioQualitySummary(settingsManager.getQuality());
        layoutAudioQuality.setOnClickListener(v -> showAudioQualityDialog());

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingLanguageSpinner) {
                    return;
                }
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
        updateLanguageSpinnerSelection();

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
                scheduleAppVolumeUpdate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                cancelPendingAppVolumeUpdate();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                settingsManager.setAppVolume(seekBar.getProgress());
                notifyAppVolumeChanged();
            }
        });
        textAppVolumeValue.setOnClickListener(v -> {
            int defaultVolume = SettingsManager.DEFAULT_APP_VOLUME;
            if (seekbarAppVolume.getProgress() == defaultVolume) {
                return;
            }
            seekbarAppVolume.setProgress(defaultVolume);
            textAppVolumeValue.setText(defaultVolume + "%");
            settingsManager.setAppVolume(defaultVolume);
            notifyAppVolumeChanged();
        });

        switchDynamicVolume.setChecked(settingsManager.isDynamicVolumeEnabled());
        switchDynamicVolume.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setDynamicVolumeEnabled(isChecked);
            notifySettingsChanged();
        });

        boolean hearingProtectionEnabled = settingsManager.isHearingProtectionEnabled();
        switchHearingProtection.setChecked(hearingProtectionEnabled);
        if (layoutHearingProtectionSettings != null) {
            layoutHearingProtectionSettings.setVisibility(hearingProtectionEnabled ? View.VISIBLE : View.GONE);
        }
        refreshHearingProtectionSummary();
        switchHearingProtection.setOnCheckedChangeListener((buttonView, isChecked) ->
                applyHearingProtectionEnabledState(isChecked));
        if (layoutHearingProtectionListenDuration != null) {
            layoutHearingProtectionListenDuration.setOnClickListener(v -> showHearingProtectionListenDurationDialog());
        }
        if (layoutHearingProtectionRestDuration != null) {
            layoutHearingProtectionRestDuration.setOnClickListener(v -> showHearingProtectionRestDurationDialog());
        }

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
                    AlertDialog dialog = new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.permission_required)
                            .setMessage(R.string.hint_overlay_permission)
                            .setPositiveButton(R.string.go_to_settings, (dialogInterface, which) -> {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + requireContext().getPackageName()));
                                startActivity(intent);
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .create();
                    showManagedDialog(dialog);
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
        cancelPendingSave();
        stopHearingProtectionUiRefresh();
        if (settingsManager != null && preferenceChangeListener != null) {
            settingsManager.getPrefs().unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        inputMusicU = null;
        inputSearchLimit = null;
        layoutAudioQuality = null;
        textAudioQualityValue = null;
        seekbarAppVolume = null;
        textAppVolumeValue = null;
        spinnerLanguage = null;
        layoutDownloadCustomize = null;
        textDownloadCustomizeSummary = null;
        switchHearingProtection = null;
        layoutHearingProtectionSettings = null;
        layoutHearingProtectionListenDuration = null;
        layoutHearingProtectionRestDuration = null;
        textHearingProtectionSummary = null;
        textHearingProtectionProgress = null;
        textHearingProtectionListenDurationValue = null;
        textHearingProtectionRestDurationValue = null;
        switchFloatingLyrics = null;
        switchTranslationIntegration = null;
        layoutFloatingSettings = null;
        btnColorRed = null;
        btnColorBlue = null;
        btnColorGreen = null;
        btnColorYellow = null;
        btnColorPurple = null;
        textFontSize = null;
        btnSizePlus = null;
        btnSizeMinus = null;
        textLyricPreviewCurrent = null;
        textLyricPreviewNext = null;
        dismissActiveDialog();
        super.onDestroyView();
    }

    private void dismissActiveDialog() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = null;
    }

    private void showManagedDialog(@NonNull AlertDialog dialog) {
        if (!UiLaunchGuards.showAlertDialogOnce(activeDialog, dialog)) {
            return;
        }
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == dialog) {
                activeDialog = null;
            }
        });
    }

    private void setupInputListeners() {
        // MUSIC_U Cookie
        inputMusicU.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (isRefreshingSettingsUi) {
                    return;
                }
                scheduleMusicUSave(s.toString().trim());
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
                if (isRefreshingSettingsUi) {
                    return;
                }
                scheduleSearchLimitSave(s.toString().trim());
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

    private void scheduleMusicUSave(String musicU) {
        if (musicUSaveRunnable != null) {
            debounceHandler.removeCallbacks(musicUSaveRunnable);
        }
        musicUSaveRunnable = () -> {
            settingsManager.setMusicU(musicU);
            notifySettingsChanged();
        };
        debounceHandler.postDelayed(musicUSaveRunnable, 300); // 300ms debounce
    }

    private void scheduleSearchLimitSave(String limitStr) {
        if (searchLimitSaveRunnable != null) {
            debounceHandler.removeCallbacks(searchLimitSaveRunnable);
        }
        searchLimitSaveRunnable = () -> {
            if (saveSearchLimitDraft(limitStr)) {
                notifySettingsChanged();
            }
        };
        debounceHandler.postDelayed(searchLimitSaveRunnable, 300); // 300ms debounce
    }

    private void cancelPendingSave() {
        if (musicUSaveRunnable != null) {
            debounceHandler.removeCallbacks(musicUSaveRunnable);
            musicUSaveRunnable = null;
        }
        if (searchLimitSaveRunnable != null) {
            debounceHandler.removeCallbacks(searchLimitSaveRunnable);
            searchLimitSaveRunnable = null;
        }
        cancelPendingAppVolumeUpdate();
        debounceHandler.removeCallbacksAndMessages(null);
    }

    private void saveAndClearFocus(EditText editText) {
        if (editText == inputSearchLimit) {
            if (searchLimitSaveRunnable != null) {
                debounceHandler.removeCallbacks(searchLimitSaveRunnable);
                searchLimitSaveRunnable = null;
            }
            if (validateAndSaveSearchLimit(editText.getText().toString().trim(), true)) {
                notifySettingsChanged();
            }
        } else if (editText == inputMusicU) {
            if (musicUSaveRunnable != null) {
                debounceHandler.removeCallbacks(musicUSaveRunnable);
                musicUSaveRunnable = null;
            }
            settingsManager.setMusicU(editText.getText().toString().trim());
            notifySettingsChanged();
        }
        hideKeyboard(editText);
        editText.clearFocus();
    }

    private boolean saveSearchLimitDraft(String limitStr) {
        if (limitStr.isEmpty()) {
            return false;
        }
        try {
            int limit = Integer.parseInt(limitStr);
            if (limit < 1 || limit > 100) {
                return false;
            }
            settingsManager.setSearchLimit(limit);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean validateAndSaveSearchLimit(String limitStr, boolean finalizeEdit) {
        if (limitStr.isEmpty()) {
            if (!finalizeEdit) {
                return false;
            }
            int defaultLimit = 10;
            settingsManager.setSearchLimit(defaultLimit);
            if (inputSearchLimit != null) {
                inputSearchLimit.setText(String.valueOf(defaultLimit));
            }
            return true;
        }
        try {
            int limit = Integer.parseInt(limitStr);
            boolean changed = false;
            if (limit < 1) { limit = 1; changed = true; }
            if (limit > 100) { limit = 100; changed = true; }
            settingsManager.setSearchLimit(limit);
            if (finalizeEdit && changed && inputSearchLimit != null) {
                inputSearchLimit.setText(String.valueOf(limit));
            }
            return true;
        } catch (NumberFormatException ignored) {
            if (finalizeEdit && inputSearchLimit != null) {
                inputSearchLimit.setText(String.valueOf(settingsManager.getSearchLimit()));
            }
            return false;
        }
    }

    private void notifySettingsChanged() {
        if (getActivity() == null) return;
        Intent intent = new Intent(requireContext(), MusicService.class);
        intent.setAction("ACTION_UPDATE_SETTINGS");
        requireContext().startService(intent);
    }

    private void scheduleAppVolumeUpdate() {
        cancelPendingAppVolumeUpdate();
        appVolumeUpdateRunnable = this::notifyAppVolumeChanged;
        debounceHandler.postDelayed(appVolumeUpdateRunnable, APP_VOLUME_UPDATE_DEBOUNCE_MS);
    }

    private void cancelPendingAppVolumeUpdate() {
        if (appVolumeUpdateRunnable != null) {
            debounceHandler.removeCallbacks(appVolumeUpdateRunnable);
            appVolumeUpdateRunnable = null;
        }
    }

    private void notifyAppVolumeChanged() {
        if (getActivity() == null) return;
        cancelPendingAppVolumeUpdate();
        Intent intent = new Intent(requireContext(), MusicService.class);
        intent.setAction(MusicService.ACTION_UPDATE_APP_VOLUME);
        requireContext().startService(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSettingsUI();
        scheduleHearingProtectionUiRefresh();
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
            scheduleHearingProtectionUiRefresh();
        } else {
            stopHearingProtectionUiRefresh();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        flushPendingSaves();
        stopHearingProtectionUiRefresh();
    }

    private void flushPendingSaves() {
        if (appVolumeUpdateRunnable != null) {
            notifyAppVolumeChanged();
        }
        if (musicUSaveRunnable != null) {
            debounceHandler.removeCallbacks(musicUSaveRunnable);
            musicUSaveRunnable.run();
            musicUSaveRunnable = null;
        }
        if (searchLimitSaveRunnable != null) {
            debounceHandler.removeCallbacks(searchLimitSaveRunnable);
            searchLimitSaveRunnable = null;
            if (inputSearchLimit != null) {
                if (validateAndSaveSearchLimit(inputSearchLimit.getText().toString().trim(), true)) {
                    notifySettingsChanged();
                }
            }
        }
    }

    private void refreshSettingsUI() {
        if (settingsManager == null || getView() == null) return;

        isRefreshingSettingsUi = true;
        try {
            // Refresh values from SharedPreferences in case they were changed elsewhere (e.g. Floating Window)
            if (inputMusicU != null && !inputMusicU.hasFocus()) {
                String musicU = settingsManager.getMusicU();
                if (!musicU.equals(inputMusicU.getText().toString())) {
                    inputMusicU.setText(musicU);
                }
            }
            if (inputSearchLimit != null && !inputSearchLimit.hasFocus()) {
                String searchLimit = String.valueOf(settingsManager.getSearchLimit());
                if (!searchLimit.equals(inputSearchLimit.getText().toString())) {
                    inputSearchLimit.setText(searchLimit);
                }
            }
            int appVolume = settingsManager.getAppVolume();
            if (seekbarAppVolume != null && seekbarAppVolume.getProgress() != appVolume) {
                seekbarAppVolume.setProgress(appVolume);
            }
            if (textAppVolumeValue != null) {
                textAppVolumeValue.setText(appVolume + "%");
            }
            boolean dynamicVolumeEnabled = settingsManager.isDynamicVolumeEnabled();
            if (switchDynamicVolume != null) {
                switchDynamicVolume.setOnCheckedChangeListener(null);
                switchDynamicVolume.setChecked(dynamicVolumeEnabled);
                switchDynamicVolume.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    settingsManager.setDynamicVolumeEnabled(isChecked);
                    notifySettingsChanged();
                });
            }
            updateLanguageSpinnerSelection();

            String currentQuality = settingsManager.getQuality();
            updateAudioQualitySummary(currentQuality);

            boolean hearingProtectionEnabled = settingsManager.isHearingProtectionEnabled();
            if (switchHearingProtection != null) {
                switchHearingProtection.setOnCheckedChangeListener(null);
                switchHearingProtection.setChecked(hearingProtectionEnabled);
                switchHearingProtection.setOnCheckedChangeListener((buttonView, isChecked) ->
                        applyHearingProtectionEnabledState(isChecked));
            }
            if (layoutHearingProtectionSettings != null) {
                layoutHearingProtectionSettings.setVisibility(hearingProtectionEnabled ? View.VISIBLE : View.GONE);
            }
            refreshHearingProtectionSummary();

            boolean isFloatingEnabled = settingsManager.isFloatingLyricsEnabled();
            boolean isTranslationEnabled = settingsManager.isTranslationIntegrationEnabled();
            // Avoid triggering listeners if value is same
            if (switchFloatingLyrics != null) {
                switchFloatingLyrics.setOnCheckedChangeListener(null);
                switchFloatingLyrics.setChecked(isFloatingEnabled);
                switchFloatingLyrics.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        if (!Settings.canDrawOverlays(requireContext())) {
                            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.permission_required)
                                    .setMessage(R.string.hint_overlay_permission)
                                    .setPositiveButton(R.string.go_to_settings, (dialogInterface, which) -> {
                                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:" + requireContext().getPackageName()));
                                        startActivity(intent);
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .create();
                            showManagedDialog(dialog);
                            buttonView.setChecked(false);
                            return;
                        }
                    }
                    settingsManager.setFloatingLyricsEnabled(isChecked);
                    layoutFloatingSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    notifySettingsChanged();
                });
            }

            if (switchTranslationIntegration != null) {
                switchTranslationIntegration.setOnCheckedChangeListener(null);
                switchTranslationIntegration.setChecked(isTranslationEnabled);
                switchTranslationIntegration.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    settingsManager.setTranslationIntegrationEnabled(isChecked);
                    notifySettingsChanged();
                });
            }

            if (layoutFloatingSettings != null) {
                layoutFloatingSettings.setVisibility(isFloatingEnabled ? View.VISIBLE : View.GONE);
            }

            tempColor = settingsManager.getLyricColor();
            if (tempColor == 0) tempColor = getResources().getColor(R.color.lyrics_color_blue, null);
            updateColorSelection();

            tempSize = settingsManager.getLyricSize();
            if (textFontSize != null) {
                textFontSize.setText(String.valueOf((int) tempSize));
            }
            if (textLyricPreviewCurrent != null) {
                textLyricPreviewCurrent.setTextSize(tempSize);
            }
            if (textLyricPreviewNext != null) {
                textLyricPreviewNext.setTextSize(Math.max(10f, tempSize - 2f));
            }
            refreshDownloadCustomizeSummary();
        } finally {
            isRefreshingSettingsUi = false;
        }
    }

    private void updateLanguageSpinnerSelection() {
        if (spinnerLanguage == null || settingsManager == null) {
            return;
        }
        String currentLanguage = settingsManager.getAppLanguage();
        int selection = 0;
        if ("en".equals(currentLanguage)) {
            selection = 1;
        } else if ("zh".equals(currentLanguage)) {
            selection = 2;
        }
        if (spinnerLanguage.getSelectedItemPosition() == selection) {
            return;
        }
        isUpdatingLanguageSpinner = true;
        spinnerLanguage.setSelection(selection, false);
        isUpdatingLanguageSpinner = false;
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

    private void refreshHearingProtectionSummary() {
        if (settingsManager == null) {
            return;
        }
        int listenMinutes = settingsManager.getHearingProtectionListenMinutes();
        int restMinutes = settingsManager.getHearingProtectionRestMinutes();
        String listenLabel = formatMinutes(listenMinutes);
        String restLabel = formatMinutes(restMinutes);
        if (textHearingProtectionListenDurationValue != null) {
            textHearingProtectionListenDurationValue.setText(listenLabel);
        }
        if (textHearingProtectionRestDurationValue != null) {
            textHearingProtectionRestDurationValue.setText(restLabel);
        }
        if (textHearingProtectionSummary != null) {
            textHearingProtectionSummary.setText(getString(
                    R.string.hearing_protection_schedule_summary,
                    restLabel,
                    listenLabel
            ));
        }
        if (textHearingProtectionProgress != null) {
            HearingProtectionController.HearingProtectionSnapshot snapshot =
                    HearingProtectionController.getSnapshot(requireContext());
            if (snapshot.restActive) {
                textHearingProtectionProgress.setText(getString(
                        R.string.hearing_protection_rest_remaining_summary,
                        formatRestRemaining(snapshot.restRemainingMs)
                ));
            } else {
                long currentDoseMs = Math.max(0L, snapshot.getDisplayDoseMs());
                double currentMinutes = currentDoseMs / 60_000d;
                textHearingProtectionProgress.setText(getString(
                        R.string.hearing_protection_progress_summary,
                        formatWeightedMinutes(currentMinutes),
                        listenLabel
                ));
            }
            textHearingProtectionProgress.setVisibility(
                    settingsManager.isHearingProtectionEnabled() ? View.VISIBLE : View.GONE
            );
        }
    }

    private void applyHearingProtectionEnabledState(boolean enabled) {
        settingsManager.setHearingProtectionEnabled(enabled);
        if (layoutHearingProtectionSettings != null) {
            layoutHearingProtectionSettings.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        refreshHearingProtectionSummary();
        if (enabled) {
            scheduleHearingProtectionUiRefresh();
            showHearingProtectionBackgroundPromptIfNeeded();
        } else {
            stopHearingProtectionUiRefresh();
        }
        notifySettingsChanged();
    }

    private void showHearingProtectionBackgroundPromptIfNeeded() {
        if (!isAdded() || settingsManager == null
                || settingsManager.isHearingProtectionBackgroundPromptDismissed()) {
            return;
        }

        Context context = requireContext();
        CheckBox dontShowAgain = new CheckBox(context);
        dontShowAgain.setText(R.string.dont_show_again);
        dontShowAgain.setTextColor(getResources().getColor(R.color.text_primary, null));
        dontShowAgain.setPadding(0, dpToPx(8), 0, 0);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0);

        TextView message = new TextView(context);
        message.setText(R.string.hearing_protection_background_prompt_message);
        message.setTextColor(getResources().getColor(R.color.text_primary, null));
        message.setTextSize(14f);
        container.addView(message);
        container.addView(dontShowAgain);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.hearing_protection_background_prompt_title)
                .setView(container)
                .setPositiveButton(R.string.go_to_settings, (dialogInterface, which) -> {
                    if (dontShowAgain.isChecked()) {
                        settingsManager.setHearingProtectionBackgroundPromptDismissed(true);
                    }
                    openAppSettings();
                })
                .setNegativeButton(R.string.cancel, (dialogInterface, which) -> {
                    if (dontShowAgain.isChecked()) {
                        settingsManager.setHearingProtectionBackgroundPromptDismissed(true);
                    }
                })
                .create();
        showManagedDialog(dialog);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(intent);
    }

    private void showHearingProtectionListenDurationDialog() {
        showMinuteChoiceDialog(
                R.string.hearing_protection_listen_dialog_title,
                new int[] {30, 45, 60, 90, 120, 150, 180},
                settingsManager.getHearingProtectionListenMinutes(),
                15,
                240,
                selectedMinutes -> {
                    if (selectedMinutes != settingsManager.getHearingProtectionListenMinutes()) {
                        settingsManager.setHearingProtectionListenMinutes(selectedMinutes);
                        refreshHearingProtectionSummary();
                        notifySettingsChanged();
                    }
                }
        );
    }

    private void showHearingProtectionRestDurationDialog() {
        showMinuteChoiceDialog(
                R.string.hearing_protection_rest_dialog_title,
                new int[] {5, 10, 15, 20, 30, 45, 60},
                settingsManager.getHearingProtectionRestMinutes(),
                5,
                60,
                selectedMinutes -> {
                    if (selectedMinutes != settingsManager.getHearingProtectionRestMinutes()) {
                        settingsManager.setHearingProtectionRestMinutes(selectedMinutes);
                        refreshHearingProtectionSummary();
                        notifySettingsChanged();
                    }
                }
        );
    }

    private void showMinuteChoiceDialog(int titleResId, int[] options, int currentValue, int minValue, int maxValue, MinuteChoiceListener listener) {
        if (options == null || options.length == 0 || listener == null) {
            return;
        }
        Context context = requireContext();
        String[] labels = new String[options.length + 1];
        int selectedIndex = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = formatMinutes(options[i]);
            if (options[i] == currentValue) {
                selectedIndex = i;
            }
        }
        labels[options.length] = getString(R.string.hearing_protection_custom_option);
        boolean isCustomValue = true;
        for (int option : options) {
            if (option == currentValue) {
                isCustomValue = false;
                break;
            }
        }
        if (isCustomValue) {
            selectedIndex = options.length;
        }
        final boolean currentValueIsCustom = isCustomValue;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), 0);

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(LinearLayout.VERTICAL);
        container.addView(radioGroup);

        for (int i = 0; i < labels.length; i++) {
            RadioButton radioButton = new RadioButton(context);
            radioButton.setLayoutParams(new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            radioButton.setText(labels[i]);
            radioButton.setTextColor(getResources().getColor(R.color.text_primary, null));
            radioButton.setButtonTintList(getResources().getColorStateList(R.color.brand_primary, null));
            radioButton.setPadding(0, dpToPx(8), 0, dpToPx(8));
            radioButton.setId(View.generateViewId());
            radioGroup.addView(radioButton);
            if (i == selectedIndex) {
                radioGroup.check(radioButton.getId());
            }
        }

        EditText customInput = new EditText(context);
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        customInput.setHint(getString(R.string.hearing_protection_custom_minutes_hint));
        customInput.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        customInput.setSelectAllOnFocus(true);
        if (isCustomValue) {
            customInput.setText(String.valueOf(currentValue));
        }
        container.addView(customInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] checkedIndex = {selectedIndex};
        updateCustomMinuteInputState(customInput, checkedIndex[0] == options.length);
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i).getId() == checkedId) {
                    checkedIndex[0] = i;
                    break;
                }
            }
            boolean customSelected = checkedIndex[0] == options.length;
            updateCustomMinuteInputState(customInput, customSelected);
            if (customSelected && customInput.getText() != null && customInput.getText().length() == 0 && currentValueIsCustom) {
                customInput.setText(String.valueOf(currentValue));
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(titleResId)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selected = checkedIndex[0];
            if (selected >= 0 && selected < options.length) {
                listener.onMinuteSelected(options[selected]);
                dialog.dismiss();
                return;
            }
            String rawValue = customInput.getText() == null ? "" : customInput.getText().toString().trim();
            if (rawValue.isEmpty()) {
                Toast.makeText(context, getString(R.string.hearing_protection_custom_invalid, minValue, maxValue), Toast.LENGTH_SHORT).show();
                return;
            }
            int customMinutes;
            try {
                customMinutes = Integer.parseInt(rawValue);
            } catch (NumberFormatException ignored) {
                Toast.makeText(context, getString(R.string.hearing_protection_custom_invalid, minValue, maxValue), Toast.LENGTH_SHORT).show();
                return;
            }
            if (customMinutes < minValue || customMinutes > maxValue) {
                Toast.makeText(context, getString(R.string.hearing_protection_custom_invalid, minValue, maxValue), Toast.LENGTH_SHORT).show();
                return;
            }
            listener.onMinuteSelected(customMinutes);
            dialog.dismiss();
        }));
        showManagedDialog(dialog);
    }

    private void updateCustomMinuteInputState(EditText customInput, boolean enabled) {
        customInput.setEnabled(enabled);
        customInput.setFocusable(enabled);
        customInput.setFocusableInTouchMode(enabled);
        customInput.setClickable(enabled);
        customInput.setAlpha(enabled ? 1f : 0.5f);
    }

    private void scheduleHearingProtectionUiRefresh() {
        stopHearingProtectionUiRefresh();
        if (!isAdded() || getView() == null || settingsManager == null || isHidden()) {
            return;
        }
        if (!settingsManager.isHearingProtectionEnabled()) {
            return;
        }
        refreshHearingProtectionSummary();
        HearingProtectionController.HearingProtectionSnapshot snapshot =
                HearingProtectionController.getSnapshot(requireContext());
        if (!snapshot.activelyAccumulating && !snapshot.pauseRecoveryActive && !snapshot.restActive) {
            return;
        }
        hearingProtectionUiHandler.postDelayed(hearingProtectionUiRefreshRunnable, 1000L);
    }

    private void stopHearingProtectionUiRefresh() {
        hearingProtectionUiHandler.removeCallbacks(hearingProtectionUiRefreshRunnable);
    }

    private String formatMinutes(int minutes) {
        return getString(R.string.hearing_protection_option_minutes, minutes);
    }

    private String formatWeightedMinutes(double minutes) {
        return getString(R.string.hearing_protection_duration_minutes_decimal, Math.max(0d, minutes));
    }

    private String formatRestRemaining(long remainingMs) {
        long safeRemainingMs = Math.max(0L, remainingMs);
        long totalSeconds = TimeUnit.MILLISECONDS.toSeconds(safeRemainingMs);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return getString(R.string.hearing_protection_rest_remaining_duration, minutes, seconds);
    }

    private interface MinuteChoiceListener {
        void onMinuteSelected(int minutes);
    }

    private void showAudioQualityDialog() {
        Context context = requireContext();
        String currentQuality = settingsManager.getQuality();

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(LinearLayout.VERTICAL);
        radioGroup.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), 0);

        addAudioQualityOption(radioGroup, "standard", R.string.type_audio_quality_standard, currentQuality);
        addAudioQualityOption(radioGroup, "exhigh", R.string.type_audio_quality_exhigh, currentQuality);
        addAudioQualityOption(radioGroup, "lossless", R.string.type_audio_quality_lossless, currentQuality);
        addAudioQualityOption(radioGroup, "hires", R.string.type_audio_quality_hires, currentQuality);
        addAudioQualityOption(radioGroup, "jyeffect", R.string.type_audio_quality_jyeffect, currentQuality);
        addAudioQualityOption(radioGroup, "sky", R.string.type_audio_quality_sky, currentQuality);
        addAudioQualityOption(radioGroup, "jymaster", R.string.type_audio_quality_jymaster, currentQuality);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.title_audio_quality)
                .setView(radioGroup)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialogInterface, which) -> {
                    int checkedId = radioGroup.getCheckedRadioButtonId();
                    View checkedView = radioGroup.findViewById(checkedId);
                    Object tag = checkedView == null ? null : checkedView.getTag();
                    String selectedQuality = tag instanceof String ? (String) tag : SettingsManager.DEFAULT_QUALITY;
                    if (!selectedQuality.equals(settingsManager.getQuality())) {
                        settingsManager.setQuality(selectedQuality);
                        updateAudioQualitySummary(selectedQuality);
                        notifySettingsChanged();
                    }
                })
                .create();
        showManagedDialog(dialog);
    }

    private void addAudioQualityOption(RadioGroup radioGroup, String qualityValue, int labelResId, String currentQuality) {
        RadioButton radioButton = new RadioButton(requireContext());
        radioButton.setLayoutParams(new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        radioButton.setText(labelResId);
        radioButton.setTextColor(getResources().getColor(R.color.text_primary, null));
        radioButton.setButtonTintList(getResources().getColorStateList(R.color.brand_primary, null));
        radioButton.setPadding(0, dpToPx(8), 0, dpToPx(8));
        radioButton.setTag(qualityValue);
        radioButton.setId(View.generateViewId());
        radioGroup.addView(radioButton);
        if (qualityValue.equals(currentQuality)) {
            radioGroup.check(radioButton.getId());
        }
    }

    private void updateAudioQualitySummary(String quality) {
        if (textAudioQualityValue == null) {
            return;
        }
        textAudioQualityValue.setText(getAudioQualityLabel(quality));
    }

    private String getAudioQualityLabel(String quality) {
        int labelResId;
        switch (quality) {
            case "exhigh":
                labelResId = R.string.type_audio_quality_exhigh;
                break;
            case "lossless":
                labelResId = R.string.type_audio_quality_lossless;
                break;
            case "hires":
                labelResId = R.string.type_audio_quality_hires;
                break;
            case "jyeffect":
                labelResId = R.string.type_audio_quality_jyeffect;
                break;
            case "sky":
                labelResId = R.string.type_audio_quality_sky;
                break;
            case "jymaster":
                labelResId = R.string.type_audio_quality_jymaster;
                break;
            case "standard":
            default:
                labelResId = R.string.type_audio_quality_standard;
                break;
        }
        return getString(labelResId);
    }

    private void showDataBackupActions() {
        String[] actions = new String[] {
                getString(R.string.settings_export),
                getString(R.string.settings_import)
        };
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_backup_choose_action)
                .setItems(actions, (dialogInterface, which) -> {
                    dialogInterface.dismiss();
                    if (which == 0) {
                        hearingProtectionUiHandler.post(this::showExportPasswordDialog);
                    } else if (which == 1) {
                        hearingProtectionUiHandler.post(() ->
                                importSettingsBackupLauncher.launch(new String[] {"application/octet-stream", "application/json"}));
                    }
                })
                .create();
        showManagedDialog(dialog);
    }

    private void showExportPasswordDialog() {
        showPasswordDialog(true, null);
    }

    private void showImportPasswordDialog(@NonNull Uri uri) {
        showPasswordDialog(false, uri);
    }

    private void showPasswordDialog(boolean isExport, @Nullable Uri importUri) {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(20), dpToPx(12), dpToPx(20), 0);

        TextView subtitleView = createDialogBodyText(isExport
                ? R.string.settings_export_subtitle
                : R.string.settings_import_subtitle);
        container.addView(subtitleView);

        if (!isExport && importUri != null) {
            TextView fileLabel = createDialogSectionLabel(R.string.settings_selected_file);
            fileLabel.setPadding(0, dpToPx(16), 0, dpToPx(8));
            container.addView(fileLabel);

            TextView fileChip = createDialogValueChip(getDisplayName(importUri));
            container.addView(fileChip);

            TextView importNote = createDialogBodyText(R.string.settings_import_note);
            importNote.setPadding(0, dpToPx(12), 0, 0);
            container.addView(importNote);
        }

        TextView passwordLabel = createDialogSectionLabel(R.string.settings_password);
        passwordLabel.setPadding(0, dpToPx(16), 0, dpToPx(8));
        container.addView(passwordLabel);

        EditText passwordInput = createPasswordInput();
        passwordInput.setHint(getString(R.string.settings_password));
        container.addView(passwordInput);

        EditText confirmInput = null;
        if (isExport) {
            TextView noteView = createDialogBodyText(R.string.settings_export_note);
            noteView.setPadding(0, dpToPx(12), 0, 0);
            container.addView(noteView);

            TextView confirmLabel = createDialogSectionLabel(R.string.settings_confirm_password);
            confirmLabel.setPadding(0, dpToPx(16), 0, dpToPx(8));
            container.addView(confirmLabel);

            confirmInput = createPasswordInput();
            confirmInput.setHint(getString(R.string.settings_confirm_password));
            container.addView(confirmInput);
        }

        EditText finalConfirmInput = confirmInput;
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(isExport ? R.string.settings_export : R.string.settings_import)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(isExport ? R.string.settings_export : R.string.settings_import, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String password = passwordInput.getText().toString();
                    if (password.trim().isEmpty()) {
                        Toast.makeText(requireContext(), R.string.settings_password_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (isExport && finalConfirmInput != null && !Objects.equals(password, finalConfirmInput.getText().toString())) {
                        Toast.makeText(requireContext(), R.string.settings_password_mismatch, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    pendingBackupAction = new PendingBackupAction(isExport, password, importUri);
                    dialog.dismiss();
                    if (isExport) {
                        createSettingsBackupLauncher.launch(getDefaultBackupFilename());
                    } else if (importUri != null) {
                        handleImportSourceSelected(importUri);
                    }
                }));
        showManagedDialog(dialog);
    }

    private TextView createDialogBodyText(int stringResId) {
        TextView textView = new TextView(requireContext());
        textView.setText(stringResId);
        textView.setTextColor(getResources().getColor(R.color.text_secondary, null));
        textView.setTextSize(14f);
        textView.setLineSpacing(0f, 1.15f);
        return textView;
    }

    private TextView createDialogSectionLabel(int stringResId) {
        TextView textView = new TextView(requireContext());
        textView.setText(stringResId);
        textView.setTextColor(getResources().getColor(R.color.text_primary, null));
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setTextSize(14f);
        return textView;
    }

    private TextView createDialogValueChip(String text) {
        TextView textView = new TextView(requireContext());
        textView.setText(text);
        textView.setTextColor(getResources().getColor(R.color.text_primary, null));
        textView.setBackgroundResource(R.drawable.search_background);
        textView.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        return textView;
    }

    private EditText createPasswordInput() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(getResources().getColor(R.color.text_primary, null));
        input.setHintTextColor(getResources().getColor(R.color.text_secondary, null));
        input.setBackgroundResource(R.drawable.search_background);
        input.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        return input;
    }

    private String getReadableFileName(Uri uri) {
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment == null || lastSegment.trim().isEmpty()) {
            return uri.toString();
        }
        int splitIndex = lastSegment.lastIndexOf('/');
        String value = splitIndex >= 0 ? lastSegment.substring(splitIndex + 1) : lastSegment;
        return value.replace(':', '/');
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver().query(
                uri,
                new String[] {OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (columnIndex >= 0) {
                    String displayName = cursor.getString(columnIndex);
                    if (!TextUtils.isEmpty(displayName)) {
                        return displayName;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return getReadableFileName(uri);
    }

    private boolean hasBackupFileExtension(@Nullable String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.US).endsWith(BACKUP_FILE_EXTENSION);
    }

    @NonNull
    private String ensureBackupFileExtension(@NonNull String fileName) {
        return hasBackupFileExtension(fileName) ? fileName : fileName + BACKUP_FILE_EXTENSION;
    }

    @Nullable
    private Uri maybeRenameBackupDocument(@NonNull Uri uri) {
        String displayName = getDisplayName(uri);
        if (hasBackupFileExtension(displayName)) {
            return uri;
        }
        try {
            Uri renamedUri = DocumentsContract.renameDocument(
                    requireContext().getContentResolver(),
                    uri,
                    ensureBackupFileExtension(displayName)
            );
            return renamedUri != null ? renamedUri : uri;
        } catch (Exception ignored) {
            return uri;
        }
    }

    private void handleExportDestinationSelected(Uri uri) {
        PendingBackupAction action = pendingBackupAction;
        pendingBackupAction = null;
        if (uri == null || action == null || !action.export) {
            return;
        }
        try {
            Uri outputUri = maybeRenameBackupDocument(uri);
            if (!hasBackupFileExtension(getDisplayName(outputUri))) {
                Toast.makeText(requireContext(), R.string.settings_backup_extension_required, Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] data = settingsManager.exportEncryptedData(action.password);
            try (OutputStream outputStream = requireContext().getContentResolver().openOutputStream(outputUri, "w")) {
                if (outputStream == null) {
                    throw new IllegalStateException("Output stream unavailable");
                }
                outputStream.write(data);
                outputStream.flush();
            }
            Toast.makeText(requireContext(), R.string.settings_export_success, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.settings_password_required, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.settings_backup_write_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleImportSourceSelected(Uri uri) {
        if (pendingBackupAction == null) {
            if (uri != null) {
                showImportPasswordDialog(uri);
            }
            return;
        }
        PendingBackupAction action = pendingBackupAction;
        pendingBackupAction = null;
        Uri sourceUri = action == null ? uri : (action.fileUri != null ? action.fileUri : uri);
        if (sourceUri == null || action == null || action.export) {
            return;
        }
        try {
            if (!hasBackupFileExtension(getDisplayName(sourceUri))) {
                Toast.makeText(requireContext(), R.string.settings_backup_extension_required, Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] data = readAllBytes(sourceUri);
            lastImportSkippedFloatingLyrics = settingsManager.importEncryptedData(data, action.password);
            MusicPlayerManager.getInstance(requireContext()).reloadPlaybackModeFromSettings();
            refreshSettingsUI();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).setAppLocale(settingsManager.getAppLanguage());
                ((MainActivity) getActivity()).reloadHomeShortcuts();
            }
            notifySettingsChanged();
            Toast.makeText(requireContext(), R.string.settings_import_success, Toast.LENGTH_SHORT).show();
            if (lastImportSkippedFloatingLyrics) {
                Toast.makeText(requireContext(), R.string.hint_grant_overlay, Toast.LENGTH_LONG).show();
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(requireContext(), R.string.settings_backup_invalid_file, Toast.LENGTH_SHORT).show();
        } catch (AEADBadTagException e) {
            Toast.makeText(requireContext(), R.string.settings_backup_wrong_password, Toast.LENGTH_SHORT).show();
        } catch (java.io.IOException e) {
            Toast.makeText(requireContext(), R.string.settings_backup_read_error, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.settings_backup_wrong_password, Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readAllBytes(Uri uri) throws java.io.IOException {
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new java.io.IOException("Input stream unavailable");
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private String getDefaultBackupFilename() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        return getString(R.string.settings_backup_filename, timestamp);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
