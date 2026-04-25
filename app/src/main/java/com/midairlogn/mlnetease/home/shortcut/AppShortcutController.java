package com.midairlogn.mlnetease.home.shortcut;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import com.midairlogn.mlnetease.R;
import com.midairlogn.mlnetease.MainActivity;
import com.midairlogn.mlnetease.home.model.HomeShortcut;
import com.midairlogn.mlnetease.settings.SettingsManager;

import java.util.ArrayList;
import java.util.List;

public final class AppShortcutController {
    public static final String ACTION_OPEN_DOWNLOADS = "com.midairlogn.mlnetease.action.OPEN_DOWNLOADS";
    public static final String ACTION_PLAY_FAVOURITES = "com.midairlogn.mlnetease.action.PLAY_FAVOURITES";
    public static final String ACTION_PLAY_HOME_SHORTCUT = "com.midairlogn.mlnetease.action.PLAY_HOME_SHORTCUT";
    public static final String EXTRA_SHORTCUT_TYPE = "extra_shortcut_type";
    public static final String EXTRA_SHORTCUT_ID = "extra_shortcut_id";
    private static final String SHORTCUT_ID_DOWNLOADS = "app_downloads";
    private static final String SHORTCUT_ID_FAVOURITES = "app_favourites";
    private static final String SHORTCUT_ID_PREFIX_HOME = "app_home_";

    private AppShortcutController() {
    }

    public static void refresh(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return;
        }

        Context appContext = context.getApplicationContext();
        ShortcutManager shortcutManager = appContext.getSystemService(ShortcutManager.class);
        if (shortcutManager == null) {
            return;
        }

        SettingsManager settingsManager = new SettingsManager(appContext);
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        int maxShortcuts = Math.max(0, shortcutManager.getMaxShortcutCountPerActivity());
        if (maxShortcuts == 0) {
            shortcutManager.removeAllDynamicShortcuts();
            return;
        }

        shortcuts.add(buildDownloadsShortcut(appContext));
        if (!settingsManager.getFavouriteSongs().isEmpty() && shortcuts.size() < maxShortcuts) {
            shortcuts.add(buildFavouritesShortcut(appContext));
        }

        List<HomeShortcut> homeShortcuts = settingsManager.getHomeShortcuts();
        for (HomeShortcut shortcut : homeShortcuts) {
            if (shortcut == null || shortcuts.size() >= maxShortcuts) {
                break;
            }
            shortcuts.add(buildHomeShortcut(appContext, shortcut));
        }

        shortcutManager.setDynamicShortcuts(shortcuts);
    }

    public static HomeShortcut findHomeShortcut(Context context, String type, String id) {
        if (context == null || type == null || id == null) {
            return null;
        }
        List<HomeShortcut> shortcuts = new SettingsManager(context.getApplicationContext()).getHomeShortcuts();
        for (HomeShortcut shortcut : shortcuts) {
            if (shortcut != null && type.equals(shortcut.type) && id.equals(shortcut.id)) {
                return shortcut;
            }
        }
        return null;
    }

    private static ShortcutInfo buildDownloadsShortcut(Context context) {
        return new ShortcutInfo.Builder(context, SHORTCUT_ID_DOWNLOADS)
                .setShortLabel(context.getString(R.string.app_shortcut_downloads))
                .setLongLabel(context.getString(R.string.app_shortcut_downloads_long))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_downloads))
                .setIntent(createBaseIntent(context, ACTION_OPEN_DOWNLOADS))
                .build();
    }

    private static ShortcutInfo buildFavouritesShortcut(Context context) {
        return new ShortcutInfo.Builder(context, SHORTCUT_ID_FAVOURITES)
                .setShortLabel(context.getString(R.string.app_shortcut_favourites))
                .setLongLabel(context.getString(R.string.app_shortcut_favourites_long))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_favourites))
                .setIntent(createBaseIntent(context, ACTION_PLAY_FAVOURITES))
                .build();
    }

    private static ShortcutInfo buildHomeShortcut(Context context, HomeShortcut shortcut) {
        String title = normalizeLabel(shortcut.title, context.getString(R.string.app_shortcut_home_fallback));
        Intent intent = createBaseIntent(context, ACTION_PLAY_HOME_SHORTCUT)
                .putExtra(EXTRA_SHORTCUT_TYPE, shortcut.type)
                .putExtra(EXTRA_SHORTCUT_ID, shortcut.id);
        return new ShortcutInfo.Builder(context, SHORTCUT_ID_PREFIX_HOME + shortcut.type + "_" + shortcut.id)
                .setShortLabel(title)
                .setLongLabel(title)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_play))
                .setIntent(intent)
                .build();
    }

    private static Intent createBaseIntent(Context context, String action) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private static String normalizeLabel(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
