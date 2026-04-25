package com.midairlogn.mlnetease.download.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.midairlogn.mlnetease.download.model.DownloadTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DownloadTaskStore {
    private static final String PREF_NAME = "download_task_store";
    private static final String KEY_TASKS = "tasks_json";

    private DownloadTaskStore() {}

    public static List<DownloadTask> load(Context context) {
        List<DownloadTask> tasks = new ArrayList<>();
        if (context == null) {
            return tasks;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_TASKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                DownloadTask task = DownloadTask.fromJson(object);
                if (task != null) {
                    tasks.add(task);
                }
            }
        } catch (Exception ignored) {
        }
        return tasks;
    }

    public static void save(Context context, List<DownloadTask> tasks) {
        if (context == null) {
            return;
        }
        JSONArray array = new JSONArray();
        if (tasks != null) {
            for (DownloadTask task : tasks) {
                if (task != null) {
                    array.put(task.toJson());
                }
            }
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TASKS, array.toString())
                .apply();
    }
}
