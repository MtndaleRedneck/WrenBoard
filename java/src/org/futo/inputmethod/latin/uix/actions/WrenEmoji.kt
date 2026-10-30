package org.futo.inputmethod.latin.uix.actions

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.EmojiTracker.getRecentEmojis
import org.futo.inputmethod.latin.uix.EmojiTracker.useEmoji
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalManager
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.WbDebug
import org.futo.inputmethod.latin.uix.actions.clipboard.WrenEmojiPanelHeight
import org.futo.inputmethod.latin.uix.actions.clipboard.wbPos
import org.futo.inputmethod.latin.uix.actions.emoji.EmojiItem
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.LocalCompatEmojiFamily
import org.futo.inputmethod.latin.uix.theme.LocalCompatEmojiTypeface
import org.futo.inputmethod.latin.uix.theme.emojiNeedsCompat

/** Emojis locked to the top of the Recents page, newest lock last. No limit (Ezzy, Oct 30). */
val WrenLockedEmojis = SettingsKey(stringPreferencesKey("wren_locked_emojis"), "")
private const val SEP = "<|>"
fun String.toLockedList(): List<String> = split(SEP).filter { it.isNotEmpty() }

/** Measured from the approved renders (wrenboard_v4_swiftkey s01-s05). */
private object EmojiColors {
    val Orange = Color(0xFFFF8714)
    val OrangeMuted = Color(0xFFC87A30)
    val OrangeText = Color(0xFFB9772F)
    val Bar = Color(0xFF3C3C3C)
    val Sep = Color(0xFF333333)
    val GridTop = Color(0xFF4A4844)
    val GridMid = Color(0xFF45433F)
    val GridBottom = Color(0xFF3E3C38)
    val Field = Color(0xFF2F2F2F)
    val FieldBorder = Color(0xFF474747)
    val Arrow = Color(0xFF8D8D8D)
    val Triangle = Color(0xFF8A8A8A)
    val Divider = Color(0xFF6A5A4A)
    val Popup = Color(0xFF2A2928)
    val PopupBorder = Color(0xFF5C5650)
    val PopupSel = Color(0xFF4A4744)
    val Chip = Color(0xFF4A4744)
}

/** One page of the emoji panel: Recents, then the categories (Smileys and People share a page, as in SwiftKey). */
private data class EmojiPage(val key: String, val icon: Int, val iconActive: Int, val categories: List<String>)

private val emojiPages = listOf(
    EmojiPage("Recent", R.drawable.wren_ic_schedule, R.drawable.wren_ic_schedule_fill, emptyList()),
    EmojiPage("Smileys", R.drawable.wren_ic_sentiment_satisfied, R.drawable.wren_ic_sentiment_satisfied_fill, listOf("Smileys & Emotion", "People & Body")),
    EmojiPage("Animals", R.drawable.wren_ic_pets, R.drawable.wren_ic_pets, listOf("Animals & Nature")),
    EmojiPage("Food", R.drawable.wren_ic_sports_bar, R.drawable.wren_ic_sports_bar_fill, listOf("Food & Drink")),
    EmojiPage("Travel", R.drawable.wren_ic_directions_car, R.drawable.wren_ic_directions_car_fill, listOf("Travel & Places")),
    EmojiPage("Activities", R.drawable.wren_ic_trophy, R.drawable.wren_ic_trophy_fill, listOf("Activities")),
    EmojiPage("Objects", R.drawable.wren_ic_lightbulb, R.drawable.wren_ic_lightbulb_fill, listOf("Objects")),
    EmojiPage("Symbols", R.drawable.wren_ic_emoji_symbols, R.drawable.wren_ic_emoji_symbols, listOf("Symbols")),
    EmojiPage("Flags", R.drawable.wren_ic_flag, R.drawable.wren_ic_flag_fill, listOf("Flags")),
    EmojiPage("ASCII", R.drawable.ic_emoji_emoticons_activated_lxx_dark, R.drawable.ic_emoji_emoticons_activated_lxx_dark, listOf("ASCII")),
)

class WrenEmojiWindow(val manager: KeyboardManagerForAction) : ActionWindow() {
    /** 0 = Emoji, 1 = GIF. The toolbar GIF button opens straight onto the GIF tab. */
    val tab = mutableStateOf(if (EmojiPanelRequest.openOnGifTab) 1 else 0)
    val searching = mutableStateOf(false)
    val searchText = mutableStateOf("")

    init { EmojiPanelRequest.openOnGifTab = false }

