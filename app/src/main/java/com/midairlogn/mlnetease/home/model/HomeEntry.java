package com.midairlogn.mlnetease.home.model;

public class HomeEntry {
    public static final int TYPE_FAVOURITES = 0;
    public static final int TYPE_SHORTCUT = 1;

    public final int type;
    public final String title;
    public final String subtitle;
    public final String badge;
    public final HomeShortcut shortcut;
    public final boolean showManageIcon;

    private HomeEntry(int type, String title, String subtitle, String badge, HomeShortcut shortcut, boolean showManageIcon) {
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.badge = badge;
        this.shortcut = shortcut;
        this.showManageIcon = showManageIcon;
    }

    public static HomeEntry favourites(String title, String subtitle, String badge) {
        return new HomeEntry(TYPE_FAVOURITES, title, subtitle, badge, null, true);
    }

    public static HomeEntry shortcut(HomeShortcut shortcut, String subtitle, String badge) {
        return new HomeEntry(TYPE_SHORTCUT, shortcut.title, subtitle, badge, shortcut, false);
    }

    public boolean isFavourites() {
        return type == TYPE_FAVOURITES;
    }
}
