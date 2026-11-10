package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.LocalManager
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.WarningTip
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.Typography
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState


@Composable
fun ActionItem(action: Action, modifier: Modifier = Modifier, dragIcon: Boolean = false, dragIconModifier: Modifier = Modifier, position: Int? = null) {
    val darkTiles = LocalKeyboardScheme.current.keyboardSurface.luminance() < 0.5f
    val tileLabel = if(darkTiles) Color(0xFFE6E6E6) else Color(0xFF262626)
    val tileFill = LocalKeyboardScheme.current.keyboardContainer
    Surface(color = tileFill,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides LocalKeyboardScheme.current.onKeyboardContainer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {

                if (position != null && position <= 6) Text(position.toString(), color = tileLabel.copy(alpha = 0.55f), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart))
                if (dragIcon) {
                    Icon(
                        painterResource(id = R.drawable.move),
                        contentDescription = null,
                        modifier = dragIconModifier
                            .size(16.dp)
                            .align(Alignment.TopEnd),
                        tint = tileLabel.copy(alpha = 0.5f)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Center)
                        .padding(2.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1.0f))
                    Icon(
                        painterResource(id = action.icon),
                        contentDescription = null,
                        modifier = Modifier.align(
                            CenterHorizontally
                        )
                    )

                    Spacer(modifier = Modifier.weight(1.0f))

                    org.futo.inputmethod.latin.uix.AutoFitText(
                        if (action == org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistoryAction) stringResource(R.string.wren_home_clipboard) else stringResource(id = action.name),
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        style = Typography.Small.copy(lineHeight = 12.sp, color = tileLabel)
                    )
                }
            }
        }
    }
}


