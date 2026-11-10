package org.futo.inputmethod.latin.uix.actions

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import org.futo.inputmethod.latin.uix.gif.KlipyClient
import org.futo.inputmethod.latin.uix.gif.KlipyClient.KlipyGif
import org.futo.inputmethod.latin.uix.gif.klipyCustomerId
import org.futo.inputmethod.latin.uix.gif.KlipyInsert
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
import org.futo.inputmethod.latin.uix.theme.emojiCompatText
import org.futo.inputmethod.latin.uix.theme.emojiNeedsCompat


val WrenLockedEmojis = SettingsKey(stringPreferencesKey("wren_locked_emojis"), "")
private const val SEP = "<|>"
fun String.toLockedList(): List<String> = split(SEP).filter { it.isNotEmpty() }


private object EmojiColors {
    val Orange = Color(0xFFFF8710)
    val OrangeMuted = Color(0xFFB16922)
    val OrangeText = Color(0xFFC06C19)
    val Bar = Color(0xFF3C3C3C)
    val GridTop = Color(0xFF4C4A47)
    val GridBottom = Color(0xFF393735)
    val GifTop = Color(0xFF4C4A47)
    val GifBottom = Color(0xFF373533)
    val Field = Color(0xFF303030)
    val FieldBorder = Color(0xFF4C4C4C)
    val Arrow = Color(0xFF8E8E8E)
    val ArrowGlyph = Color(0xFF2A2A2A)
    val BackspaceGlyph = Color(0xFF2E2E2E)
    val Triangle = Color(0xFF9A9A9A)
    val Divider = Color(0xFF6A5A4A)
    val Popup = Color(0xFF2A2928)
    val PopupBorder = Color(0xFF5C5650)
    val PopupSel = Color(0xFF4A4744)
    val Chip = Color(0xFF4A4744)
}

/** One page of the emoji panel: Recents, then the categories (Smileys and People share a page, as in SwiftKey). */
private data class EmojiPage(val key: String, val icon: Int, val iconActive: Int, val categories: List<String>)

/**
 * WrenBoard 1.6 (item 9, mock-up 09): page 0 is "Quick emoji" (most used), shown under the search icon, and the
 * panel opens on it. The bottom bar uses outline icons only; the selected one is brighter with an orange underline.
 */
private const val QUICK_PAGE = 0
private const val QUICK_COUNT = 24 // 3 rows of 8

/** Fills Quick emoji up to 3 rows until the user has used that many (popular defaults, as in the mock-up). */
private val QuickDefaults = listOf(
    "😂", "😘", "😊", "😍", "🙏", "👍", "❤️", "😭",
    "🥰", "😁", "😉", "🤣", "😅", "🔥", "🎉", "👏",
    "😢", "🤔", "😎", "🙌", "💪", "😴", "😮", "👌",
)

private val emojiPages = listOf(
    EmojiPage("Quick", R.drawable.wren_ic_line_search, R.drawable.wren_ic_line_search, emptyList()),
    EmojiPage("Recent", R.drawable.wren_ic_line_clock, R.drawable.wren_ic_line_clock, emptyList()),
    EmojiPage("Smileys", R.drawable.wren_ic_line_smiley, R.drawable.wren_ic_line_smiley, listOf("Smileys & Emotion", "People & Body")),
    EmojiPage("Animals", R.drawable.wren_ic_line_cat, R.drawable.wren_ic_line_cat, listOf("Animals & Nature")),
    EmojiPage("Food", R.drawable.wren_ic_line_cup, R.drawable.wren_ic_line_cup, listOf("Food & Drink")),
    EmojiPage("Travel", R.drawable.wren_ic_line_car, R.drawable.wren_ic_line_car, listOf("Travel & Places")),
    EmojiPage("Activities", R.drawable.wren_ic_line_ball, R.drawable.wren_ic_line_ball, listOf("Activities")),
    EmojiPage("Objects", R.drawable.wren_ic_line_bulb, R.drawable.wren_ic_line_bulb, listOf("Objects")),
    EmojiPage("Symbols", R.drawable.wren_ic_emoji_symbols, R.drawable.wren_ic_emoji_symbols, listOf("Symbols")),
    EmojiPage("Flags", R.drawable.wren_ic_flag, R.drawable.wren_ic_flag_fill, listOf("Flags")),
    EmojiPage("ASCII", R.drawable.ic_emoji_emoticons_activated_lxx_dark, R.drawable.ic_emoji_emoticons_activated_lxx_dark, listOf("ASCII")),
)

