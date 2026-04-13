package com.midairlogn.mlnetease;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class DownloadsFragment extends Fragment implements DownloadTaskManager.Listener {
    private DownloadTaskManager taskManager;
    private DownloadTaskAdapter adapter;
    private TextView textSummary;
    private TextView textEmpty;
    private Button btnClearFinished;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        taskManager = DownloadTaskManager.getInstance(requireContext());

        textSummary = view.findViewById(R.id.text_downloads_summary);
        textEmpty = view.findViewById(R.id.text_downloads_empty);
        btnClearFinished = view.findViewById(R.id.btn_clear_finished_downloads);
        RecyclerView recyclerView = view.findViewById(R.id.recycler_download_tasks);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new DownloadTaskAdapter();
        adapter.setListener(new DownloadTaskAdapter.Listener() {
            @Override
            public void onPauseClicked(DownloadTaskSnapshot task) {
                SongDownloadService.pauseTask(requireContext(), task.id);
            }

            @Override
            public void onResumeClicked(DownloadTaskSnapshot task) {
                SongDownloadService.resumeTask(requireContext(), task.id);
            }

            @Override
            public void onCancelClicked(DownloadTaskSnapshot task) {
                SongDownloadService.cancelTask(requireContext(), task.id);
            }

            @Override
            public void onRemoveClicked(DownloadTaskSnapshot task) {
                taskManager.removeTask(task.id);
            }
        });
        recyclerView.setAdapter(adapter);

        btnClearFinished.setOnClickListener(v -> SongDownloadService.clearFinishedTasks(requireContext()));

        onDownloadTasksChanged(taskManager.getTaskSnapshots());
    }

    @Override
    public void onStart() {
        super.onStart();
        taskManager.addListener(this);
    }

    @Override
    public void onStop() {
        taskManager.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDownloadTasksChanged(List<DownloadTaskSnapshot> tasks) {
        if (getActivity() == null) {
            return;
        }
        getActivity().runOnUiThread(() -> render(tasks));
    }

    private void render(List<DownloadTaskSnapshot> tasks) {
        int active = 0;
        int waiting = 0;
        int finished = 0;
        List<DownloadTaskSnapshot> activeTasks = new ArrayList<>();
        List<DownloadTaskSnapshot> waitingTasks = new ArrayList<>();
        List<DownloadTaskSnapshot> finishedTasks = new ArrayList<>();

        for (DownloadTaskSnapshot task : tasks) {
            if (task.status == DownloadTaskStatus.ACTIVE) {
                active++;
                activeTasks.add(task);
            } else if (task.status == DownloadTaskStatus.WAITING || task.status == DownloadTaskStatus.PAUSED) {
                waiting++;
                waitingTasks.add(task);
            } else {
                finished++;
                finishedTasks.add(task);
            }
        }

        textSummary.setText(getString(R.string.downloads_summary_format, active, waiting, finished));
        btnClearFinished.setVisibility(finished > 0 ? View.VISIBLE : View.GONE);
        textEmpty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);

        List<DownloadTaskListItem> items = new ArrayList<>();
        if (!activeTasks.isEmpty()) {
            items.add(DownloadTaskListItem.section(getString(R.string.download_section_active)));
            for (DownloadTaskSnapshot task : activeTasks) {
                items.add(DownloadTaskListItem.task(task));
            }
        }
        if (!waitingTasks.isEmpty()) {
            items.add(DownloadTaskListItem.section(getString(R.string.download_section_waiting)));
            for (DownloadTaskSnapshot task : waitingTasks) {
                items.add(DownloadTaskListItem.task(task));
            }
        }
        if (!finishedTasks.isEmpty()) {
            items.add(DownloadTaskListItem.section(getString(R.string.download_section_finished)));
            for (DownloadTaskSnapshot task : finishedTasks) {
                items.add(DownloadTaskListItem.task(task));
            }
        }
        adapter.setItems(items);
    }
}
