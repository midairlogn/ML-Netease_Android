package com.midairlogn.mlnetease;

import android.app.Application;
import android.app.Activity;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

public class MainApplication extends Application implements Application.ActivityLifecycleCallbacks {

    private int activityCount = 0;
    private List<AppVisibilityListener> listeners = new ArrayList<>();
    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable notifyRunnable;

    public interface AppVisibilityListener {
        void onAppVisibilityChanged(boolean isForeground);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    public void addAppVisibilityListener(AppVisibilityListener listener) {
        listeners.add(listener);
    }

    public void removeAppVisibilityListener(AppVisibilityListener listener) {
        listeners.remove(listener);
    }

    public boolean isAppForeground() {
        return activityCount > 0;
    }

    private void notifyListeners() {
        if (notifyRunnable != null) {
            handler.removeCallbacks(notifyRunnable);
        }

        notifyRunnable = () -> {
            boolean isForeground = activityCount > 0;
            for (AppVisibilityListener listener : listeners) {
                listener.onAppVisibilityChanged(isForeground);
            }
            notifyRunnable = null;
        };

        // Small delay to debounce visibility changes during activity recreation/rotation
        handler.postDelayed(notifyRunnable, 300);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {
        activityCount++;
        notifyListeners();
    }

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {
        activityCount--;
        if (!activity.isChangingConfigurations()) {
            notifyListeners();
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}
}
