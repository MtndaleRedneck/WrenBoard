package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** Panel heights measured at 1080 px wide / 420 dpi (2.625 px per dp): clipboard 1124 px, emoji 1365 px. */
val WrenClipboardPanelHeight: Dp = 428.dp
val WrenEmojiPanelHeight: Dp = 520.dp
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
    // offset), so the card moving under the finger cannot shrink or cancel the drag; a cancelled drag is judged
    // the same way as a finished one, and the delete never waits on the slide-out animation.
    val finishSwipe: () -> Unit = {
        val x = dragX.value
        dragging.value = false
        if (abs(x) > 180f) {
            w.removeWithUndo(listOf(entry), resources.getString(R.string.wren_clip_deleted))
            scope.launch { offsetX.snapTo(x); try { offsetX.animateTo(if (x > 0) 1400f else -1400f) } catch (_: Exception) { } }
        } else {
            scope.launch { offsetX.snapTo(x); try { offsetX.animateTo(0f) } catch (_: Exception) { } }
        }
        dragX.value = 0f
    }

    Box(
        Modifier
            .pointerInput(entry) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging.value = true; dragX.value = offsetX.value },
                    onDragEnd = { finishSwipe() },
                    onDragCancel = { finishSwipe() },
                    onHorizontalDrag = { change, delta -> change.consume(); dragX.value += delta }
                )
            }
            .offset { IntOffset((if (dragging.value) dragX.value else offsetX.value).roundToInt(), 0) }
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .wbPos("CLIPCARD i=$index pinned=${entry.pinned}")
            .clip(shape)
            .background(WrenPanelColors.Card, shape)
            .border(1.dp, WrenPanelColors.CardBorder, shape)
            .combinedClickable(
                onClick = { w.paste(entry, view) },
                onLongClick = { w.menuFor.value = entry }
            )
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
