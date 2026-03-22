package com.midairlogn.mlnetease;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ManageShortcutsDialog extends DialogFragment {

    private SettingsManager settingsManager;
    private List<HomeShortcut> shortcuts;
    private LinearLayout listLayout;
    private Runnable onDismissListener;

    public void setOnDismissListener(Runnable listener) {
        this.onDismissListener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_manage_home_shortcuts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settingsManager = new SettingsManager(requireContext());
        shortcuts = new ArrayList<>(settingsManager.getHomeShortcuts());
        listLayout = view.findViewById(R.id.layout_manage_shortcuts_list);

        view.findViewById(R.id.btn_add_shortcut).setOnClickListener(v -> showEditDialog(null));
        view.findViewById(R.id.btn_close_shortcuts).setOnClickListener(v -> dismiss());

        renderList();
    }

    private void renderList() {
        listLayout.removeAllViews();
        getView().findViewById(R.id.tv_manage_shortcuts_empty).setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < shortcuts.size(); i++) {
            HomeShortcut s = shortcuts.get(i);
            View itemView = LayoutInflater.from(getContext()).inflate(R.layout.item_manage_home_shortcut, listLayout, false);
            ((TextView) itemView.findViewById(R.id.tv_manage_shortcut_title)).setText(s.title);
            ((TextView) itemView.findViewById(R.id.tv_manage_shortcut_meta)).setText(s.type + " (ID: " + s.id + ")");

            int pos = i;
            itemView.findViewById(R.id.btn_shortcut_up).setEnabled(pos > 0);
            itemView.findViewById(R.id.btn_shortcut_up).setOnClickListener(v -> swap(pos, pos - 1));
            itemView.findViewById(R.id.btn_shortcut_down).setEnabled(pos < shortcuts.size() - 1);
            itemView.findViewById(R.id.btn_shortcut_down).setOnClickListener(v -> swap(pos, pos + 1));
            itemView.findViewById(R.id.btn_shortcut_edit).setOnClickListener(v -> showEditDialog(s));
            itemView.findViewById(R.id.btn_shortcut_delete).setOnClickListener(v -> {
                shortcuts.remove(pos);
                saveAndRender();
            });
            listLayout.addView(itemView);
        }
    }

    private void swap(int from, int to) {
        Collections.swap(shortcuts, from, to);
        saveAndRender();
    }

    private void saveAndRender() {
        settingsManager.setHomeShortcuts(shortcuts);
        shortcuts = new ArrayList<>(settingsManager.getHomeShortcuts());
        renderList();
    }

    private void showEditDialog(HomeShortcut shortcut) {
        Dialog editDialog = new Dialog(getContext());
        editDialog.setContentView(R.layout.dialog_edit_home_shortcut);
        editDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);

        EditText titleInput = editDialog.findViewById(R.id.input_shortcut_title);
        EditText idInput = editDialog.findViewById(R.id.input_shortcut_id);
        RadioGroup typeGroup = editDialog.findViewById(R.id.group_shortcut_type);
        RadioButton playlistRadio = editDialog.findViewById(R.id.radio_shortcut_playlist);
        RadioButton albumRadio = editDialog.findViewById(R.id.radio_shortcut_album);

        if (shortcut != null) {
            titleInput.setText(shortcut.title);
            idInput.setText(shortcut.id);
            if (shortcut.isPlaylist()) playlistRadio.setChecked(true);
            else albumRadio.setChecked(true);
        }

        editDialog.findViewById(R.id.btn_save_shortcut).setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            String id = HomeShortcutIdParser.normalizeId(idInput.getText().toString());
            String type = playlistRadio.isChecked() ? HomeShortcut.TYPE_PLAYLIST : HomeShortcut.TYPE_ALBUM;

            if (title.isEmpty() || id.isEmpty()) {
                return;
            }

            if (shortcut == null) shortcuts.add(new HomeShortcut(title, id, type, 0));
            else {
                shortcut.title = title;
                shortcut.id = id;
                shortcut.type = type;
            }
            saveAndRender();
            editDialog.dismiss();
        });

        editDialog.show();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onDismissListener != null) onDismissListener.run();
    }
}
