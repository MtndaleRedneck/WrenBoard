package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.WbDebug
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import java.io.File
import kotlin.math.roundToInt

/** History first, then the event's own point, for DragAccum. */
private fun androidx.compose.ui.input.pointer.PointerInputChange.allSamples(): List<DragSample> =
    historical.map { DragSample(it.position.x, it.position.y, it.uptimeMillis) } +
        DragSample(position.x, position.y, uptimeMillis)

/** WrenBoard 1.2: the SwiftKey-style emoji and clipboard panels. Off = FUTO's own panels (kept, not removed). */
val WrenSwiftKeyPanels = SettingsKey(booleanPreferencesKey("wren_swiftkey_panels"), true)

/** Colours measured from the approved renders (wrenboard_v4_swiftkey s06/s07). */
object WrenPanelColors {
    val Orange = Color(0xFFEE8F33)
    val OrangeBright = Color(0xFFFF8714)
    val OrangeMuted = Color(0xFFC87A30)
    val Handle = Color(0xFFD4822F)
    val Bar = Color(0xFF3C3C3C)
    val BodyTop = Color(0xFF454442)
    val BodyMid = Color(0xFF3E3D3B)
    val BodyBottom = Color(0xFF363533)
    val Card = Color(0xFF433B33)
    val CardBorder = Color(0xFF5D544B)
    val Menu = Color(0xFF2B2A29)
    val MenuBorder = Color(0xFF5C5650)
    val Field = Color(0xFF2F2F2F)
    val FieldBorder = Color(0xFF474747)
    val UndoBar = Color(0xFF2B2A29)
}

/** Panel heights were 428/520 dp converted at 2.625 px/dp (1080 px, 420 dpi, screen 882 dp tall). On Ezzy's
 * 480 dpi phone the screen is only 772 dp tall, so fixed dp panels covered ~14% more of it (teardown items 3/9).
 * Keep the reference FRACTION of the screen instead, so every density lands at the same size. */
private fun wrenScreenHeightDp(): Float {
    val dm = android.content.res.Resources.getSystem().displayMetrics
    return dm.heightPixels / dm.density
}
val WrenClipboardPanelHeight: Dp get() = (wrenScreenHeightDp() * (428f / 882f)).dp
val WrenEmojiPanelHeight: Dp get() = (wrenScreenHeightDp() * (520f / 882f)).dp
val WrenPanelSearchHeight: Dp = 64.dp

/** One removal that can still be undone: the entries and where they were in the history list. */
class WrenUndo(val removed: List<Pair<Int, ClipboardEntry>>, val label: String) {
    val id = System.nanoTime()
}