class WrenEmojiWindow(val manager: KeyboardManagerForAction) : ActionWindow() {
    /** 0 = Emoji, 1 = GIF. The toolbar GIF button opens straight onto the GIF tab. */
    val tab = mutableStateOf(if (EmojiPanelRequest.openOnGifTab) 1 else 0)
    val searching = mutableStateOf(false)
    val searchText = mutableStateOf("")
    // B2: GIF search state lives here so the top search row and the GIF panel share it,
    // and close() clears it (no stale local capture across editors).
    val gifQuery = mutableStateOf("")
    // "recents" (default, mock-up 10), "trending", or "search" (query drives results)
    val gifMode = mutableStateOf("recents")
    // Opening on Recents with no recent GIFs yet falls back to Trending once, so a new user never sees an empty panel.
    val gifRecentsFallback = mutableStateOf(true)

    // SwiftKey-matching GIF layout (owner reference, Nov 3): the grid fills the whole panel
    // with no letters keyboard below; tapping the search field raises the keyboard, the
    // round arrow dismisses it. gifSearching tracks that focused state.
    val gifSearching = mutableStateOf(false)

    init {
        EmojiPanelRequest.openOnGifTab = false
    }

    override val showTitleBar: Boolean get() = false
    override val positionIsUserManagable: Boolean get() = false
    override val showCloseButton: Boolean get() = false
    // Typing state contains only tab/search rows; suggestions and full letters are below.
    // Results replace letters after the arrow. Runtime pixels determine host visibility.
    override val fixedWindowHeight: Dp
        get() = if (searching.value) 104.dp else if (gifSearching.value) 88.dp else WrenEmojiPanelHeight

    @Composable
    override fun windowName(): String = stringResource(R.string.action_emoji_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        WrenEmojiPanel(this)
    }

    override fun close(): CloseResult {
        searching.value = false
        searchText.value = ""
        gifQuery.value = ""
        gifMode.value = "recents"
        gifRecentsFallback.value = true
        gifSearching.value = false
        manager.forceActionWindowAboveKeyboard(false)
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
private fun Ic(id: Int, tint: Color, size: Dp, modifier: Modifier = Modifier, desc: String? = null) {
    // WrenBoard 1.3 (teardown item 30): icons were all contentDescription=null, so TalkBack had nothing to read.
    Icon(painterResource(id), contentDescription = desc, tint = tint, modifier = modifier.size(size))
}

@Composable
private fun EmojiGlyph(text: String, sizeDp: Float, modifier: Modifier = Modifier) {
    val typeface = LocalCompatEmojiTypeface.current
    val family = LocalCompatEmojiFamily.current
    val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
    if (org.futo.inputmethod.latin.BuildConfig.DEBUG) {
        LaunchedEffect(text, typeface) {
            val systemHas = android.graphics.Paint().hasGlyph(text)
            val compatHas = typeface?.let { android.graphics.Paint().apply { this.typeface = it }.hasGlyph(text) } ?: false
            if (!systemHas || !compatHas) {
                val points = text.codePoints().toArray().joinToString(" ") { "U+" + it.toString(16).uppercase() }
                android.util.Log.d("WBGLYPH", "$points system=$systemHas compat=$compatHas")
            }
        }
    }
    Text(
        if (emojiNeedsCompat(text, typeface)) emojiCompatText(text, typeface) else text,
        fontSize = fontSize,
        lineHeight = fontSize,
        textAlign = TextAlign.Center,
        fontFamily = if (emojiNeedsCompat(text, typeface)) family else null,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * Top tab (mock-ups 09/10): the active tab shows its filled icon and name with an orange underline the width of the
 * tab; the other tab is a 60 dp slot with its outline icon only.
 */
@Composable
private fun WrenTopTab(active: Boolean, label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Boolean) -> Unit) {
    Box(modifier.width(60.dp).fillMaxHeight().semantics { contentDescription = label }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon(active)
            if(active) Text(label, color = EmojiColors.Orange, fontSize = 14.sp, maxLines = 1)
        }
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).fillMaxWidth().height(1.67.dp).background(EmojiColors.Orange))
    }
}

