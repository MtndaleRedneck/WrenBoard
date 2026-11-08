package org.futo.inputmethod.latin.uix.actions

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
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
fun ActionItem(action: Action, modifier: Modifier = Modifier, dragIcon: Boolean = false, dragIconModifier: Modifier = Modifier) {
    Surface(color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp),
        shape = RoundedCornerShape(0.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides LocalKeyboardScheme.current.onKeyboardContainer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .border(0.3.dp, LocalKeyboardScheme.current.outline.copy(alpha = 0.2f))
            ) {

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
                        .padding(6.dp)
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
                        style = Typography.Small.copy(lineHeight = 12.sp, color = LocalContentColor.current)
                    )
                }
            }
        }
    }
}


@Composable
@Preview(showBackground = true)
fun MoreActionsView() {
    val manager = if(LocalInspectionMode.current) { null } else { LocalManager.current }
    val context = LocalContext.current

    val unified = useUnifiedActions()
    val actions = unified.visible.mapNotNull { AllActionsMap[it] }
    var showHidden by remember { mutableStateOf(false) }
    if (showHidden) {
        Column {
            TextButton(onClick = { showHidden = false }) { Text("Back to actions") }
            LazyVerticalGrid(columns = GridCells.Fixed(4)) {
                items(unified.hidden.mapNotNull { AllActionsMap[it] }.filter { it.shownInEditor }, key = { it.name }) { action ->
                    ActionItem(action, Modifier.clickable { manager?.activateAction(action) })
                }
            }
        }
        return
    }
    if(actions.isEmpty()) {
        ScreenTitle(stringResource(R.string.action_editor_warning_no_actions))
    }

    LazyVerticalGrid(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "hidden-destination") {
            TextButton(onClick = { showHidden = true }, modifier = Modifier.height(74.dp)) { Text("Hidden") }
        }
        items(actions, key = { it.name }) {
            // WrenBoard harness: log each grid tile's center in DEBUG builds, same as the toolbar.
            val wbView = LocalView.current
            val wbName = stringResource(it.name)
            ActionItem(it, Modifier.onGloballyPositioned { c ->
                if (org.futo.inputmethod.latin.BuildConfig.DEBUG) {
                    val loc = IntArray(2); wbView.getLocationOnScreen(loc); val p = c.positionInRoot()
                    org.futo.inputmethod.latin.uix.WbDebug.logWindow("ACTION name=${wbName.replace(' ', '_')} cx=${(loc[0] + p.x + c.size.width / 2).toInt()} cy=${(loc[1] + p.y + c.size.height / 2).toInt()}")
                }
            }.clickable {
                manager!!.activateAction(it)
            })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionsEditor(header: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inspecting = LocalInspectionMode.current
    var draft by remember { mutableStateOf(if(inspecting) migrateUnifiedActionIds(ActionsSettings.default) else context.readUnifiedActions()) }
    var hiddenOpen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lazyState = rememberLazyGridState()
    // Sentinel remains fixed and reachable. Moving onto it transfers the action to Hidden.
    val shownIds = if(hiddenOpen) draft.hidden.filter { AllActionsMap[it]?.shownInEditor == true } else draft.visible
    val reorder = rememberReorderableLazyGridState(lazyState) { from, to ->
        val id = from.key as? String
        if (id != null && id != "hidden-destination" && !hiddenOpen) {
            draft = if(to.key == "hidden-destination") draft.hide(id)
                else draft.move(id, shownIds.indexOf(to.key).coerceAtLeast(0))
            saved = false
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    }
    Column(Modifier.fillMaxSize()) {
        header()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { hiddenOpen = !hiddenOpen }) { Text(if(hiddenOpen) "Back to actions" else "Customize actions") }
            TextButton(onClick = { scope.launch { context.saveUnifiedActions(draft); saved = true } }) { Text(if(saved) "Saved" else "Done") }
        }
        Text(if(hiddenOpen) "Tap an action to bring it back." else "Hold and drag to reorder. Drag onto Hidden to hide.",
            Modifier.padding(8.dp), style = Typography.Small)
        LazyVerticalGrid(state = lazyState, columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize().padding(8.dp)) {
            item(key = "hidden-destination") {
                ReorderableItem(reorder, key = "hidden-destination") {
                    TextButton(onClick = { hiddenOpen = !hiddenOpen }, modifier = Modifier.height(74.dp)) {
                        Text(if(hiddenOpen) "Actions" else "Hidden (" + draft.hidden.count { AllActionsMap[it]?.shownInEditor == true } + ")")
                    }
                }
            }
            items(shownIds, key = { it }) { id ->
                val action = AllActionsMap[id]
                if(action != null) {
                    ReorderableItem(reorder, key = id) { _ ->
                        val itemModifier = if(hiddenOpen) Modifier.clickable {
                            draft = draft.show(id, draft.visible.size); saved = false
                        } else Modifier.longPressDraggableHandle()
                        ActionItem(action, itemModifier)
                    }
                }
            }
        }
    }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { manager.showActionEditor() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.wren17_reorder))
                        }
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