@Composable
@Preview(showBackground = true)
fun MoreActionsView(setNestedBack: ((() -> Boolean)?) -> Unit = {}) {
    val settingsNav = org.futo.inputmethod.latin.uix.LocalNavController.current
    val manager = if(LocalInspectionMode.current || settingsNav != null) null else LocalManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootView = LocalView.current
    val persisted = useUnifiedActions()
    var draft by remember { mutableStateOf(persisted) }
    var hiddenOpen by remember { mutableStateOf(false) }
    val hiddenState by rememberUpdatedState(hiddenOpen)
    DisposableEffect(Unit) {
        setNestedBack { if(hiddenState) { hiddenOpen = false; true } else false }
        onDispose { setNestedBack(null) }
    }
    // The IME has no Activity back dispatcher. Its ActionWindow/host handles nested back.
    if(settingsNav != null) BackHandler(enabled = hiddenOpen) { hiddenOpen = false }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var grabbedWidth by remember { mutableStateOf(80.dp) }
    var grabbedHeight by remember { mutableStateOf(64.dp) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var transaction by remember { mutableStateOf<ActionDragDraft?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var overHidden by remember { mutableStateOf(false) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val latestDraft by rememberUpdatedState(draft)
    LaunchedEffect(persisted.encode()) { if(dragging == null) draft = persisted }
    fun persist(model: OrderedActionIds) {
        draft = model
        scope.launch { context.saveUnifiedActions(model) }
    }
    val allVisible = draft.visible.filter { AllActionsMap[it]?.shownInEditor == true }
    val toolbarIds = allVisible.take(6)
    val boxIds = if(hiddenOpen) draft.hidden.filter { AllActionsMap[it]?.shownInEditor == true } else allVisible
    val gridState = rememberLazyGridState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edgeZone = with(density) { 48.dp.toPx() }
    val scrollStep = with(density) { 6.dp.toPx() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var gridBounds by remember { mutableStateOf<Rect?>(null) }
    val latestHidden by rememberUpdatedState(hiddenOpen)
    val latestRoot by rememberUpdatedState(rootOrigin)
    val currentVisible by rememberUpdatedState(allVisible)
    // The detector lives on a stable parent so a tile may cross toolbar/grid without cancelling.
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                pointer = latestRoot + offset
                val id = if(latestHidden) null else bounds.entries.firstOrNull {
                    it.key in currentVisible && it.value.contains(pointer)
                }?.key
                if(id != null) {
                    transaction = ActionDragDraft(latestDraft)
                    dragging = id
                    bounds[id]?.let { rect ->
                        grabOffset = pointer - rect.topLeft
                        grabbedWidth = with(density) { rect.width.toDp() }
                        grabbedHeight = with(density) { rect.height.toDp() }
                    }
                    rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    overHidden = false
                }
            },
            onDrag = { change, _ ->
                if(dragging != null) {
                    change.consume()
                    pointer = latestRoot + change.position
                    overHidden = bounds["hidden-destination"]?.contains(pointer) == true
                    if(!overHidden) {
                        val target = bounds.entries.firstOrNull {
                            it.key in currentVisible && it.key != dragging && it.value.contains(pointer)
                        }?.key
                        val index = latestDraft.visible.indexOf(target)
                        if(index >= 0) {
                            transaction?.move(dragging!!, index)
                            transaction?.preview()?.let { draft = it }
                        }
                    }
                }
            },
            onDragEnd = {
                dragging?.let { if(overHidden) transaction?.hide(it) }
                val completed = transaction?.drop()
                if(dragging != null) rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dragging = null; transaction = null; overHidden = false
                if(completed != null) persist(completed)
            },
            onDragCancel = {
                transaction?.cancel()
                transaction?.preview()?.let { draft = it }
                dragging = null; transaction = null; overHidden = false
            }
        )
    }
    // Hidden stays the last item. Edge scrolling brings it into reach during long drags.
    LaunchedEffect(dragging, pointer) {
        while(dragging != null) {
            val rect = gridBounds
            if(rect != null && rect.contains(pointer) && !overHidden) {
                val amount = when {
                    pointer.y > rect.bottom - edgeZone -> scrollStep
                    pointer.y < rect.top + edgeZone -> -scrollStep
                    else -> 0f
                }
                if(amount != 0f) gridState.scroll { scrollBy(amount) }
            }
            kotlinx.coroutines.delay(35)
        }
    }
    val tileModifier: @Composable (String, Boolean) -> Modifier = { id, inBar ->
        DisposableEffect(id, hiddenOpen) { onDispose { bounds.remove(id) } }
        Modifier.alpha(if(dragging == id) 0f else 1f)
            .onGloballyPositioned {
                val rect = it.boundsInRoot()
                bounds[id] = if(inBar) rect else gridBounds?.intersect(rect) ?: Rect.Zero
                if(inBar && org.futo.inputmethod.latin.BuildConfig.DEBUG) {
                    val action = AllActionsMap[id]!!
                    val name = if(action == org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistoryAction) context.getString(R.string.wren_home_clipboard) else context.getString(action.name)
                    val screen = IntArray(2); rootView.getLocationOnScreen(screen)
                    android.util.Log.i("WBDBG", "UNIFIED_BAR name=" + name.replace(' ', '_') + " cx=" + (screen[0] + rect.center.x).toInt() + " cy=" + (screen[1] + rect.center.y).toInt())
                }
            }
    }
    Box(Modifier.onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }.then(dragModifier)) {
    Column {
        Row(Modifier.fillMaxWidth().height(org.futo.inputmethod.latin.uix.ToolbarRowHeight), verticalAlignment = Alignment.CenterVertically) {
            toolbarIds.forEach { id ->
                androidx.compose.runtime.key(id) {
                    val action = AllActionsMap[id]!!
                    Box(tileModifier(id, true).weight(1f).fillMaxHeight().clickable { manager?.activateAction(action) }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(action.icon), contentDescription = if(action == org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistoryAction) stringResource(R.string.wren_home_clipboard) else stringResource(action.name), tint = LocalKeyboardScheme.current.onKeyboardContainer)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clickable {
                if(hiddenOpen) hiddenOpen = false
                else if(settingsNav != null) settingsNav.popBackStack()
                else manager?.activateAction(MoreActionsAction)
            }, contentAlignment = Alignment.Center) {
                org.futo.inputmethod.latin.uix.WrenMoreIcon(active = true, modifier = Modifier.size(24.dp))
            }
        }
        if(hiddenOpen) Text(stringResource(R.string.wren18_hidden), modifier = Modifier.padding(8.dp))
        LazyVerticalGrid(state = gridState, userScrollEnabled = dragging == null, columns = GridCells.FixedSize(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 304.dp).padding(8.dp).onGloballyPositioned { gridBounds = it.boundsInRoot() }) {
            items(boxIds, key = { it }) { id ->
                val action = AllActionsMap[id]!!
                Box {
                    ActionItem(action, tileModifier(id, false).animateItem().clickable {
                        if(hiddenOpen) persist(draft.show(id, draft.visible.size)) else manager?.activateAction(action)
                    }, dragIcon = false)
                    if(hiddenOpen) androidx.compose.material3.IconButton(
                        onClick = { persist(draft.show(id, draft.visible.size)) },
                        modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                    ) { Text("+", color = LocalKeyboardScheme.current.onKeyboardContainer, fontSize = 22.sp) }
                }
            }
            if(!hiddenOpen) item(key = "hidden-destination") {
                DisposableEffect(Unit) { onDispose { bounds.remove("hidden-destination") } }
                val scheme = LocalKeyboardScheme.current
                val labelColor = if(scheme.keyboardSurface.luminance() < 0.5f) Color(0xFFE0E0E0) else Color(0xFF252525)
                Surface(color = if(overHidden) scheme.keyboardContainer else Color.Transparent, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(64.dp).fillMaxWidth()
                        .onGloballyPositioned { bounds["hidden-destination"] = it.boundsInRoot() }
                        .drawBehind { drawRoundRect(labelColor.copy(alpha = 0.4f), cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))) }
                        .clickable { hiddenOpen = true }) {
                    Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                        Icon(painterResource(R.drawable.visibility_off), contentDescription = null, tint = labelColor, modifier = Modifier.size(26.dp))
                        Text(if(overHidden) "Drop to hide" else "Hidden (" + draft.hidden.count { AllActionsMap[it]?.shownInEditor == true } + ")", color = labelColor, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
    dragging?.let { id ->
        AllActionsMap[id]?.let { action ->
            Box(Modifier.offset { IntOffset((pointer.x - rootOrigin.x - grabOffset.x).roundToInt(), (pointer.y - rootOrigin.y - grabOffset.y).roundToInt()) }
                .width(grabbedWidth).height(grabbedHeight).zIndex(10f)) {
                ActionItem(action, dragIcon = false)
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionsEditor(setNestedBack: ((() -> Boolean)?) -> Unit = {}, header: @Composable () -> Unit) {
    MoreActionsView(setNestedBack)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionEditor(setNestedBack: ((() -> Boolean)?) -> Unit = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f),
        color = LocalKeyboardScheme.current.keyboardSurface,
        contentColor = LocalKeyboardScheme.current.onSurface,
        shape = RoundedCornerShape(32.dp, 32.dp, 0.dp, 0.dp)
    ) {
        ActionsEditor(setNestedBack) {
            ScreenTitle(title = stringResource(R.string.action_editor_edit_actions))
        }
    }
}


val MoreActionsAction = Action(
    icon = R.drawable.more_horizontal,
    name = R.string.action_more_actions_title,
    simplePressImpl = null,
    shownInEditor = false,
    windowImpl = { manager, _ ->
        object : ActionWindow() {
            private var nestedBack: (() -> Boolean)? = null
            override fun navigateBack(): Boolean = nestedBack?.invoke() ?: false
            override val showTitleBar = false
            @Composable
            override fun windowName(): String = stringResource(id = R.string.action_more_actions_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                Column {
                    MoreActionsView { nestedBack = it }
                }
            }

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                super.WindowTitleBar(rowScope)

                OutlinedButton(onClick = { manager.showActionEditor() }, modifier = Modifier.padding(8.dp, 0.dp)) {
                    Text(stringResource(R.string.action_editor_edit_actions), color = LocalKeyboardScheme.current.onSurface)
                }
            }
        }
    },
)