/** The GIF tab icon: a rounded box with "GIF" in it, outlined when idle and filled when selected (drawn, not copied). */
@Composable
private fun GifTabGlyph(active: Boolean) {
    val shape = RoundedCornerShape(2.9.dp)
    val c = if (active) EmojiColors.Orange else EmojiColors.OrangeMuted
    Box(
        Modifier.size(width = 20.6.dp, height = 14.6.dp)
            .then(if (active) Modifier.background(c, shape) else Modifier.border(1.7.dp, c, shape)),
        contentAlignment = Alignment.Center
    ) {
        Text("GIF", color = if (active) EmojiColors.Bar else c, fontSize = with(LocalDensity.current) { 7.3.dp.toSp() },
            fontWeight = FontWeight.Bold, lineHeight = with(LocalDensity.current) { 7.3.dp.toSp() }, maxLines = 1)
    }
}

/** Backspace for the panels' bottom bars (mock-ups 09/10): filled orange key shape with a dark cross, 24 x 23.5 dp. */
@Composable
private fun PanelBackspaceGlyph() {
    Canvas(Modifier.size(width = 24.2.dp, height = 23.5.dp)) {
        val sx = size.width / 68f
        val sy = size.height / 66f
        val p = Path().apply {
            moveTo(24f * sx, 1f * sy); lineTo(64f * sx, 1f * sy)
            quadraticTo(67f * sx, 1f * sy, 67f * sx, 4f * sy); lineTo(67f * sx, 62f * sy)
            quadraticTo(67f * sx, 65f * sy, 64f * sx, 65f * sy); lineTo(24f * sx, 65f * sy)
            lineTo(1f * sx, 33f * sy); close()
        }
        drawPath(p, EmojiColors.Orange)
        val w = 5.5f * sx
        drawLine(EmojiColors.BackspaceGlyph, Offset(32f * sx, 22f * sy), Offset(52f * sx, 44f * sy), w, StrokeCap.Round)
        drawLine(EmojiColors.BackspaceGlyph, Offset(52f * sx, 22f * sy), Offset(32f * sx, 44f * sy), w, StrokeCap.Round)
    }
}

