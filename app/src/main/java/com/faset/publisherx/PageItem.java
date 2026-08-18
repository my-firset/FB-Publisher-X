package com.faset.publisherx;

public final class PageItem {
    public final String id;
    public final String name;

    public PageItem(String id, String name) {
        this.id = id == null ? "" : id.trim();
        this.name = (name == null || name.trim().isEmpty()) ? this.id : name.trim();
    }
}
