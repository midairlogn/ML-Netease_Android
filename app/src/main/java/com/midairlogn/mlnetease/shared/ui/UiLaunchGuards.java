package com.midairlogn.mlnetease.shared.ui;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public final class UiLaunchGuards {

    private UiLaunchGuards() {
    }

    public static boolean showDialogFragmentOnce(@NonNull FragmentManager fragmentManager,
                                                 @NonNull DialogFragment dialogFragment,
                                                 @NonNull String tag) {
        if (fragmentManager.isStateSaved()) {
            return false;
        }
        Fragment existing = fragmentManager.findFragmentByTag(tag);
        if (existing instanceof DialogFragment && existing.isAdded() && !existing.isRemoving()) {
            return false;
        }
        dialogFragment.showNow(fragmentManager, tag);
        return true;
    }

    public static boolean showAlertDialogOnce(AlertDialog currentDialog, @NonNull AlertDialog nextDialog) {
        if (currentDialog != null && currentDialog.isShowing()) {
            return false;
        }
        nextDialog.show();
        return true;
    }
}