/** The GIF bar's Recents mark: a filled orange disc with dark clock hands. */
@Composable
private fun RecentsDiscGlyph(active: Boolean) {
    val c = if (active) EmojiColors.Orange else EmojiColors.OrangeMuted
    Canvas(Modifier.size(20.6.dp)) {
        val u = size.width / 60f
        drawCircle(c, radius = 29f * u)
        val hands = Path().apply { moveTo(27f * u, 15f * u); lineTo(27f * u, 33f * u); lineTo(41f * u, 33f * u) }
        drawPath(hands, EmojiColors.Bar, style = Stroke(width = 5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
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

    val backDesc = stringResource(R.string.wren_cd_back)
    val bsDesc = stringResource(R.string.wren_cd_backspace)
    Column(Modifier.fillMaxSize()) {
        // Tab row 129 px (46 dp) + search row 118 px (42 dp), as in mock-ups 09/10: back, Emoji tab, GIF tab.
        Box(Modifier.fillMaxWidth().height(46.dp).background(EmojiColors.Bar)) {
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            WrenTopTab(w.tab.value == 0, stringResource(R.string.wren_emoji_tab), Modifier.wbPos("EMOJITAB"),
                onClick = { w.tab.value = 0; w.manager.forceActionWindowAboveKeyboard(false) }) { active ->
                Ic(if(active) R.drawable.wren_ic_sentiment_satisfied_fill else R.drawable.wren_ic_line_smiley, if(active) EmojiColors.Orange else EmojiColors.OrangeMuted, 20.dp)
            }
            WrenTopTab(w.tab.value == 1, stringResource(R.string.wren_gif_tab), Modifier.wbPos("EMOJIGIFTAB"),
                onClick = { w.tab.value = 1; w.gifSearching.value = false; w.manager.forceActionWindowAboveKeyboard(false) }) { active -> GifTabGlyph(active) }
        }
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).fillMaxHeight().semantics { contentDescription = backDesc }.clickable {
                w.manager.closeActionWindow()
                w.manager.performHapticAndAudioFeedback(Constants.CODE_SWITCH_ALPHA_SYMBOL, view)
            }, contentAlignment = Alignment.CenterStart) {
                Icon(painterResource(R.drawable.wren_ic_line_chevron_back), contentDescription = null, tint = EmojiColors.Orange,
                    modifier = Modifier.padding(start = 17.dp).size(width = 9.2.dp, height = 15.6.dp))
            }
        }
        }

        // Search row: field 944 x 98 px (rounded 6 dp), round arrow 98 px.
        Row(Modifier.fillMaxWidth().height(42.dp).background(EmojiColors.Bar).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(34.8.dp).clip(RoundedCornerShape(6.dp))
                    .background(EmojiColors.Field).border(0.33.dp, EmojiColors.FieldBorder, RoundedCornerShape(6.dp))
                    .clickable {
                        if (w.tab.value == 0) w.searching.value = true
                        else { w.gifSearching.value = true; w.manager.forceActionWindowAboveKeyboard(true) }
                    }
                    .padding(start = 8.9.dp, end = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ic(R.drawable.wren_ic_line_search, EmojiColors.OrangeText, 19.5.dp)
                Spacer(Modifier.width(4.3.dp))
                if (w.tab.value == 0) {
                    Text(stringResource(R.string.wren_search_emojis), color = EmojiColors.OrangeText, fontSize = 15.6.sp, maxLines = 1)
                } else if (w.gifSearching.value) {
                    // Typable GIF search field (same action-editor mechanism as emoji/clipboard
                    // search). Shown while the search row has focus; the letters keyboard is
                    // raised for it and dismissed by the round arrow.
                    Box(Modifier.weight(1f).wbPos("GIFSEARCHFIELD")) {
                        ActionTextEditor(text = w.gifQuery, autofocus = true, autocorrect = true, noPersonalizedLearning = true, textSize = 15.6.sp, placeholder = stringResource(R.string.wren_search_gifs))
                    }
                } else {
                    Box(Modifier.weight(1f).wbPos("GIFSEARCHFIELD")) {
                        Text(if (w.gifQuery.value.isEmpty()) stringResource(R.string.wren_search_gifs) else w.gifQuery.value,
                            color = EmojiColors.OrangeText, fontSize = 15.6.sp, maxLines = 1)
                    }
                }
            }
            if(w.tab.value == 1) {
            Spacer(Modifier.width(4.6.dp))
            // B2: this button used to send a raw ENTER to the host app - in any messaging
            // field with enter=send that sent the user's drafted message. It now only drives
            // local behavior: emoji tab opens the emoji search UI, GIF tab dismisses the
            // search keyboard (results load on a debounce while typing).
            Box(Modifier.size(34.8.dp).clip(CircleShape).background(EmojiColors.Arrow).wbPos("EMOJISEARCHGO").clickable {
                if (w.tab.value == 0) w.searching.value = true
                else { w.gifSearching.value = false; w.manager.forceActionWindowAboveKeyboard(false) }
            }, contentAlignment = Alignment.Center) { Ic(R.drawable.wren_ic_arrow_forward, EmojiColors.ArrowGlyph, 19.dp, desc = stringResource(if (w.tab.value == 0) R.string.wren_search_emojis else R.string.wren_cd_back)) }
        }

        }

        // Typing reference: tab/search rows directly above suggestions + full keyboard.
        // Results/status/categories only belong to the post-search panel, not this state.
        if (w.tab.value == 1 && w.gifSearching.value) return@Column

        if (w.tab.value == 1) {
            WrenGifPlaceholder(w, view, Modifier.weight(1f))
            return@Column
        }

        val pagerState = rememberPagerState(initialPage = 1) { emojiPages.size }
        val scope = rememberCoroutineScope()
        LaunchedEffect(pagerState.currentPage) { WbDebug.logWindow("EMOJI page=${emojiPages[pagerState.currentPage].key}") }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth().wbPos("EMOJIGRID").background(
            Brush.verticalGradient(listOf(EmojiColors.GridTop, EmojiColors.GridBottom))
        )) { page ->
            when (page) {
                QUICK_PAGE -> WrenQuickPage(w, emojiMap, view)
                1 -> WrenRecentsPage(w, emojis, emojiMap, view)
                else -> WrenCategoryPage(w, emojiPages[page], emojis, emojiMap, view)
            }
        }

        // Bottom row 124 px (44 dp): abc, search (= Quick emoji), recents + categories (scroll), backspace.
        Row(Modifier.fillMaxWidth().height(37.67.dp).background(EmojiColors.Bar), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(47.dp).fillMaxHeight().clickable {
                w.manager.closeActionWindow()
                w.manager.performHapticAndAudioFeedback(Constants.CODE_SWITCH_ALPHA_SYMBOL, view)
            }, contentAlignment = Alignment.Center) { Text("ABC", color = EmojiColors.Orange, fontSize = 15.6.sp) }
            LazyRow(Modifier.weight(1f).fillMaxHeight()) {
                items(emojiPages.size - 1) { k ->
                    val i = k + 1
                    CategoryIcon(emojiPages[i].icon, pagerState.currentPage == i, emojiPages[i].key, Modifier.wbPos("EMOJITAB i=$i key=${emojiPages[i].key}")) {
                        scope.launch { pagerState.scrollToPage(i) }
                    }
                }
            }
            Box(Modifier.width(37.67.dp).fillMaxHeight().semantics { contentDescription = bsDesc }.repeatablyClickableAction { repeated ->
                w.manager.backspace(1)
                if (!repeated) w.manager.performHapticAndAudioFeedback(Constants.CODE_DELETE, view)
            }, contentAlignment = Alignment.Center) { PanelBackspaceGlyph() }
        }
    }
}