    override val showTitleBar: Boolean get() = false
    override val positionIsUserManagable: Boolean get() = false
    override val showCloseButton: Boolean get() = false
    override val fixedWindowHeight: Dp get() = if (searching.value) 104.dp else WrenEmojiPanelHeight

    @Composable
    override fun windowName(): String = stringResource(R.string.action_emoji_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        WrenEmojiPanel(this)
    }

    override fun close(): CloseResult {
        searching.value = false
        searchText.value = ""
        return CloseResult.Default
    }

    fun endSearch() {
        searching.value = false
        searchText.value = ""
        manager.forceActionWindowAboveKeyboard(false)
    }

    fun type(item: EmojiItem, view: View) {
        if (item.category == "ASCII") manager.typeTextSurroundedByWhitespace(item.emoji) else manager.typeText(item.emoji)
        manager.getLifecycleScope().launch { manager.getContext().useEmoji(item.emoji) }
        manager.performHapticAndAudioFeedback(Constants.CODE_EMOJI, view)
    }
}

@Composable
private fun Ic(id: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

@Composable
private fun EmojiGlyph(text: String, sizeDp: Float, modifier: Modifier = Modifier) {
    val typeface = LocalCompatEmojiTypeface.current
    val family = LocalCompatEmojiFamily.current
    val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
    Text(
        text,
        fontSize = fontSize,
        lineHeight = fontSize,
        textAlign = TextAlign.Center,
        fontFamily = if (emojiNeedsCompat(text, typeface)) family else null,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
fun WrenEmojiPanel(w: WrenEmojiWindow) {
    val view = LocalView.current
    val emojis = PersistentEmojiState.emojis.value ?: return
    val emojiMap = PersistentEmojiState.emojiMap

    if (w.searching.value) {
        WrenEmojiSearch(w, emojis, view)
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Tab row, 136 px (52 dp): back, Emoji (icon + label + underline), GIF.
        Row(Modifier.fillMaxWidth().height(52.dp).background(EmojiColors.Bar), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clickable {
                w.manager.closeActionWindow()
                w.manager.performHapticAndAudioFeedback(Constants.CODE_SWITCH_ALPHA_SYMBOL, view)
            }, contentAlignment = Alignment.Center) { Ic(R.drawable.wren_ic_arrow_back_ios_new, EmojiColors.Orange, 18.dp) }
            Box(Modifier.width(93.dp).fillMaxHeight().clickable { w.tab.value = 0 }) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Ic(if (w.tab.value == 0) R.drawable.wren_ic_sentiment_satisfied_fill else R.drawable.wren_ic_sentiment_satisfied,
                        if (w.tab.value == 0) EmojiColors.Orange else EmojiColors.OrangeMuted, 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_emoji_title), color = if (w.tab.value == 0) EmojiColors.Orange else EmojiColors.OrangeMuted,
                        fontSize = 15.6.sp, fontWeight = FontWeight.Medium)
                }
                if (w.tab.value == 0) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).width(93.dp).height(2.dp).background(EmojiColors.Orange, RoundedCornerShape(1.dp)))
            }
            Box(Modifier.width(56.dp).fillMaxHeight().wbPos("EMOJIGIFTAB").clickable { w.tab.value = 1 }) {
                Ic(if (w.tab.value == 1) R.drawable.wren_ic_gif_box_fill else R.drawable.wren_ic_gif_box,
                    if (w.tab.value == 1) EmojiColors.Orange else EmojiColors.OrangeMuted, 22.dp, Modifier.align(Alignment.Center))
                if (w.tab.value == 1) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).width(40.dp).height(2.dp).background(EmojiColors.Orange, RoundedCornerShape(1.dp)))
            }
        }

        // Search row: field 945 x 98 px, round arrow 96 px.
        Row(Modifier.fillMaxWidth().height(38.dp).background(EmojiColors.Bar).padding(horizontal = 4.5.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(37.dp).clip(RoundedCornerShape(6.dp))
                    .background(EmojiColors.Field).border(1.dp, EmojiColors.FieldBorder, RoundedCornerShape(6.dp))
                    .clickable { if (w.tab.value == 0) w.searching.value = true }
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ic(R.drawable.wren_ic_search, EmojiColors.OrangeMuted, 19.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (w.tab.value == 0) R.string.wren_search_emojis else R.string.wren_search_gifs), color = EmojiColors.OrangeText, fontSize = 16.sp)
            }
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).background(EmojiColors.Arrow).clickable {
                w.manager.sendCodePointEvent(Constants.CODE_ENTER)
            }, contentAlignment = Alignment.Center) { Ic(R.drawable.wren_ic_arrow_forward, Color(0xFF333333), 21.dp) }
        }
        Box(Modifier.fillMaxWidth().height(2.7.dp).background(EmojiColors.Sep))

        if (w.tab.value == 1) {
            WrenGifPlaceholder(Modifier.weight(1f))
            return@Column
        }

        val pagerState = rememberPagerState(initialPage = 1) { emojiPages.size }
        val scope = rememberCoroutineScope()
        LaunchedEffect(pagerState.currentPage) { WbDebug.logWindow("EMOJI page=${emojiPages[pagerState.currentPage].key}") }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth().wbPos("EMOJIGRID").background(
            Brush.verticalGradient(listOf(EmojiColors.GridTop, EmojiColors.GridMid, EmojiColors.GridBottom))
        )) { page ->
            if (page == 0) WrenRecentsPage(w, emojis, emojiMap, view)
            else WrenCategoryPage(w, emojiPages[page], emojis, emojiMap, view)
        }

        // Bottom row 123 px (47 dp): abc, search, recents + categories (scroll), backspace.
        Row(Modifier.fillMaxWidth().height(47.dp).background(EmojiColors.Bar), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp).fillMaxHeight().clickable {
                w.manager.closeActionWindow()
                w.manager.performHapticAndAudioFeedback(Constants.CODE_SWITCH_ALPHA_SYMBOL, view)
            }, contentAlignment = Alignment.Center) { Text("abc", color = EmojiColors.Orange, fontSize = 17.9.sp) }
            Box(Modifier.width(39.dp).fillMaxHeight().wbPos("EMOJISEARCH").clickable { w.searching.value = true }, contentAlignment = Alignment.Center) {
                Ic(R.drawable.wren_ic_search, EmojiColors.OrangeMuted, 24.dp)
            }
            LazyRow(Modifier.weight(1f).fillMaxHeight()) {
                items(emojiPages.size) { i ->
                    val active = pagerState.currentPage == i
                    Box(Modifier.width(39.dp).fillMaxHeight().wbPos("EMOJITAB i=$i key=${emojiPages[i].key}").clickable { scope.launch { pagerState.scrollToPage(i) } }, contentAlignment = Alignment.Center) {
                        Ic(if (active) emojiPages[i].iconActive else emojiPages[i].icon, if (active) EmojiColors.Orange else EmojiColors.OrangeMuted, 24.dp)
                        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).width(27.dp).height(2.3.dp).background(EmojiColors.Orange, RoundedCornerShape(1.dp)))
                    }
                }
            }
            Box(Modifier.width(44.dp).fillMaxHeight().repeatablyClickableAction { repeated ->
                w.manager.backspace(1)
                if (!repeated) w.manager.performHapticAndAudioFeedback(Constants.CODE_DELETE, view)
            }, contentAlignment = Alignment.Center) { Ic(R.drawable.wren_ic_backspace_fill, EmojiColors.Orange, 30.dp) }
        }
    }
}