class WrenClipboardWindow(
    val manager: KeyboardManagerForAction,
    val history: ClipboardHistoryManager
) : ActionWindow() {
    val searching = mutableStateOf(false)
    val searchText = mutableStateOf("")
    val editing: MutableState<ClipboardEntry?> = mutableStateOf(null)
    val editText = mutableStateOf("")
    val undo: MutableState<WrenUndo?> = mutableStateOf(null)
    val menuFor: MutableState<ClipboardEntry?> = mutableStateOf(null)

    override val showTitleBar: Boolean get() = false
    override val positionIsUserManagable: Boolean get() = false
    override val showCloseButton: Boolean get() = false
    override val fixedWindowHeight: Dp
        get() = if (searching.value || editing.value != null) WrenPanelSearchHeight else WrenClipboardPanelHeight

    @Composable
    override fun windowName(): String = stringResource(R.string.action_clipboard_manager_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        WrenClipboardPanel(this)
    }

    override fun close(): CloseResult {
        searching.value = false
        editing.value = null
        menuFor.value = null
        return CloseResult.Default
    }

    fun endTextMode() {
        searching.value = false
        searchText.value = ""
        editing.value = null
        manager.forceActionWindowAboveKeyboard(false)
    }

    /** Removes entries now and keeps them for Undo. Every delete in this panel goes through here. */
    fun removeWithUndo(entries: List<ClipboardEntry>, label: String) {
        val list = history.clipboardHistory
        val removed = entries.mapNotNull { e -> val i = list.indexOf(e); if (i >= 0) i to e else null }
            .sortedBy { it.first }
        if (removed.isEmpty()) return
        removed.reversed().forEach { (_, e) -> history.onRemove(e) }
        undo.value = WrenUndo(removed, label)
        WbDebug.logWindow("CLIP removed=${removed.size} left=${list.size}")
    }

    fun undoLast() {
        val u = undo.value ?: return
        val list = history.clipboardHistory
        u.removed.forEach { (i, e) -> if (!list.contains(e)) list.add(i.coerceIn(0, list.size), e) }
        history.saveClipboard()
        undo.value = null
        WbDebug.logWindow("CLIP undo restored=${u.removed.size} left=${list.size}")
    }

    fun paste(entry: ClipboardEntry, view: View) {
        val context = manager.getContext()
        if (entry.text != null) {
            manager.typeText(entry.text)
        } else if (entry.backingFile != null && entry.mimeTypes.isNotEmpty()) {
            val request = ClipboardProviderState.addRequest(
                ClipboardPasteRequest(
                    file = File(context.clipboardDir, entry.backingFile),
                    mimeType = entry.mimeTypes.first(),
                    expiration = System.currentTimeMillis() + 5L * 60L * 1000L
                )
            )
            val uri = "content://${CLIPBOARD_AUTHORITY}/clip/$request".toUri()
            manager.typeUri(uri, entry.mimeTypes, true)
        }
        history.onPaste(entry)
        manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
    }

    fun saveEdit() {
        val entry = editing.value ?: return
        val list = history.clipboardHistory
        val i = list.indexOf(entry)
        if (i >= 0 && entry.text != null) {
            list[i] = entry.copy(text = editText.value, timestamp = System.currentTimeMillis())
            history.saveClipboard()
        }
        endTextMode()
    }
}

private val orange get() = WrenPanelColors.Orange

/** DEBUG builds: logs a screen-space centre for the emulator checks (WBDBG "WINDOW <tag> cx= cy= w= h="). */
@Composable
fun Modifier.wbPos(tag: String): Modifier {
    if (!org.futo.inputmethod.latin.BuildConfig.DEBUG) return this
    val v = LocalView.current
    return this.onGloballyPositioned { c ->
        val loc = IntArray(2); v.getLocationOnScreen(loc); val p = c.positionInRoot()
        WbDebug.logWindow("$tag cx=${(loc[0] + p.x + c.size.width / 2).toInt()} cy=${(loc[1] + p.y + c.size.height / 2).toInt()} w=${c.size.width} h=${c.size.height}")
    }
}

@Composable
private fun WrenIcon(id: Int, tint: Color, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(id), contentDescription = description, tint = tint, modifier = modifier.size(size))
}

@Composable
private fun HeaderButton(id: Int, description: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { WrenIcon(id, orange, size, description = description) }
}

