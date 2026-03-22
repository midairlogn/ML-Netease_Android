package com.midairlogn.mlnetease;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ManageShortcutsDialog extends DialogFragment implements ShortcutAdapter.OnItemInteractionListener {

    private SettingsManager settingsManager;
    private List<HomeShortcut> shortcuts;
    private RecyclerView recyclerView;
    private ShortcutAdapter adapter;
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
        recyclerView = view.findViewById(R.id.rv_manage_shortcuts);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ShortcutAdapter(shortcuts, this);
        recyclerView.setAdapter(adapter);

        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (requireActivity().getCurrentFocus() != null) {
                    hideKeyboard(requireActivity().getCurrentFocus());
                    requireActivity().getCurrentFocus().clearFocus();
                }
            }
            return false;
        });

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                Collections.swap(shortcuts, from, to);
                adapter.notifyItemMoved(from, to);
                adapter.notifyItemChanged(from);
                adapter.notifyItemChanged(to);
                saveAndRender();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);

        view.findViewById(R.id.btn_add_shortcut).setOnClickListener(v -> showEditDialog(null));
        view.findViewById(R.id.btn_close_shortcuts).setOnClickListener(v -> dismiss());

        updateEmptyView();
    }

    private void updateEmptyView() {
        getView().findViewById(R.id.tv_manage_shortcuts_empty).setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onEdit(HomeShortcut shortcut) {
        showEditDialog(shortcut);
    }

    @Override
    public void onDelete(int position) {
        shortcuts.remove(position);
        adapter.notifyItemRemoved(position);
        adapter.notifyItemRangeChanged(position, shortcuts.size());
        saveAndRender();
    }

    private void saveAndRender() {
        settingsManager.normalizeShortcutSequences(shortcuts);
        settingsManager.setHomeShortcuts(shortcuts);
        shortcuts.clear();
        shortcuts.addAll(settingsManager.getHomeShortcuts());
        updateEmptyView();
    }

    private void showEditDialog(HomeShortcut shortcut) {
        Dialog editDialog = new Dialog(getContext());
        editDialog.setContentView(R.layout.dialog_edit_home_shortcut);
        editDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);

        // Make root layout focusable to intercept clicks for keyboard hiding
        View root = editDialog.findViewById(android.R.id.content);
        if (root != null) {
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    View currentFocus = editDialog.getCurrentFocus();
                    if (currentFocus != null) {
                        hideKeyboard(currentFocus);
                        currentFocus.clearFocus();
                    }
                }
                return false;
            });
        }

        editDialog.findViewById(R.id.btn_close_edit).setOnClickListener(v -> editDialog.dismiss());

        EditText titleInput = editDialog.findViewById(R.id.input_shortcut_title);
        EditText idInput = editDialog.findViewById(R.id.input_shortcut_id);
        idInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String input = idInput.getText().toString();
                String normalized = HomeShortcutIdParser.normalizeId(input);
                if (!normalized.equals(input)) {
                    idInput.setText(normalized);
                }
            }
        });
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

            if (title.isEmpty() || id.isEmpty()) return;

            for (HomeShortcut s : shortcuts) {
                if (s != shortcut && s.type.equals(type) && s.id.equals(id)) {
                    Toast.makeText(getContext(), "Shortcut already exists", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            if (shortcut == null) shortcuts.add(new HomeShortcut(title, id, type, shortcuts.size()));
            else {
                shortcut.title = title;
                shortcut.id = id;
                shortcut.type = type;
            }
            saveAndRender();
            adapter.notifyDataSetChanged();
            editDialog.dismiss();
        });

        editDialog.show();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onDismissListener != null) onDismissListener.run();
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