/** Bottom-bar category slot, 102 px apart: outline icon, brighter with a 72 x 5 px orange underline when selected. */
@Composable
private fun CategoryIcon(icon: Int, active: Boolean, desc: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.width(34.dp).fillMaxHeight().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Ic(icon, if (active) EmojiColors.Orange else EmojiColors.OrangeMuted, 21.dp, desc = desc)
    }
}

/** Quick emoji (mock-up 09): the 24 most recently used emojis, topped up with popular defaults, 3 rows of 8. */
@Composable
private fun WrenQuickPage(w: WrenEmojiWindow, emojiMap: Map<String, EmojiItem>, view: View) {
    val context = LocalContext.current
    val (locked, setLocked) = rememberLocked()
    val lockedSet = locked.toSet()
    val recents = remember { runBlocking { context.getRecentEmojis() } }
    val quick = remember(recents) { (recents + QuickDefaults).distinct().take(QUICK_COUNT) }
    fun itemFor(e: String) = emojiMap[e] ?: EmojiItem(e, description = "", category = "", skinTones = false)
    LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(quick.size, key = { "Q" + quick[it] }) { i ->
            EmojiCell(w, itemFor(quick[i]), view, locked = false, lockedSet = lockedSet, emojiMap = emojiMap, setLocked = setLocked)
        }
    }
}

