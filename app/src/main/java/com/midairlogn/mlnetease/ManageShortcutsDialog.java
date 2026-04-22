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
import android.widget.TextView;
import android.widget.ImageButton;
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
    private HomeShortcut initialShortcut;
    private Dialog editDialog;

    public void setOnDismissListener(Runnable listener) {
        this.onDismissListener = listener;
    }

    public void setInitialShortcut(HomeShortcut shortcut) {
        this.initialShortcut = shortcut;
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

        if (initialShortcut != null) {
            // Find the actual shortcut object in the local list to ensure reference equality
            HomeShortcut target = null;
            for (HomeShortcut s : shortcuts) {
                if (s.type.equals(initialShortcut.type) && s.id.equals(initialShortcut.id)) {
                    target = s;
                    break;
                }
            }
            // Delay showing the edit dialog to ensure the main dialog is already visible
            final HomeShortcut finalTarget = target != null ? target : initialShortcut;
            view.post(() -> showEditDialog(finalTarget));
            initialShortcut = null;
        }

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
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                saveAndRender();
                adapter.notifyDataSetChanged();
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
        View emptyView = getView().findViewById(R.id.tv_manage_shortcuts_empty);
        if (emptyView != null) {
            emptyView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (recyclerView != null) {
            recyclerView.setVisibility(shortcuts.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onEdit(HomeShortcut shortcut) {
        showEditDialog(shortcut);
    }

    @Override
    public synchronized void onDelete(int position) {
        if (position >= 0 && position < shortcuts.size()) {
            shortcuts.remove(position);
            adapter.notifyItemRemoved(position);
            // The saveAndRender method will handle the full data set change notification.
            saveAndRender();
        } else {
            // Log this or show a toast, though with synchronization, this case should be rare for valid initial positions.
            Toast.makeText(getContext(), getString(R.string.hint_error_title) + getString(R.string.hint_shortcut_not_found), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAndRender() {
        synchronized (shortcuts) {
            settingsManager.normalizeShortcutSequences(shortcuts);
            settingsManager.setHomeShortcuts(shortcuts);
            shortcuts.clear();
            shortcuts.addAll(settingsManager.getHomeShortcuts());
            updateEmptyView();
            adapter.notifyDataSetChanged(); // Ensure UI is updated after save
        }
    }

    private void showEditDialog(HomeShortcut shortcut) {
        if (editDialog != null && editDialog.isShowing()) {
            return;
        }
        editDialog = new Dialog(getContext());
        editDialog.setContentView(R.layout.dialog_edit_home_shortcut);
        editDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        editDialog.setOnDismissListener(dialog -> {
            if (this.editDialog == dialog) {
                this.editDialog = null;
            }
        });

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
        ImageButton clearTitleButton = editDialog.findViewById(R.id.btn_clear_shortcut_title);
        EditText idInput = editDialog.findViewById(R.id.input_shortcut_id);
        ImageButton clearIdButton = editDialog.findViewById(R.id.btn_clear_shortcut_id);

        clearTitleButton.setOnClickListener(v -> titleInput.setText(""));
        clearIdButton.setOnClickListener(v -> idInput.setText(""));

        titleInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearTitleButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) { }
        });

        idInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearIdButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) { }
        });

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
            TextView editorTitle = editDialog.findViewById(R.id.tv_shortcut_editor_title);
            if (editorTitle != null) editorTitle.setText(R.string.edit_shortcut);
            titleInput.setText(shortcut.title);
            idInput.setText(shortcut.id);
            if (shortcut.isPlaylist()) playlistRadio.setChecked(true);
            else albumRadio.setChecked(true);
        } else {
            TextView editorTitle = editDialog.findViewById(R.id.tv_shortcut_editor_title);
            if (editorTitle != null) editorTitle.setText(R.string.add_shortcut);
        }

        editDialog.findViewById(R.id.btn_save_shortcut).setOnClickListener(v -> {
            String title = titleInput.getText().toString().trim();
            String id = HomeShortcutIdParser.normalizeId(idInput.getText().toString());
            String type = playlistRadio.isChecked() ? HomeShortcut.TYPE_PLAYLIST : HomeShortcut.TYPE_ALBUM;

            if (title.isEmpty() || id.isEmpty()) return;

            for (HomeShortcut s : shortcuts) {
                if (s != shortcut && s.type.equals(type) && s.id.equals(id)) {
                    Toast.makeText(getContext(), R.string.hint_shortcut_exist, Toast.LENGTH_SHORT).show();
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
        if (editDialog != null && editDialog.isShowing()) {
            editDialog.dismiss();
        }
        editDialog = null;
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
