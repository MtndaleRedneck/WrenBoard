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
    Surface(color = LocalKeyboardScheme.current.onSurface.copy(alpha = 0.10f),
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

                if (position != null && position <= 6) Text(position.toString(), color = LocalKeyboardScheme.current.onSurface.copy(alpha = 0.55f), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart))
                if (dragIcon) {
                    Icon(
                        painterResource(id = R.drawable.move),
                        contentDescription = null,
                        modifier = dragIconModifier
                            .size(16.dp)
                            .align(Alignment.TopEnd),
                        tint = LocalContentColor.current.copy(alpha = 0.6f)
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
                        style = Typography.Small.copy(lineHeight = 12.sp, color = LocalKeyboardScheme.current.onSurface)
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
    Column {
        if(hiddenOpen) TextButton(onClick = { hiddenOpen = false }) { Text("Back") }
        LazyVerticalGrid(columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            val ids = (if(hiddenOpen) draft.hidden else draft.visible).filter { AllActionsMap[it]?.shownInEditor == true }
            items(ids, key = { it }) { id ->
                val action = AllActionsMap[id]!!
                var itemOrigin by remember { mutableStateOf(Offset.Zero) }
                val gesture = if(hiddenOpen) Modifier.clickable {
                    persist(draft.show(id, draft.visible.size))
                } else Modifier
                    .clickable { manager?.activateAction(action) }
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                transaction = ActionDragDraft(latestDraft)
                                dragging = id
                                pointer = itemOrigin + offset
                                overHidden = false
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                pointer += delta
                                overHidden = bounds["hidden-destination"]?.contains(pointer) == true
                                if(!overHidden) {
                                    val target = bounds.entries.firstOrNull { it.key != "hidden-destination" && it.key != id && it.value.contains(pointer) }?.key
                                    val index = latestDraft.visible.indexOf(target)
                                    if(index >= 0) {
                                        transaction?.move(id, index)
                                        transaction?.preview()?.let { draft = it }
                                    }
                                }
                            },
                            onDragEnd = {
                                if(overHidden) transaction?.hide(id)
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
                ActionItem(action, Modifier.alpha(if(dragging == id) 0.55f else 1.0f).onGloballyPositioned {
                    bounds[id] = it.boundsInRoot(); itemOrigin = it.boundsInRoot().topLeft
                }.then(gesture), dragIcon = !hiddenOpen, position = if(hiddenOpen) null else ids.indexOf(id) + 1)
            }
            // Keep the hide destination at the bottom right, after all action tiles.
            if(!hiddenOpen) items((3 - ids.size % 4 + 4) % 4) { Spacer(Modifier.height(64.dp)) }
            item(key = "hidden-destination") {
                val scheme = LocalKeyboardScheme.current
                val label = if(hiddenOpen) "Actions" else if(overHidden) "Drop to hide" else "Hidden (" + draft.hidden.count { AllActionsMap[it]?.shownInEditor == true } + ")"
                Surface(color = if(overHidden) scheme.onSurface.copy(alpha = 0.16f) else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(64.dp).fillMaxWidth()
                        .onGloballyPositioned { bounds["hidden-destination"] = it.boundsInRoot() }
                        .drawBehind { drawRoundRect(scheme.onSurface.copy(alpha = 0.4f), cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))) }
                        .clickable { hiddenOpen = !hiddenOpen }) {
                    Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                        Icon(painterResource(R.drawable.visibility_off), contentDescription = null, tint = scheme.onSurface.copy(alpha = 0.8f), modifier = Modifier.size(26.dp))
                        Text(label, color = scheme.onSurface, fontSize = 12.sp, maxLines = 1)
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
                    Box(Modifier.fillMaxWidth().height(org.futo.inputmethod.latin.uix.ToolbarRowHeight)) {
                        org.futo.inputmethod.latin.uix.ActionItems({ manager.activateAction(it) }, { })
                    }
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
