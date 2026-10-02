package com.midairlogn.mlnetease.shared.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowInsets;
import android.widget.EditText;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import com.midairlogn.mlnetease.MainActivity;

/**
 * Keeps the MainActivity mini player hidden while an editor owns the keyboard. The
 * suppression is landscape-only (MainActivity decides) because the IME plus the mini
 * player leave the content area too little height there.
 */
public final class MiniPlayerImeHelper {

    private static final long RELEASE_POLL_MS = 400;

    private MiniPlayerImeHelper() {
    }

    public static void setSuppressed(Fragment fragment, boolean suppressed) {
        if (fragment.isAdded() && fragment.getActivity() instanceof MainActivity) {
            ((MainActivity) fragment.getActivity()).setMiniPlayerSuppressed(suppressed);
        }
    }

    public static void setSuppressedOnFocus(Fragment fragment, EditText input) {
        input.setOnFocusChangeListener((v, hasFocus) -> onEditorFocusChanged(fragment, input, hasFocus));
    }

    /**
     * Focus gained suppresses immediately. Focus lost starts a poll that releases the
     * suppression only once editing has truly ended: some ROMs (secure-IME handoff)
     * transiently clear or keep editor focus while the keyboard state is ambiguous, so
     * neither an immediate release nor a one-shot check is reliable.
     */
    public static void onEditorFocusChanged(Fragment fragment, EditText input, boolean hasFocus) {
        setSuppressed(fragment, hasFocus);
        if (!hasFocus) {
            scheduleReleaseCheck(fragment, input);
        }
    }

    private static void scheduleReleaseCheck(Fragment fragment, EditText input) {
        input.postDelayed(() -> {
            if (fragment.getActivity() == null || input.getWindowToken() == null) {
                setSuppressed(fragment, false);
                return;
            }
            if (input.isFocused() || isImeVisible(input)) {
                scheduleReleaseCheck(fragment, input);
            } else {
                setSuppressed(fragment, false);
            }
        }, RELEASE_POLL_MS);
    }

    /** Any text typed into the editor re-asserts suppression so ROM focus quirks
     *  cannot drop it mid-editing. */
    public static void keepSuppressedWhileEditing(Fragment fragment, EditText input) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) {
                    setSuppressed(fragment, true);
                }
            }
        });
    }

    private static boolean isImeVisible(EditText input) {
        WindowInsets insets = input.getRootWindowInsets();
        return insets != null
                && WindowInsetsCompat.toWindowInsetsCompat(insets).isVisible(WindowInsetsCompat.Type.ime());
    }

    /** Drops editor focus restored across activity recreation: its keyboard is not reopened. */
    public static void dropStaleFocus(EditText input) {
        if (input != null && input.isFocused()) {
            input.clearFocus();
        }
    }
}