@Composable
fun WrenClipboardPanel(w: WrenClipboardWindow) {
    val view = LocalView.current
    val context = LocalContext.current
    val resources = context.resources

    // Search and edit borrow the keyboard: the panel shrinks to one field above it.
    val editing = w.editing.value
    if (w.searching.value || editing != null) {
        Row(
            Modifier.fillMaxSize().background(WrenPanelColors.Bar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderButton(R.drawable.wren_ic_arrow_back_ios_new, stringResource(R.string.wren_back), 18.dp) {
                if (editing != null) w.saveEdit() else w.endTextMode()
            }
            Box(
                Modifier.weight(1f).height(44.dp)
                    .background(WrenPanelColors.Field, RoundedCornerShape(8.dp))
                    .border(1.dp, WrenPanelColors.FieldBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (editing != null) {
                    ActionTextEditor(text = w.editText, multiline = false, onEnter = { w.saveEdit() })
                } else {
                    ActionTextEditor(text = w.searchText, placeholder = stringResource(R.string.action_clipboard_manager_enter_your_search))
                }
            }
            if (editing != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.wren_clip_save), color = orange, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { w.saveEdit() }.padding(10.dp)
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Header 124 px (47 dp): orange drag handle, back, title, Clear all, search, gear.
        Box(Modifier.fillMaxWidth().height(47.dp).background(WrenPanelColors.Bar)) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 5.dp).width(35.dp).height(5.dp)
                    .background(WrenPanelColors.Handle, RoundedCornerShape(3.dp))
            )
            Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(R.drawable.wren_ic_arrow_back_ios_new, stringResource(R.string.wren_back), 18.dp) {
                    manager_close(w, view)
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.wren_clip_title), color = orange, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                // Clear all: a header button with a confirmation (Ezzy, Oct 30); pinned clips stay; Undo brings them back.
                val unpinned = w.history.clipboardHistory.filter { !it.pinned }
                if (unpinned.isNotEmpty()) {
                    Text(
                        stringResource(R.string.wren_clip_clear_all), color = orange, fontSize = 15.sp,
                        modifier = Modifier.wbPos("CLIPCLEARALL").clip(RoundedCornerShape(8.dp)).clickable {
                            manager_dialog(w,
                                resources.getString(R.string.wren_clip_clear_all_confirm),
                                resources.getString(R.string.action_clipboard_manager_cancel_action_button),
                                resources.getString(R.string.wren_clip_delete)
                            ) { w.removeWithUndo(w.history.clipboardHistory.filter { !it.pinned }, resources.getString(R.string.wren_clip_cleared)) }
                        }.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                HeaderButton(R.drawable.wren_ic_search, stringResource(R.string.action_clipboard_manager_search), 27.dp) {
                    w.searching.value = true
                }
                Spacer(Modifier.width(18.dp))
                HeaderButton(R.drawable.wren_ic_settings, stringResource(R.string.wren_clip_settings), 26.dp) {
                    org.futo.inputmethod.latin.uix.settings.SettingsActivity.openToNavDest(w.manager.getContext(), "actions/clipboard_history")
                }
            }
        }

        val showPinnedOnTop = useDataStoreValue(ClipboardShowPinnedOnTop)
        val query = w.searchText.value
        val entries = w.history.clipboardHistory.toList().let { list ->
            val filtered = if (query.isEmpty()) list else list.filter { it.text?.contains(query, ignoreCase = true) == true }
            val newestFirst = filtered.reversed()
            if (showPinnedOnTop) newestFirst.sortedBy { if (it.pinned) 0 else 1 } else newestFirst
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(
                Brush.verticalGradient(listOf(WrenPanelColors.BodyTop, WrenPanelColors.BodyMid, WrenPanelColors.BodyBottom))
            )
        ) {
            LaunchedEffect(entries.size) { WbDebug.logWindow("CLIP shown=${entries.size}") }
            // Masonry: two columns of 201 dp cards (528 px), 4.5 dp gaps, newest first.
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 5.dp, bottom = 60.dp)
            ) {
                items(entries.size, key = { i -> entries[i].timestamp.toString() + ":" + (entries[i].text?.hashCode() ?: entries[i].backingFile.hashCode()) }) { i ->
                    val entry = entries[i]
                    WrenClipCard(w, entry, view, i)
                }
            }

            w.undo.value?.let { u ->
                LaunchedEffect(u.id) { delay(6000); if (w.undo.value?.id == u.id) w.undo.value = null }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth().height(46.dp)
                        .background(WrenPanelColors.UndoBar, RoundedCornerShape(12.dp))
                        .border(1.dp, WrenPanelColors.MenuBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(u.label, color = Color(0xFFE6E6E6), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.wbPos("CLIPUNDO").clip(RoundedCornerShape(8.dp)).clickable { w.undoLast() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WrenIcon(R.drawable.wren_ic_undo, orange, 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.wren_undo), color = orange, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

private fun manager_close(w: WrenClipboardWindow, view: View) {
    w.manager.closeActionWindow()
    w.manager.performHapticAndAudioFeedback(Constants.CODE_SWITCH_ALPHA_SYMBOL, view)
}

private fun manager_dialog(w: WrenClipboardWindow, text: String, cancel: String, ok: String, onOk: () -> Unit) {
    w.manager.requestDialog(text, listOf(DialogRequestItem(cancel) { }, DialogRequestItem(ok) { onOk() }), { })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WrenClipCard(w: WrenClipboardWindow, entry: ClipboardEntry, view: View, index: Int) {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val offsetX = remember(entry) { Animatable(0f) }
    val dragX = remember(entry) { mutableStateOf(0f) }
    val dragging = remember(entry) { mutableStateOf(false) }
    val widthPx = remember { mutableStateOf(1f) }
    val shape = RoundedCornerShape(11.5.dp)
    // Swipe a clip sideways to delete it (with Undo). The gesture is read on the card's resting slot (outside the
    // offset), so the card moving under the finger cannot shrink or cancel the drag. Only a finished swipe past the
    // threshold deletes; an interrupted or cancelled drag snaps back (WrenSwipeJudge). The delete never waits on
    // the slide-out animation.
    val finishSwipe: (Boolean) -> Unit = { completed ->
        val x = dragX.value
        if (org.futo.inputmethod.latin.BuildConfig.DEBUG) android.util.Log.i("WBDBG",
            "CLIPTOUCH VERDICT rx=${android.os.SystemClock.uptimeMillis()} accDx=${x.toInt()} completed=$completed outcome=${WrenSwipeJudge.judge(x, completed)} thr=${WrenSwipeJudge.DELETE_THRESHOLD_PX.toInt()}")
        dragging.value = false
        if (WrenSwipeJudge.judge(x, completed) == WrenSwipeJudge.Outcome.Delete) {
            w.removeWithUndo(listOf(entry), resources.getString(R.string.wren_clip_deleted))
            scope.launch { offsetX.snapTo(x); try { offsetX.animateTo(if (x > 0) 1400f else -1400f) } catch (_: Exception) { } }
        } else {
            scope.launch { offsetX.snapTo(x); try { offsetX.animateTo(0f) } catch (_: Exception) { } }
        }
        dragX.value = 0f
    }

    Box(
        Modifier
            // Swipe-delete determinism (wb12-check cl04 failures, reproduced on 1.4 too): the old
            // stack of detectHorizontalDragGestures + combinedClickable raced the long-press timeout
            // against touch slop - a slow or emulator-batched touch opened the Pin menu mid-drag and
            // cancelled the swipe. Explicit arbitration instead: within the long-press window, the
            // first thing to happen wins - horizontal slop claims the drag (any speed), a stationary
            // hold reaches the timeout and opens the same menu as the 3-dot button, and a lift before
            // either is a tap (paste). Interaction set unchanged; the winner is deterministic.
            .pointerInput(entry) {
                // Tab's exact arbitration semantics (20:45 spec): the hold timer is cancelled
                // by movement >3dp in ANY direction; the swipe claims at touch slop; the hold
                // only wins if the finger stays within 3dp for the full long-press timeout;
                // and once the Pin menu is open, no later drag in that gesture can delete.
                //
                // One explicit event loop, no slop/drag helper handoff: with helper chaining a
                // fast synthesized swipe (input swipe) could have its UP consumed inside the
                // slop-await under emulator event latency, turning a 600px swipe into a "tap"
                // (cl04 first-attempt failures on 92095f3b7/3fdc035e8 while manual streams
                // passed). Here every event is accounted for in order, so a drag claims on
                // accumulated distance no matter how the events are batched.
                val holdCancelPx = 3.dp.toPx()
                val slopPx = viewConfiguration.touchSlop
                // App-boundary gesture trace (DEBUG only, wb12-check cl04 investigation): what the
                // handler actually received, in order, with receipt vs event timestamps so emulator
                // input-pipeline lag/coalescing is distinguishable from app event handling.
                val traceOn = org.futo.inputmethod.latin.BuildConfig.DEBUG
                fun tr(msg: String) { if (traceOn) android.util.Log.i("WBDBG", "CLIPTOUCH $msg") }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Travel counts every delivered sample (batched history included), so a fast
                    // swipe under event coalescing still measures its full distance (WrenDragAccum).
                    val accum = DragAccum()
                    accum.reset(down.position.x, down.position.y, down.uptimeMillis)
                    tr("DOWN rx=${android.os.SystemClock.uptimeMillis()} ev=${down.uptimeMillis} x=${down.position.x.toInt()} y=${down.position.y.toInt()} slop=${slopPx.toInt()} holdCancel=${holdCancelPx.toInt()} thr=${WrenSwipeJudge.DELETE_THRESHOLD_PX.toInt()}")
                    var holdCancelledByMove = false
            // Peak travel across the whole gesture, advanced from EVERY sample including a
            // coalesced UP event's samples, and consulted BEFORE any paste/swipe decision. Net
            // dy is insufficient: a scroll out and back near the origin nets ~0.
            var maxAbsDx = 0f
            var maxAbsDy = 0f
            fun notePeaks() {
                val ax = kotlin.math.abs(accum.dx); if (ax > maxAbsDx) maxAbsDx = ax
                val ay = kotlin.math.abs(accum.dy); if (ay > maxAbsDy) maxAbsDy = ay
            }
            // Vertical/scroll ownership LATCHES once crossed: a later horizontal turn never
            // revokes it, and an already-consumed change (the scrollable took the pointer)
            // latches it too. gestureOver records an already-delivered UP so no branch waits
            // for a second UP and steals the next gesture.
            var verticalOwned = false
            var scrollConsumed = false
            var gestureOver = false
            fun verticalClaimed() = maxAbsDy > slopPx && maxAbsDy > maxAbsDx
            // Per-sample advance, latching vertical ownership AT the sample where the threshold
            // is crossed: a larger horizontal move later in the SAME batch cannot take the
            // gesture back, and an out-and-back excursion still registers its excursion peak
            // BEFORE any lift decision.
            fun advancePeaks(samples: List<DragSample>) {
                for (smp in samples) {
                    accum.advance(listOf(smp)); notePeaks()
                    if (!verticalOwned && verticalClaimed()) verticalOwned = true
                }
            }
                    var dragClaimed = false
                    var lifted = false
                    var cancelled = false
                    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            advancePeaks(change.allSamples())
                            tr("EV rx=${android.os.SystemClock.uptimeMillis()} ev=${change.uptimeMillis} x=${change.position.x.toInt()} hist=${change.historical.size} accDx=${accum.dx.toInt()} up=${change.changedToUp()} pressed=${change.pressed} phase=hold")
                            if (change.isConsumed) scrollConsumed = true
                            // changedToUp() is false on an already-consumed UP: record gesture
                            // over from UP-in-any-state or !pressed, BEFORE any wait decision.
                            if (change.changedToUp() || !change.pressed) gestureOver = true
                            if (change.changedToUp()) { if (!verticalOwned && !scrollConsumed) lifted = true; break }
                            if (!change.pressed) { cancelled = true; break }
                            if (verticalOwned || scrollConsumed) break
                            if (abs(accum.dx) > holdCancelPx || abs(accum.dy) > holdCancelPx) {
                                holdCancelledByMove = true
                                break
                            }
                        }
                    } == null
                    when {
                        cancelled -> { if (!gestureOver) waitForUpOrCancellation() }
                        timedOut && !lifted && !holdCancelledByMove -> {
                            // stationary hold (within 3dp for the whole timeout): the same Pin
                            // menu the 3-dot button opens. From here this gesture can only end -
                            // a drag after the menu opened never deletes.
                            w.menuFor.value = entry
                            waitForUpOrCancellation()
                        }
                        verticalOwned || scrollConsumed -> { if (!gestureOver) waitForUpOrCancellation() }
                        lifted && WrenSwipeJudge.liftOutcome(accum.dx, slopPx) == WrenSwipeJudge.LiftOutcome.JudgeSwipe -> { dragX.value = accum.dx; finishSwipe(true) }
                        lifted -> w.paste(entry, view)
                        else -> {
                            // hold timer cancelled by >3dp movement: watch for the horizontal
                            // drag claim at touch slop; a lift before slop pastes.
                            // A vertical-dominant move beyond slop means the list owns the
                            // gesture as a scroll: its lift must neither paste nor start a
                            // delete swipe (1.4 behaviour; the dx-only lift judgment pasted
                            // the clip under the finger after a scroll).
                            while (!verticalOwned && !scrollConsumed && !dragClaimed && !lifted && !cancelled) {
                                if (abs(accum.dx) > slopPx) {
                                    dragClaimed = true
                                    dragging.value = true
                                    dragX.value = accum.dx
                                } else {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    advancePeaks(change.allSamples())
                                    tr("EV rx=${android.os.SystemClock.uptimeMillis()} ev=${change.uptimeMillis} x=${change.position.x.toInt()} hist=${change.historical.size} accDx=${accum.dx.toInt()} accDy=${accum.dy.toInt()} up=${change.changedToUp()} pressed=${change.pressed} phase=slopwatch")
                                    if (change.isConsumed) scrollConsumed = true
                                    if (change.changedToUp() || !change.pressed) gestureOver = true
                                    if (change.changedToUp()) { if (!verticalOwned && !scrollConsumed) lifted = true }
                                    else if (!change.pressed) cancelled = true
                                }
                            }
                            if (verticalOwned || scrollConsumed) {
                                if (!gestureOver) waitForUpOrCancellation()
                            } else if (dragClaimed) {
                                // keep following the finger until lift/cancel. The UP check must
                                // come BEFORE consume(): changedToUp() returns false once the
                                // change is consumed, so consuming first misread every lift as a
                                // cancellation and the drag always snapped back (cl04/cl05v).
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) {
                                        // The lift is part of the swipe: count the UP event's own
                                        // samples (its position and any batched history) BEFORE
                                        // judging. Fast swipes coalesce their tail into the UP
                                        // event - judging without it truncated a delivered 600px
                                        // swipe to whatever the last MOVE happened to carry
                                        // (cl04 flip-flop: pass/fail was delivery weather).
                                        accum.advance(change.allSamples())
                                        dragX.value = accum.dx
                                        tr("UP rx=${android.os.SystemClock.uptimeMillis()} ev=${change.uptimeMillis} x=${change.position.x.toInt()} hist=${change.historical.size} accDx=${accum.dx.toInt()} phase=drag")
                                        change.consume(); lifted = true; break
                                    }
                                    if (!change.pressed) { tr("CANCEL rx=${android.os.SystemClock.uptimeMillis()} accDx=${accum.dx.toInt()} phase=drag"); cancelled = true; break }
                                    accum.advance(change.allSamples())
                                    tr("EV rx=${android.os.SystemClock.uptimeMillis()} ev=${change.uptimeMillis} x=${change.position.x.toInt()} hist=${change.historical.size} accDx=${accum.dx.toInt()} phase=drag")
                                    dragX.value = accum.dx
                                    change.consume()
                                }
                                finishSwipe(lifted)
                            } else if (lifted) {
                                // A lift before slop is a tap (paste); a lift after slop is a
                                // finished or abandoned swipe - judge it, never paste mid-drag.
                                if (WrenSwipeJudge.liftOutcome(accum.dx, slopPx) == WrenSwipeJudge.LiftOutcome.JudgeSwipe) { dragX.value = accum.dx; finishSwipe(true) } else w.paste(entry, view)
                            }
                        }
                    }
                }
            }
            // a11y click action for the card (the gesture arbitration above owns actual touches)
            .semantics { onClick(label = null) { w.paste(entry, view); true } }
            .offset { IntOffset((if (dragging.value) dragX.value else offsetX.value).roundToInt(), 0) }
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .wbPos("CLIPCARD i=$index pinned=${entry.pinned}")
            .clip(shape)
            .background(WrenPanelColors.Card, shape)
            .border(1.dp, WrenPanelColors.CardBorder, shape)

    ) {
        val bitmap = remember(entry) {
            if (entry.text == null && entry.backingFile != null) {
                val thumbnail = ClipboardUtil.thumbnailFor(File(context.clipboardDir, entry.backingFile))
                if (thumbnail.exists()) ClipboardThumbCache.getOrPut(thumbnail.name) {
                    android.graphics.BitmapFactory.decodeFile(thumbnail.absolutePath)?.asImageBitmap()
                } else null
            } else null
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
        } else {
            Text(
                entry.text ?: "",
                color = orange,
                style = TextStyle(fontSize = 16.sp, lineHeight = 18.7.sp),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.5.dp, top = 8.dp, end = 50.dp, bottom = if (entry.pinned) 30.dp else 12.dp)
            )
        }

        // 3-dot menu: Pin/Unpin, Paste, Edit, Delete.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 6.dp)) {
            Box(
                Modifier.size(width = 30.dp, height = 40.dp).wbPos("CLIPDOTS i=$index").clip(RoundedCornerShape(15.dp))
                    .clickable { w.menuFor.value = entry },
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    repeat(3) { Box(Modifier.size(4.dp).background(orange, RoundedCornerShape(2.dp))) }
                }
            }
            if (w.menuFor.value == entry) {
                WrenClipMenu(w, entry, view, onDismiss = { w.menuFor.value = null })
            }
        }

        if (entry.pinned) {
            WrenIcon(
                R.drawable.wren_ic_push_pin_fill, orange, 24.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 12.dp).rotate(-45f)
            )
        }
    }
}

