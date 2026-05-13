package com.midairlogn.mlnetease.download.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.midairlogn.mlnetease.image.ImageManager;
import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.download.model.DownloadTaskListItem;
import com.midairlogn.mlnetease.download.model.DownloadTaskSnapshot;
import com.midairlogn.mlnetease.download.model.DownloadTaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DownloadTaskAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onPauseClicked(DownloadTaskSnapshot task);
        void onResumeClicked(DownloadTaskSnapshot task);
        void onCancelClicked(DownloadTaskSnapshot task);
        void onRetryClicked(DownloadTaskSnapshot task);
        void onRemoveClicked(DownloadTaskSnapshot task);
    }

    private final List<DownloadTaskListItem> items = new ArrayList<>();
    private Listener listener;

    public DownloadTaskAdapter() {
        setHasStableIds(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<DownloadTaskListItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public long getItemId(int position) {
        DownloadTaskListItem item = items.get(position);
        if (item.type == DownloadTaskListItem.TYPE_SECTION) {
            return ("section:" + item.title).hashCode();
        }
        return item.task == null ? RecyclerView.NO_ID : item.task.id.hashCode();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == DownloadTaskListItem.TYPE_SECTION) {
            View view = inflater.inflate(R.layout.item_download_section, parent, false);
            return new SectionViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_download_task, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DownloadTaskListItem item = items.get(position);
        if (holder instanceof SectionViewHolder) {
            ((SectionViewHolder) holder).bind(item.title);
            return;
        }
        ((TaskViewHolder) holder).bind(item.task, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;

        SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_section_title);
        }

        void bind(String sectionTitle) {
            title.setText(sectionTitle);
        }
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        private final ImageView cover;
        private final TextView title;
        private final TextView subtitle;
        private final TextView status;
        private final TextView progress;
        private final TextView details;
        private final TextView error;
        private final ProgressBar progressBar;
        private final Button pauseResume;
        private final Button cancel;
        private final Button retry;
        private final Button remove;

        TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.image_download_cover);
            title = itemView.findViewById(R.id.text_download_title);
            subtitle = itemView.findViewById(R.id.text_download_subtitle);
            status = itemView.findViewById(R.id.text_download_status);
            progress = itemView.findViewById(R.id.text_download_progress);
            details = itemView.findViewById(R.id.text_download_details);
            error = itemView.findViewById(R.id.text_download_error);
            progressBar = itemView.findViewById(R.id.progress_download_task);
            pauseResume = itemView.findViewById(R.id.btn_download_pause_resume);
            cancel = itemView.findViewById(R.id.btn_download_cancel);
            retry = itemView.findViewById(R.id.btn_download_retry);
            remove = itemView.findViewById(R.id.btn_download_remove);
        }

        void bind(DownloadTaskSnapshot task, Listener listener) {
            title.setText(task.title);
            subtitle.setText(task.subtitle);
            status.setText(statusLabel(itemView, task));
            progress.setText(itemView.getContext().getString(R.string.download_percent_format, task.progressPercent));
            progressBar.setProgress(task.progressPercent);
            details.setText(buildDetails(itemView, task));

            if (task.lastError != null && !task.lastError.trim().isEmpty() && task.status == DownloadTaskStatus.FAILED) {
                error.setVisibility(View.VISIBLE);
                error.setText(task.lastError);
            } else {
                error.setVisibility(View.GONE);
            }

            if (task.coverUrl != null && !task.coverUrl.trim().isEmpty()) {
                ImageManager.getInstance().load(task.coverUrl, cover, R.drawable.ic_ml_app_logo_foreground);
            } else {
                cover.setTag(null);
                cover.setImageResource(R.drawable.ic_ml_app_logo_foreground);
            }

            if (task.canPause || task.canResume) {
                pauseResume.setVisibility(View.VISIBLE);
                pauseResume.setText(task.canPause ? R.string.download_pause : R.string.download_resume);
                pauseResume.setOnClickListener(v -> {
                    if (listener == null) {
                        return;
                    }
                    if (task.canPause) {
                        listener.onPauseClicked(task);
                    } else if (task.canResume) {
                        listener.onResumeClicked(task);
                    }
                });
            } else {
                pauseResume.setVisibility(View.GONE);
            }

            cancel.setVisibility(task.canCancel ? View.VISIBLE : View.GONE);
            cancel.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCancelClicked(task);
                }
            });

            retry.setVisibility(task.canRetry ? View.VISIBLE : View.GONE);
            retry.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRetryClicked(task);
                }
            });

            remove.setVisibility(task.status.isTerminal() ? View.VISIBLE : View.GONE);
            remove.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRemoveClicked(task);
                }
            });
        }

        private static String statusLabel(View itemView, DownloadTaskSnapshot task) {
            int resId;
            switch (task.status) {
                case ACTIVE:
                    resId = R.string.download_status_active;
                    break;
                case PAUSED:
                    resId = R.string.download_status_paused;
                    break;
                case COMPLETED:
                    resId = R.string.download_status_completed;
                    break;
                case FAILED:
                    resId = R.string.download_status_failed;
                    break;
                case CANCELLED:
                    resId = R.string.download_status_cancelled;
                    break;
                case WAITING:
                default:
                    resId = R.string.download_status_waiting;
                    break;
            }
            return itemView.getContext().getString(resId);
        }

        private static String buildDetails(View itemView, DownloadTaskSnapshot task) {
            List<String> firstLineParts = new ArrayList<>();
            firstLineParts.add(itemView.getContext().getString(R.string.download_task_counts, task.completedCount, task.totalCount));
            if (task.skippedCount > 0) {
                firstLineParts.add(itemView.getContext().getString(R.string.download_task_skipped_count, task.skippedCount));
            }
            if (task.etaMillis > 0L) {
                firstLineParts.add(itemView.getContext().getString(R.string.download_eta_format, formatDuration(task.etaMillis)));
            }

            List<String> secondLineParts = new ArrayList<>();
            if (task.currentSongTitle != null && !task.currentSongTitle.trim().isEmpty()) {
                secondLineParts.add(task.currentSongTitle.trim());
            }
            if (task.statusMessage != null && !task.statusMessage.trim().isEmpty()) {
                secondLineParts.add(task.statusMessage.trim());
            }

            String firstLine = TextUtils.join(" • ", firstLineParts);
            String secondLine = TextUtils.join(" • ", secondLineParts);
            if (secondLine.isEmpty()) {
                return firstLine;
            }
            return firstLine + "\n" + secondLine;
        }

        private static String formatDuration(long millis) {
            long totalSeconds = Math.max(0L, millis / 1000L);
            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;
            if (minutes >= 60L) {
                long hours = minutes / 60L;
                long remainingMinutes = minutes % 60L;
                return String.format(Locale.US, "%dh %02dm", hours, remainingMinutes);
            }
            return String.format(Locale.US, "%dm %02ds", minutes, seconds);
        }
    }
}