/** One emoji cell: 135 x 131 px (48 x 46.6 dp), glyph 73 px (26 dp); 12 px grey corner triangle when it has skin tones (mock-up 09). */
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
        Modifier.fillMaxWidth().height(44.dp)
            .semantics { if (item.description.isNotBlank()) contentDescription = item.description }
            .then(if (popup.value) Modifier.padding(horizontal = 4.dp, vertical = 2.dp).clip(RoundedCornerShape(7.6.dp)).background(Color(0x29FFFFFF)) else Modifier)
            // Passive gesture observer (DEBUG only, wb12 e02 investigation): logs what the cell
            // actually received - DOWN/UP eventTimes and the app-visible hold duration - without
            // consuming anything, so a long-press that the pipeline delivered as a tap is visible
            // as a short delivered hold rather than guessed from the outcome.
            .then(if (org.futo.inputmethod.latin.BuildConfig.DEBUG) Modifier.pointerInput(item.emoji) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    val t0 = android.os.SystemClock.uptimeMillis()
                    // immediate marker: lets the harness anchor the hold to the APP-VISIBLE down
                    android.util.Log.i("WBDBG", "EMOJITOUCH DOWN rx=$t0 ev=${d.uptimeMillis}")
                    var upEv = -1L
                    while (true) {
                        val ch = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == d.id } ?: break
                        if (ch.changedToUp()) { upEv = ch.uptimeMillis; break }
                        if (!ch.pressed) break
                    }
                    android.util.Log.i("WBDBG", "EMOJITOUCH rx0=$t0 evDown=${d.uptimeMillis} evUp=$upEv heldMs=${if (upEv > 0) upEv - d.uptimeMillis else -1}")
                }
            } else Modifier)
            .combinedClickable(
                onClick = { if (org.futo.inputmethod.latin.BuildConfig.DEBUG) android.util.Log.i("WBDBG", "EMOJITOUCH outcome=TAP ${item.emoji}"); w.type(item, view) },
                onLongClick = { if (org.futo.inputmethod.latin.BuildConfig.DEBUG) android.util.Log.i("WBDBG", "EMOJITOUCH outcome=LONG ${item.emoji}"); popup.value = true }
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        EmojiGlyph(item.emoji, if (item.category == "ASCII") 16f else 27.33f, Modifier.padding(top = if (item.category == "ASCII") 15.dp else 8.3.dp))
        val hasTones = emojiMap[base]?.skinTones == true || item.skinTones
        if (hasTones) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(top = 36.3.dp, end = 7.dp).size(4.3.dp)) {
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
                Ic(if (isLocked) R.drawable.wren_ic_lock_open else R.drawable.wren_ic_lock_fill, EmojiColors.Orange, 20.dp, desc = stringResource(if (isLocked) R.string.wren_cd_unlock_emoji else R.string.wren_cd_lock_emoji))
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
                Ic(R.drawable.wren_ic_arrow_back_ios_new, EmojiColors.Orange, 18.dp, desc = stringResource(R.string.wren_cd_back))
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
                if (w.searchText.value.isNotEmpty()) {
                    Box(Modifier.size(30.dp).clip(CircleShape).clickable { w.searchText.value = "" }, contentAlignment = Alignment.Center) {
                        Ic(R.drawable.wren_ic_close, EmojiColors.OrangeMuted, 20.dp, desc = stringResource(R.string.wren_cd_clear))
                    }
                }
            }
        }
        LazyRow(Modifier.fillMaxWidth().weight(1f).background(EmojiColors.GridTop), verticalAlignment = Alignment.CenterVertically) {
            items(results, key = { it.emoji }) { item ->
                Box(Modifier.width(51.4.dp).fillMaxHeight().clickable { w.type(item, view) }, contentAlignment = Alignment.Center) {
                    EmojiGlyph(item.emoji, 24.5f)
                }
            }
        }
    }
}