@Composable
private fun WrenClipMenu(w: WrenClipboardWindow, entry: ClipboardEntry, view: View, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 110),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
    ) {
        val shape = RoundedCornerShape(8.5.dp)
        Column(
            Modifier.width(126.dp).background(WrenPanelColors.Menu, shape).border(1.dp, WrenPanelColors.MenuBorder, shape)
                .padding(vertical = 4.dp)
        ) {
            WrenMenuItem(R.drawable.wren_ic_push_pin, stringResource(if (entry.pinned) R.string.wren_clip_unpin else R.string.wren_clip_pin)) {
                onDismiss(); w.history.onTogglePin(entry)
            }
            WrenMenuItem(R.drawable.wren_ic_content_paste, stringResource(R.string.wren_clip_paste)) {
                onDismiss(); w.paste(entry, view)
            }
            if (entry.text != null) {
                WrenMenuItem(R.drawable.wren_ic_edit, stringResource(R.string.wren_clip_edit)) {
                    onDismiss(); w.editText.value = entry.text; w.editing.value = entry
                }
            }
            WrenMenuItem(R.drawable.wren_ic_delete, stringResource(R.string.wren_clip_delete)) {
                onDismiss(); w.removeWithUndo(listOf(entry), resources.getString(R.string.wren_clip_deleted))
            }
        }
    }
}

@Composable
private fun WrenMenuItem(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(43.dp).wbPos("CLIPMENU name=$label").clickable(onClick = onClick).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WrenIcon(icon, orange, 19.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = orange, fontSize = 15.sp)
    }
}