/** One emoji cell: 135 x 132 px (51.4 x 50.3 dp), glyph 84 px (32 dp); grey corner triangle when it has skin tones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(
    w: WrenEmojiWindow, item: EmojiItem, view: View, locked: Boolean, lockedSet: Set<String>,
    emojiMap: Map<String, EmojiItem>, setLocked: (String, Boolean) -> Unit
) {
    val popup = remember { mutableStateOf(false) }
    val base = item.untoned
    val isLocked = lockedSet.contains(item.emoji) || lockedSet.contains(base)
    Box(
        Modifier.fillMaxWidth().height(50.3.dp)
            .combinedClickable(onClick = { w.type(item, view) }, onLongClick = { popup.value = true }),
        contentAlignment = Alignment.TopCenter
    ) {
        EmojiGlyph(item.emoji, if (item.category == "ASCII") 16f else 29.8f, Modifier.padding(top = 9.dp))
        val hasTones = emojiMap[base]?.skinTones == true || item.skinTones
        if (hasTones) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 0.dp).size(7.6.dp)) {
                val p = Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(p, EmojiColors.Triangle)
            }
        }
        if (locked) {
            Box(
                Modifier.align(Alignment.TopStart).offset(x = 34.3.dp, y = 4.dp).size(14.5.dp).clip(CircleShape)
                    .background(EmojiColors.GridTop).padding(1.1.dp).clip(CircleShape).background(EmojiColors.Orange),
                contentAlignment = Alignment.Center
            ) { Ic(R.drawable.wren_ic_lock_fill, Color(0xFF1D1D1D), 8.4.dp) }
        }
        if (popup.value) {
            EmojiPopup(w, item, view, isLocked, emojiMap, onDismiss = { popup.value = false }, setLocked = setLocked)
        }
    }
}

/** Long-press: SwiftKey's skin-tone row, with Lock (or Unlock) added at the end. */
@Composable
private fun EmojiPopup(
    w: WrenEmojiWindow, item: EmojiItem, view: View, isLocked: Boolean, emojiMap: Map<String, EmojiItem>,
    onDismiss: () -> Unit, setLocked: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val base = item.untoned
    val hasTones = emojiMap[base]?.skinTones == true
    val variants = if (hasTones) generateSkinToneVariants(base, emojiMap) else listOf(TonedEmoji(item.emoji, null))
    LaunchedEffect(Unit) { WbDebug.logWindow("EMOJI popup ${item.emoji} variants=${variants.size} locked=$isLocked") }
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, -with(LocalDensity.current) { 52.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
    ) {
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.height(52.dp).background(EmojiColors.Popup, shape).border(1.dp, EmojiColors.PopupBorder, shape).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            variants.forEachIndexed { i, v ->
                Box(
                    Modifier.size(width = 40.dp, height = 41.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (v.emoji == item.emoji || (i == 0 && variants.none { it.emoji == item.emoji })) EmojiColors.PopupSel else Color.Transparent)
                        .clickable {
                            w.manager.typeText(v.emoji)
                            w.manager.getLifecycleScope().launch {
                                w.manager.getContext().useEmoji(v.emoji)
                                if (v.skinTone != null) context.setSetting(LastUsedSkinTone, v.skinTone)
                            }
                            w.manager.performHapticAndAudioFeedback(Constants.CODE_EMOJI, view)
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) { EmojiGlyph(v.emoji, 27.6f) }
                if (i < variants.size - 1) Spacer(Modifier.width(2.7.dp))
            }
            Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(40.dp).background(Color(0xFF4F4A45)))
            Column(
                Modifier.width(52.dp).fillMaxHeight().wbPos("EMOJILOCKBTN").clickable {
                    setLocked(item.emoji, !isLocked)
                    onDismiss()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Ic(if (isLocked) R.drawable.wren_ic_lock_open else R.drawable.wren_ic_lock_fill, EmojiColors.Orange, 20.dp)
                Text(stringResource(if (isLocked) R.string.wren_emoji_unlock else R.string.wren_emoji_lock), color = EmojiColors.Orange, fontSize = 11.4.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun rememberLocked(): Pair<List<String>, (String, Boolean) -> Unit> {
    val (lockedRaw, setLockedRaw) = useDataStore(WrenLockedEmojis)
    val locked = remember(lockedRaw) { lockedRaw.toLockedList() }
    val setLocked: (String, Boolean) -> Unit = { emoji, lock ->
        val now = lockedRaw.toLockedList().filter { it != emoji }
        val next = if (lock) now + emoji else now
        setLockedRaw(next.joinToString(SEP))
        WbDebug.logWindow("EMOJI ${if (lock) "lock" else "unlock"} $emoji count=${next.size}")
    }
    return locked to setLocked
}

@Composable
private fun WrenRecentsPage(w: WrenEmojiWindow, emojis: List<EmojiItem>, emojiMap: Map<String, EmojiItem>, view: View) {
    val context = LocalContext.current
    val (locked, setLocked) = rememberLocked()
    val recents = remember { runBlocking { context.getRecentEmojis() } }
    val lockedSet = locked.toSet()
    fun itemFor(e: String) = emojiMap[e] ?: EmojiItem(e, description = "", category = "", skinTones = false)
    val recentItems = recents.filter { it !in lockedSet }.map { itemFor(it) }
    LaunchedEffect(locked.size, recentItems.size) { WbDebug.logWindow("EMOJI recents locked=${locked.size} recent=${recentItems.size}") }
    LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 0.dp, bottom = 8.dp)) {
        items(locked.size, key = { "L" + locked[it] }) { i ->
            EmojiCell(w, itemFor(locked[i]), view, locked = true, lockedSet = lockedSet, emojiMap = emojiMap, setLocked = setLocked)
        }
        if (locked.isNotEmpty()) {
            item(key = "divider", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().height(11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(10.dp))
                    Ic(R.drawable.wren_ic_lock_fill, EmojiColors.OrangeMuted, 11.dp)
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(EmojiColors.Divider))
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        items(recentItems.size, key = { "R" + recentItems[it].emoji }) { i ->
            EmojiCell(w, recentItems[i], view, locked = false, lockedSet = lockedSet, emojiMap = emojiMap, setLocked = setLocked)
        }
    }
}

@Composable
private fun WrenCategoryPage(w: WrenEmojiWindow, page: EmojiPage, emojis: List<EmojiItem>, emojiMap: Map<String, EmojiItem>, view: View) {
    val skinTone = useDataStoreValue(LastUsedSkinTone)
    val typeface = LocalCompatEmojiTypeface.current
    val (locked, setLocked) = rememberLocked()
    val lockedSet = locked.toSet()
    val items = remember(page.key, skinTone) {
        emojis.filter { it.category in page.categories }.map { transformEmojiForLastUsedSkinTone(skinTone, it, emojiMap, typeface) }
    }
    val ascii = page.key == "ASCII"
    LazyVerticalGrid(columns = GridCells.Fixed(if (ascii) 4 else 8), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(items.size, key = { items[it].emoji + it }) { i ->
            EmojiCell(w, items[i], view, locked = false, lockedSet = lockedSet, emojiMap = emojiMap, setLocked = setLocked)
        }
    }
}

/** Search: the field and one row of results above the letters keyboard (render s04). */
@Composable
private fun WrenEmojiSearch(w: WrenEmojiWindow, emojis: List<EmojiItem>, view: View) {
    val context = LocalContext.current
    val locales = LocalManager.current.let { remember { it.getActiveLocales() } }
    LaunchedEffect(locales) { locales.forEach { PersistentEmojiState.loadTranslationsForLanguage(context, it) } }
    val translations = PersistentEmojiState.getTranslationForLocales(locales)
    val query = w.searchText.value
    val results = if (query.isBlank()) emptyList() else emojis.filter { it.category != "ASCII" }.searchMultiple2(query) { item ->
        translations?.let {
            it.emojiToNames[item.emoji]?.names
                ?: it.emojiToNames[item.emoji.replace("\uFE0F", "")]?.names
                ?: it.emojiToNames[item.emoji + "\uFE0F"]?.names
        } ?: listOf(item.description)
    }.distinctBy { it.emoji }.take(40)
    LaunchedEffect(query, results.size) { WbDebug.logWindow("EMOJI search q=$query results=${results.size}") }

    Column(Modifier.fillMaxSize().background(EmojiColors.Bar)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { w.endSearch() }, contentAlignment = Alignment.Center) {
                Ic(R.drawable.wren_ic_arrow_back_ios_new, EmojiColors.Orange, 18.dp)
            }
            Row(
                Modifier.weight(1f).height(37.dp).clip(RoundedCornerShape(6.dp))
                    .background(EmojiColors.Field).border(1.dp, EmojiColors.Orange, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ic(R.drawable.wren_ic_search, EmojiColors.OrangeMuted, 19.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { ActionTextEditor(text = w.searchText, placeholder = stringResource(R.string.wren_search_emojis)) }
            }
        }
        LazyRow(Modifier.fillMaxWidth().weight(1f).background(EmojiColors.GridTop), verticalAlignment = Alignment.CenterVertically) {
            items(results, key = { it.emoji }) { item ->
                Box(Modifier.width(51.4.dp).fillMaxHeight().clickable { w.type(item, view) }, contentAlignment = Alignment.Center) {
                    EmojiGlyph(item.emoji, 29.8f)
                }
            }
        }
    }
}

/**
 * GIF tab. No free GIF source is wired in (every GIF service needs an account key), so the tab shows its
 * search field and mood chips with an empty grid. Flagged as open in the 1.2 report.
 */
@Composable
private fun WrenGifPlaceholder(modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(EmojiColors.GridTop, EmojiColors.GridMid, EmojiColors.GridBottom)))) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.wren_gif_not_connected), color = EmojiColors.OrangeText, fontSize = 15.sp)
        }
        Row(
            Modifier.fillMaxWidth().height(47.dp).background(EmojiColors.Bar).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(R.string.wren_gif_recents, R.string.wren_gif_happy, R.string.wren_gif_sad, R.string.wren_gif_laugh).forEach {
                Text(stringResource(it), color = EmojiColors.Orange, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(EmojiColors.Chip).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}