/** GIF tab: KLIPY-backed grid. Search field lives in the shared top row (B2). */
@OptIn(FlowPreview::class)
@Composable
private fun WrenGifPlaceholder(w: WrenEmojiWindow, view: View, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appKey = org.futo.inputmethod.latin.BuildConfig.KLIPY_APP_KEY
    val hasKey = appKey.isNotBlank()
    val customerId = remember { if (hasKey) klipyCustomerId(context) else "" }
    val query = w.gifQuery
    val mode = w.gifMode
    val gifs = remember { mutableStateOf<List<KlipyGif>>(emptyList()) }
    val loading = remember { mutableStateOf(false) }
    val failed = remember { mutableStateOf(false) }
    // 1.7 (owner choice, Nov 8): ONE Recents landing page with the search box on it - no
    // separate Search page. Typing overlays search results on the same grid; clearing the
    // query returns to Recents, re-arming the one-shot Trending fallback so an empty
    // Recents never shows a blank panel.
    LaunchedEffect(query.value) {
        if (query.value.isNotEmpty()) {
            mode.value = "search"
        } else if (mode.value == "search") {
            mode.value = "recents"
            w.gifRecentsFallback.value = true
        }
    }

    val canInsert = remember { KlipyInsert.canOffer(w.manager) }
    val bsDesc = stringResource(R.string.wren_cd_backspace)

    LaunchedEffect(hasKey, canInsert) {
        if (!hasKey || !canInsert) return@LaunchedEffect
        snapshotFlow { Pair(mode.value, query.value) }.debounce(300).collect { (m, q) ->
            loading.value = true; failed.value = false
            try {
                gifs.value = when {
                    m == "recents" -> KlipyClient.recent(appKey, customerId)
                    q.isBlank() -> KlipyClient.trending(appKey, customerId)
                    else -> KlipyClient.search(appKey, customerId, q)
                }
                // Opens on Recents (mock-up 10); with none yet, show Trending instead of an empty panel (once).
                if (m == "recents" && gifs.value.isEmpty() && w.gifRecentsFallback.value) {
                    w.gifRecentsFallback.value = false
                    mode.value = "trending"
                }
            } catch (_: Exception) {
                failed.value = true; gifs.value = emptyList()
            }
            loading.value = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!hasKey) {
            // No KLIPY key in this build: the message takes the grid's place; the bottom bar (abc) still shows.
            Box(Modifier.weight(1f).fillMaxWidth().background(Brush.verticalGradient(listOf(EmojiColors.GifTop, EmojiColors.GifBottom)))) {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(EmojiColors.Field).padding(18.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("GIFs powered by KLIPY", color = EmojiColors.Orange, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("GIF search is not available in this build.", color = EmojiColors.OrangeMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            // SwiftKey reference layout (owner screenshots, Nov 3): two-column varied-height grid
            // filling the panel; no letters keyboard below; bottom bar = abc / categories / backspace.
            // 1.7 (reconciled Tab prose over the wrong 17-page mock): 2 columns, 4-5 dp gaps,
            // 8 dp rounded corners, animated sharp previews (KlipyClient.gridPreview picks the
            // tile-size-appropriate animated tier, never a tiny jpg when animation exists).
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f).fillMaxWidth().wbPos("GIFGRID")
                    .background(Brush.verticalGradient(listOf(EmojiColors.GifTop, EmojiColors.GifBottom))),
                contentPadding = PaddingValues(4.5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                verticalArrangement = Arrangement.spacedBy(4.5.dp)) {
                itemsIndexed(gifs.value, key = { _, g -> g.id }) { idx, gif ->
                    val preview = gif.gridPreview()
                    val ratio = if (preview != null && preview.width > 0 && preview.height > 0)
                        preview.width.toFloat() / preview.height.toFloat() else 1f
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context).data(preview?.url)
                            .apply { if (Build.VERSION.SDK_INT >= 28) decoderFactory(ImageDecoderDecoder.Factory()) }
                            .build(),
                        contentDescription = gif.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EmojiColors.Field).wbPos("GIFCELL i=$idx").clickable {
                                KlipyInsert.insert(w.manager, appKey, gif)
                            }) {
                        when (painter.state) {
                            // Per-tile spinner while the preview loads; failure-safe: an error
                            // leaves the (tappable) tile background instead of crashing or
                            // dropping the cell.
                            is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = EmojiColors.Orange, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            }
                            is AsyncImagePainter.State.Error -> Box(Modifier.fillMaxSize())
                            else -> SubcomposeAsyncImageContent()
                        }
                    }
                }
            }
            val status = when {
                !canInsert -> "This app does not accept GIFs here"
                loading.value -> "Loading\u2026"
                failed.value -> "Couldn't load GIFs - check connection"
                gifs.value.isEmpty() && mode.value == "recents" -> "No recent GIFs yet"
                gifs.value.isEmpty() -> "No GIFs found"
                else -> ""
            }
            if (status.isNotEmpty()) Text(status, color = EmojiColors.OrangeMuted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
        // Bottom bar (mock-up 10): abc / Recents (clock disc + label) and category labels / backspace (edits the
        // GIF query only - it can never touch the host field).
        Row(Modifier.fillMaxWidth().height(44.dp).background(EmojiColors.Bar), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(54.8.dp).fillMaxHeight().wbPos("GIFABC").clickable { w.manager.closeActionWindow() }, contentAlignment = Alignment.Center) {
                Text("abc", color = EmojiColors.Orange, fontSize = 15.6.sp)
            }
            // 1.7 (reconciled Tab prose): scrolling category LABELS Recents/Happy/Sad/Laugh/Love
            // with the active underline. Trending is not a category - it is only the fallback
            // feed when Recents is empty. A non-empty label maps to its KLIPY query.
            val cats = listOf("Recents" to "", "Happy" to "happy", "Sad" to "sad", "Laugh" to "laugh", "Love" to "love")
            LazyRow(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(13.9.dp)) {
                items(cats) { (label, q) ->
                    val active = (label == "Recents" && mode.value == "recents") ||
                        (q.isNotEmpty() && mode.value == "search" && query.value.equals(q, ignoreCase = true))
                    Box(Modifier.fillMaxHeight().wbPos("GIFCAT name=$label").clickable {
                        when (label) {
                            // Recents tap re-arms the Trending fallback, same as landing.
                            "Recents" -> { mode.value = "recents"; query.value = ""; w.gifRecentsFallback.value = true }
                            else -> { w.gifRecentsFallback.value = false; mode.value = "search"; query.value = q }
                        }
                    }) {
                        Row(Modifier.align(Alignment.Center).padding(horizontal = 7.5.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (label == "Recents") {
                                RecentsDiscGlyph(active)
                                Spacer(Modifier.width(9.2.dp))
                            }
                            Text(label, color = if (active) EmojiColors.Orange else EmojiColors.OrangeMuted, fontSize = 15.6.sp, maxLines = 1)
                        }
                        if (active) Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height(1.8.dp).background(EmojiColors.Orange))
                        }
                    }
                }
            }
            Box(Modifier.width(40.5.dp).fillMaxHeight().wbPos("GIFBS").semantics { contentDescription = bsDesc }.clickable {
                if (w.gifQuery.value.isNotEmpty()) w.gifQuery.value = w.gifQuery.value.dropLast(1)
            }, contentAlignment = Alignment.Center) {
                PanelBackspaceGlyph()
            }
        }
    }
}

