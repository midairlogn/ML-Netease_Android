package com.midairlogn.mlnetease;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    private SettingsManager settingsManager;
    private EditText inputMusicU;
    private EditText inputSearchLimit;
    private RadioGroup qualityGroup;
    private Button btnSave;

    // Floating Window
    private Switch switchFloatingLyrics;
    private LinearLayout layoutFloatingSettings;
    private Button btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow, btnColorPurple;
    private TextView textFontSize;
    private Button btnSizePlus, btnSizeMinus;

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

        inputMusicU = view.findViewById(R.id.input_music_u);
        inputSearchLimit = view.findViewById(R.id.input_search_limit);
        qualityGroup = view.findViewById(R.id.quality_group);
        btnSave = view.findViewById(R.id.btn_save_cookie);

        // Floating Window Views
        switchFloatingLyrics = view.findViewById(R.id.switch_floating_lyrics);
        layoutFloatingSettings = view.findViewById(R.id.layout_floating_settings);
        btnColorRed = view.findViewById(R.id.btn_color_red);
        btnColorBlue = view.findViewById(R.id.btn_color_blue);
        btnColorGreen = view.findViewById(R.id.btn_color_green);
        btnColorYellow = view.findViewById(R.id.btn_color_yellow);
        btnColorPurple = view.findViewById(R.id.btn_color_purple);
        textFontSize = view.findViewById(R.id.text_font_size);
        btnSizePlus = view.findViewById(R.id.btn_size_plus);
        btnSizeMinus = view.findViewById(R.id.btn_size_minus);

        TextView versionInfo = view.findViewById(R.id.text_version_info);
        versionInfo.setMovementMethod(LinkMovementMethod.getInstance());
        String versionName = "0.0.0";
        try {
            versionName = BuildConfig.VERSION_NAME;
        } catch (Exception e) {
            // Use default
        }
        String infoText = "Version: v" + versionName + "   Author: <a href=\"https://github.com/midairlogn\">Midairlogn</a><br>" +
                "<a href=\"https://github.com/midairlogn/ML-Netease_Android\">ML-Netease_Android</a> © 2025 | GPLv3 LICENSE";
        versionInfo.setText(Html.fromHtml(infoText, Html.FROM_HTML_MODE_LEGACY));

        // Init values
        inputMusicU.setText(settingsManager.getMusicU());
        inputSearchLimit.setText(String.valueOf(settingsManager.getSearchLimit()));
        String currentQuality = settingsManager.getQuality();

        switch (currentQuality) {
            case "standard": qualityGroup.check(R.id.quality_standard); break;
            case "higher": qualityGroup.check(R.id.quality_higher); break;
            case "exhigh": qualityGroup.check(R.id.quality_exhigh); break;
            case "lossless": qualityGroup.check(R.id.quality_lossless); break;
            case "hires": qualityGroup.check(R.id.quality_hires); break;
            case "sky": qualityGroup.check(R.id.quality_sky); break;
            default: qualityGroup.check(R.id.quality_standard); break;
        }

        // Floating Window Init
        boolean isFloatingEnabled = settingsManager.isFloatingLyricsEnabled();
        switchFloatingLyrics.setChecked(isFloatingEnabled);
        layoutFloatingSettings.setVisibility(isFloatingEnabled ? View.VISIBLE : View.GONE);

        tempColor = settingsManager.getLyricColor();
        if (tempColor == 0) tempColor = Color.parseColor("#4CAF50");
        updateColorSelection();

        tempSize = settingsManager.getLyricSize();
        textFontSize.setText(String.valueOf((int)tempSize));

        switchFloatingLyrics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!Settings.canDrawOverlays(requireContext())) {
                    Toast.makeText(requireContext(), "Please grant overlay permission", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + requireContext().getPackageName()));
                    startActivityForResult(intent, 1001);
                    buttonView.setChecked(false); // Re-enable in onResume if granted
                    return;
                }
            }
            layoutFloatingSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Color buttons
        btnColorRed.setOnClickListener(v -> {
            tempColor = Color.parseColor("#F44336");
            updateColorSelection();
        });
        btnColorBlue.setOnClickListener(v -> {
            tempColor = Color.parseColor("#2196F3");
            updateColorSelection();
        });
        btnColorGreen.setOnClickListener(v -> {
            tempColor = Color.parseColor("#4CAF50");
            updateColorSelection();
        });
        btnColorYellow.setOnClickListener(v -> {
            tempColor = Color.parseColor("#FFEB3B");
            updateColorSelection();
        });
        btnColorPurple.setOnClickListener(v -> {
            tempColor = Color.parseColor("#9C27B0");
            updateColorSelection();
        });

        // Size buttons
        btnSizePlus.setOnClickListener(v -> {
            tempSize = Math.min(30f, tempSize + 2);
            textFontSize.setText(String.valueOf((int)tempSize));
        });
        btnSizeMinus.setOnClickListener(v -> {
            tempSize = Math.max(10f, tempSize - 2);
            textFontSize.setText(String.valueOf((int)tempSize));
        });

        btnSave.setOnClickListener(v -> {
            String cookie = inputMusicU.getText().toString().trim();
            settingsManager.setMusicU(cookie);

            String limitStr = inputSearchLimit.getText().toString().trim();
            if (!limitStr.isEmpty()) {
                try {
                    int limit = Integer.parseInt(limitStr);
                    settingsManager.setSearchLimit(limit);
                } catch (NumberFormatException e) {
                    Toast.makeText(getContext(), "Invalid limit number", Toast.LENGTH_SHORT).show();
                }
            }

            int selectedId = qualityGroup.getCheckedRadioButtonId();
            String quality = "standard";
            if (selectedId == R.id.quality_higher) quality = "higher";
            else if (selectedId == R.id.quality_exhigh) quality = "exhigh";
            else if (selectedId == R.id.quality_lossless) quality = "lossless";
            else if (selectedId == R.id.quality_hires) quality = "hires";
            else if (selectedId == R.id.quality_sky) quality = "sky";

            settingsManager.setQuality(quality);

            // Floating Window Save
            boolean enabled = switchFloatingLyrics.isChecked();
            settingsManager.setFloatingLyricsEnabled(enabled);
            settingsManager.setLyricColor(tempColor);
            settingsManager.setLyricSize(tempSize);

            // Notify Service to update
            Intent intent = new Intent(requireContext(), MusicService.class);
            intent.setAction("ACTION_UPDATE_SETTINGS");
            requireContext().startService(intent);

            Toast.makeText(getContext(), "Settings Saved", Toast.LENGTH_SHORT).show();
        });
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

        String currentQuality = settingsManager.getQuality();
        switch (currentQuality) {
            case "standard": qualityGroup.check(R.id.quality_standard); break;
            case "higher": qualityGroup.check(R.id.quality_higher); break;
            case "exhigh": qualityGroup.check(R.id.quality_exhigh); break;
            case "lossless": qualityGroup.check(R.id.quality_lossless); break;
            case "hires": qualityGroup.check(R.id.quality_hires); break;
            case "sky": qualityGroup.check(R.id.quality_sky); break;
            default: qualityGroup.check(R.id.quality_standard); break;
        }

        boolean isFloatingEnabled = settingsManager.isFloatingLyricsEnabled();
        // Avoid triggering listener if value is same
        switchFloatingLyrics.setOnCheckedChangeListener(null);
        switchFloatingLyrics.setChecked(isFloatingEnabled);
        switchFloatingLyrics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!Settings.canDrawOverlays(requireContext())) {
                    Toast.makeText(requireContext(), "Please grant overlay permission", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + requireContext().getPackageName()));
                    startActivityForResult(intent, 1001);
                    buttonView.setChecked(false); // Re-enable in onResume if granted
                    return;
                }
            }
            layoutFloatingSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        layoutFloatingSettings.setVisibility(isFloatingEnabled ? View.VISIBLE : View.GONE);

        tempColor = settingsManager.getLyricColor();
        if (tempColor == 0) tempColor = Color.parseColor("#4CAF50");
        updateColorSelection();

        tempSize = settingsManager.getLyricSize();
        textFontSize.setText(String.valueOf((int)tempSize));
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

        if (tempColor == Color.parseColor("#F44336")) {
            btnColorRed.setAlpha(1.0f);
            btnColorRed.setText("✓");
            btnColorRed.setTextColor(Color.WHITE);
        } else if (tempColor == Color.parseColor("#2196F3")) {
            btnColorBlue.setAlpha(1.0f);
            btnColorBlue.setText("✓");
            btnColorBlue.setTextColor(Color.WHITE);
        } else if (tempColor == Color.parseColor("#4CAF50")) {
            btnColorGreen.setAlpha(1.0f);
            btnColorGreen.setText("✓");
            btnColorGreen.setTextColor(Color.WHITE);
        } else if (tempColor == Color.parseColor("#FFEB3B")) {
            btnColorYellow.setAlpha(1.0f);
            btnColorYellow.setText("✓");
            btnColorYellow.setTextColor(Color.BLACK); // Yellow needs black text
        } else if (tempColor == Color.parseColor("#9C27B0")) {
            btnColorPurple.setAlpha(1.0f);
            btnColorPurple.setText("✓");
            btnColorPurple.setTextColor(Color.WHITE);
        }
    }
}
