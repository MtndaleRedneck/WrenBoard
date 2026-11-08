package org.futo.inputmethod.latin.uix.actions

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
fun MoreActionsView() {
    val manager = if(LocalInspectionMode.current) null else LocalManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persisted = useUnifiedActions()
    var draft by remember { mutableStateOf(persisted) }
    var hiddenOpen by remember { mutableStateOf(false) }
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
    val boxIds = if(hiddenOpen) draft.hidden.filter { AllActionsMap[it]?.shownInEditor == true } else allVisible.drop(6)
    val gridState = rememberLazyGridState()
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
            if(rect != null && !overHidden) {
                val amount = when {
                    pointer.y > rect.bottom - 32 -> 18f
                    pointer.y < rect.top + 32 -> -18f
                    else -> 0f
                }
                if(amount != 0f) gridState.scroll { scrollBy(amount) }
            }
            kotlinx.coroutines.delay(35)
        }
    }
    val tileModifier: @Composable (String) -> Modifier = { id ->
        DisposableEffect(id, hiddenOpen) { onDispose { bounds.remove(id) } }
        Modifier.alpha(if(dragging == id) 0.55f else 1f)
            .onGloballyPositioned { bounds[id] = it.boundsInRoot() }
    }
    Column(Modifier.onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }.then(dragModifier)) {
        Row(Modifier.fillMaxWidth().height(org.futo.inputmethod.latin.uix.ToolbarRowHeight), verticalAlignment = Alignment.CenterVertically) {
            toolbarIds.forEach { id ->
                androidx.compose.runtime.key(id) {
                    val action = AllActionsMap[id]!!
                    Box(tileModifier(id).weight(1f).fillMaxHeight().clickable { manager?.activateAction(action) }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(action.icon), contentDescription = stringResource(action.name), tint = LocalKeyboardScheme.current.onKeyboardContainer)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clickable { manager?.activateAction(MoreActionsAction) }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.more_horizontal), contentDescription = "More", tint = LocalKeyboardScheme.current.onKeyboardContainer)
            }
        }
        if(hiddenOpen) TextButton(onClick = { hiddenOpen = false }) { Text("Back") }
        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 304.dp).padding(8.dp).onGloballyPositioned { gridBounds = it.boundsInRoot() }) {
            items(boxIds, key = { it }) { id ->
                val action = AllActionsMap[id]!!
                ActionItem(action, tileModifier(id).clickable {
                    if(hiddenOpen) persist(draft.show(id, draft.visible.size)) else manager?.activateAction(action)
                }, dragIcon = !hiddenOpen)
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionsEditor(header: @Composable () -> Unit) {
    MoreActionsView()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionEditor() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f),
        color = LocalKeyboardScheme.current.keyboardSurface,
        contentColor = LocalKeyboardScheme.current.onSurface,
        shape = RoundedCornerShape(32.dp, 32.dp, 0.dp, 0.dp)
    ) {
        ActionsEditor {
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
            override val showTitleBar = false
            @Composable
            override fun windowName(): String = stringResource(id = R.string.action_more_actions_title)

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                Column {
                    MoreActionsView()
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
