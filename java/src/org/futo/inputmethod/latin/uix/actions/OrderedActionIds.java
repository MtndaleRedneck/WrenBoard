package org.futo.inputmethod.latin.uix.actions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure ordered/hidden model. No settings writes or legacy-category precedence is implied here. */
public final class OrderedActionIds {
    public final List<String> visible;
    public final List<String> hidden;

    private OrderedActionIds(List<String> visible, List<String> hidden) {
        this.visible = Collections.unmodifiableList(new ArrayList<>(visible));
        this.hidden = Collections.unmodifiableList(new ArrayList<>(hidden));
    }

    /** Hidden wins duplicates, so migration never reveals an action the user previously hid. */
    public static OrderedActionIds normalize(List<String> visible, List<String> hidden, List<String> known) {
        Set<String> allowed = new LinkedHashSet<>(known);
        Set<String> h = new LinkedHashSet<>(hidden);
        h.retainAll(allowed);
        Set<String> v = new LinkedHashSet<>(visible);
        v.retainAll(allowed);
        v.removeAll(h);
        // Missing actions stay reachable, but are not silently activated in the toolbar.
        for (String id : allowed) if (!v.contains(id)) h.add(id);
        return new OrderedActionIds(new ArrayList<>(v), new ArrayList<>(h));
    }

    /** WrenBoard 1.9 (item 2): the approved SwiftKey toolbar order is favorites-first
     * (clipboard, emoji, GIF, mic, share, info, ...). Legacy assigned/pinned actions follow. */
    public static OrderedActionIds migrate(List<String> assigned, List<String> pinned,
            List<String> favorites, List<String> more, List<String> hidden, List<String> known) {
        List<String> merged = new ArrayList<>(favorites);
        merged.addAll(pinned);
        merged.addAll(assigned);
        merged.addAll(more);
        return normalize(merged, hidden, known);
    }

    public OrderedActionIds hide(String id) {
        if (!visible.contains(id)) return this;
        List<String> v = new ArrayList<>(visible);
        List<String> h = new ArrayList<>(hidden);
        v.remove(id);
        h.add(id);
        return new OrderedActionIds(v, h);
    }

    public OrderedActionIds show(String id, int index) {
        if (!hidden.contains(id)) return this;
        List<String> v = new ArrayList<>(visible);
        List<String> h = new ArrayList<>(hidden);
        h.remove(id);
        v.add(Math.max(0, Math.min(index, v.size())), id);
        return new OrderedActionIds(v, h);
    }

    public OrderedActionIds move(String id, int index) {
        if (!visible.contains(id)) return this;
        List<String> v = new ArrayList<>(visible);
        v.remove(id);
        v.add(Math.max(0, Math.min(index, v.size())), id);
        return new OrderedActionIds(v, hidden);
    }
}
