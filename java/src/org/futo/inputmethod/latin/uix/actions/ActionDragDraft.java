package org.futo.inputmethod.latin.uix.actions;

/** In-memory drag transaction. Pointer moves never write settings; cancel never commits. */
public final class ActionDragDraft {
    private final OrderedActionIds initial;
    private OrderedActionIds draft;
    private boolean finished;
    public ActionDragDraft(OrderedActionIds initial) { this.initial = initial; this.draft = initial; }
    public OrderedActionIds preview() { return draft; }
    public void move(String id, int index) { if (!finished) draft = draft.move(id, index); }
    public void hide(String id) { if (!finished) draft = draft.hide(id); }
    /** A non-null result is a single completed-drop commit; repeated finish/cancel returns null. */
    public OrderedActionIds drop() {
        if (finished) return null;
        finished = true;
        return initial.visible.equals(draft.visible) && initial.hidden.equals(draft.hidden) ? null : draft;
    }
    public void cancel() { finished = true; draft = initial; }
}
