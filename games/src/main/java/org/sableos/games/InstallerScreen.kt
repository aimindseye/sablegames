package org.sableos.games

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Gold = Color(0xFFE8C872)
private val Ink = Color(0xFF0E0C12)
private val Panel = Color(0xFF1C1822)
private val Muted = Color(0xFFA9A2B3)

/** The Sable Installer: pick the games you want, and it downloads, checks and installs them. */
@Composable
internal fun InstallerScreen() {
    val context = LocalContext.current
    val model = remember { InstallerModel(context.applicationContext) }
    // The list is each group's header followed by its games; the cursor moves over all of them.
    val entries = remember(model) { InstallerCatalog.groups.flatMap { g -> listOf<Any>(g) + model.rowsOf(g) } }
    var cursor by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        model.refresh()
        focus.requestFocus()
    }
    LaunchedEffect(cursor) { list.animateScrollToItem(cursor) }

    fun toggle(i: Int) {
        when (val e = entries[i]) {
            is InstallerGroup -> model.toggleGroup(e)
            is InstallerRow -> if (!e.busy) e.selected = !e.selected
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .safeDrawingPadding()
            .padding(12.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_W -> cursor = (cursor - 1).coerceAtLeast(0)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_S -> cursor = (cursor + 1).coerceAtMost(entries.lastIndex)
                    AndroidKeyEvent.KEYCODE_SPACE, AndroidKeyEvent.KEYCODE_DPAD_CENTER -> toggle(cursor)
                    AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_I -> model.installSelected()
                    AndroidKeyEvent.KEYCODE_R -> model.refresh()
                    AndroidKeyEvent.KEYCODE_N -> model.pickNotInstalled()
                    AndroidKeyEvent.KEYCODE_U -> model.pickUpdates()
                    AndroidKeyEvent.KEYCODE_C -> model.clearPicks()
                    AndroidKeyEvent.KEYCODE_F -> model.toggleWifiOnly()
                    AndroidKeyEvent.KEYCODE_O -> (entries[cursor] as? InstallerRow)?.takeIf { it.installed != null }?.let { model.openApp(it) }
                    AndroidKeyEvent.KEYCODE_X -> (entries[cursor] as? InstallerRow)?.takeIf { it.installed != null }?.let { model.removeApp(it) }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SABLE INSTALLER", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(model.headline, color = Muted, fontSize = 13.sp)
        if (model.notes.isNotEmpty()) Text(model.notes, color = Muted, fontSize = 11.sp, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Not installed (N)") { model.pickNotInstalled() }
            Chip("Updates (U)") { model.pickUpdates() }
            Chip("Clear (C)") { model.clearPicks() }
            Chip(if (model.wifiOnly) "Wi-Fi only: on (F)" else "Wi-Fi only: off (F)") { model.toggleWifiOnly() }
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entries) { i, e ->
                when (e) {
                    is InstallerGroup -> InstallerHeaderView(e, model.rowsOf(e), focused = i == cursor, onTap = { cursor = i; toggle(i) })
                    is InstallerRow -> InstallerRowView(e, model.latest, focused = i == cursor, onTap = { cursor = i; toggle(i) }, onOpen = { model.openApp(e) }, onRemove = { model.removeApp(e) })
                }
            }
        }
        Text("W S move · Space picks · Enter installs · R refreshes · O opens · X removes", color = Muted, fontSize = 11.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (model.canInstall) Gold else Panel, RoundedCornerShape(10.dp))
                .clickable(enabled = model.canInstall) { model.installSelected() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val count = model.rows.count { it.selected }
            Text(
                if (model.working) "Working…" else "Install selected ($count)",
                color = if (model.canInstall) Ink else Muted,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun InstallerHeaderView(
    group: InstallerGroup,
    rows: List<InstallerRow>,
    focused: Boolean,
    onTap: () -> Unit,
) {
    val picked = rows.count { it.selected }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .border(if (focused) 2.dp else 0.dp, if (focused) Gold else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, Gold, RoundedCornerShape(4.dp))
                .background(if (picked == rows.size) Gold else if (picked > 0) Gold.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(group.name.uppercase(), color = Gold, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("$picked of ${rows.size}", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun InstallerRowView(
    row: InstallerRow,
    latest: String?,
    focused: Boolean,
    onTap: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Gold else Color(0xFF3A3442), RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, Gold, RoundedCornerShape(5.dp))
                .background(if (row.selected) Gold else Color.Transparent, RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(row.app.blurb, color = Muted, fontSize = 12.sp)
            if (row.note.isNotEmpty()) Text(row.note, color = Gold, fontSize = 12.sp)
        }
        val have = row.installed
        val newer = have != null && latest != null && ReleaseFeed.isNewer(have, latest)
        Text(
            when {
                have == null -> "not installed"
                newer -> "v$have → $latest"
                else -> "v$have"
            },
            color = if (have != null) Gold else Muted,
            fontSize = 12.sp,
        )
        if (have != null && row.app.id != InstallerModel.SELF) {
            Spacer(Modifier.width(8.dp))
            Chip("Open", onOpen)
            Spacer(Modifier.width(4.dp))
            Chip("Remove", onRemove)
        }
    }
}

@Composable
private fun Chip(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = Gold,
        fontSize = 11.sp,
        modifier =
            Modifier
                .border(1.dp, Gold, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
