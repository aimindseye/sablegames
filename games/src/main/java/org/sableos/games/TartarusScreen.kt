package org.sableos.games

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TB_PREFS = "sable_tartarus"
private const val TB_NANOS = 1_000_000_000f

/** The player's progress, kept between runs. */
private fun loadMeta(c: Context) = TbMeta.decode(c.getSharedPreferences(TB_PREFS, Context.MODE_PRIVATE).getString("meta", null))

private fun saveMeta(
    c: Context,
    m: TbMeta,
) = c.getSharedPreferences(TB_PREFS, Context.MODE_PRIVATE).edit().putString("meta", m.encode()).apply()

private enum class TbScreenState { Select, Run }

/** The keys held down: a held key repeats well inside [HOLD_MS], and a lost key-up lets go after it. */
private class TbHeld {
    enum class Key { Fwd, Back, Left, Right, TurnL, TurnR, Fire }

    private val until = LongArray(Key.entries.size)

    fun press(
        k: Key,
        now: Long,
        ttl: Long = HOLD_MS,
    ) {
        until[k.ordinal] = now + ttl
    }

    fun release(k: Key) {
        until[k.ordinal] = 0L
    }

    fun clear() = until.fill(0L)

    fun on(
        k: Key,
        now: Long,
    ) = until[k.ordinal] > now

    fun axis(
        plus: Key,
        minus: Key,
        now: Long,
    ) = (if (on(plus, now)) 1f else 0f) - (if (on(minus, now)) 1f else 0f)

    companion object {
        const val HOLD_MS = 900L
    }
}

private val GATE_SHORT = listOf("ASPHODEL", "PHLEGETHON", "LABYRINTH", "ABYSSAL SEAL")

private fun clock(t: Float): String = "%02d:%02d".format((t / 60f).toInt(), (t % 60f).toInt())

@Composable
internal fun TartarusScreen() {
    val context = LocalContext.current
    val shell = LocalGameShell.current
    val host = LocalGameKeyHost.current
    val profile = LocalDeviceProfile.current
    val sprites = rememberTbSprites()
    val controls = GameCatalog.tartarus.controls.first()
    val keys = LocalKeyBindings.current(controls)
    val bound = BoundKeys(controls, keys)
    var meta by remember { mutableStateOf(loadMeta(context)) }
    var screen by remember { mutableStateOf(TbScreenState.Select) }
    var gateIdx by remember { mutableIntStateOf(0) }
    var run by remember { mutableStateOf<TbRun?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var hud by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }
    var shrine by remember { mutableStateOf(false) }
    var shrineSel by remember { mutableIntStateOf(0) }
    var recorded by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val held = remember { TbHeld() }
    val feedback = remember { TbFeedback(context) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    feedback.sound = meta.sound
    feedback.vibrate = meta.vibrate
    val touchHeld = remember { TbHeld() }
    val suspended = shell?.suspended == true

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { sprites.preload() }
        ready = true
    }

    fun startRun() {
        val g = TartarusData.gates[gateIdx]
        if (g.number > meta.unlocked) return
        held.clear()
        run = TbRun(g, meta, seed = System.nanoTime())
        selected = 0
        shrine = false
        recorded = false
        screen = TbScreenState.Run
        hud++
    }

    fun record(r: TbRun) {
        if (recorded || (r.phase != TbPhase.Fallen && r.phase != TbPhase.Sealed)) return
        recorded = true
        val i = r.gate.number - 1
        var m = meta.copy(obols = meta.obols + r.obols)
        m = m.copy(bestWave = m.bestWave.toMutableList().also { it[i] = maxOf(it[i], if (r.sealed) r.gate.stages.size else r.wave) })
        if (r.sealed) {
            val t = r.runTime.toInt().coerceAtLeast(1)
            m = m.copy(
                bestClear = m.bestClear.toMutableList().also { it[i] = if (it[i] == 0) t else minOf(it[i], t) },
                unlocked = maxOf(m.unlocked, minOf(4, r.gate.number + 1)),
            )
        }
        meta = m
        saveMeta(context, m)
    }

    fun cycleSetting(n: Int) {
        meta =
            when (n) {
                1 -> meta.copy(difficulty = (meta.difficulty + 1) % 3)
                2 -> meta.copy(aim = (meta.aim + 1) % 3)
                3 -> meta.copy(sound = !meta.sound)
                else -> meta.copy(vibrate = !meta.vibrate)
            }
        saveMeta(context, meta)
        hud++
    }

    fun toSelect() {
        screen = TbScreenState.Select
        run = null
        shrine = false
        held.clear()
        hud++
    }

    fun buy(u: TbUpgrade) {
        if (!meta.canBuy(u)) return
        meta = meta.buy(u)
        saveMeta(context, meta)
        hud++
    }

    fun nowMs() = System.nanoTime() / 1_000_000L

    fun command(press: GameKeyPress): GameCommand? = GameKeymap.command(GameKind.Tartarus, press.copy(repeatCount = 0), profile, bound)

    fun combatKey(
        r: TbRun,
        cmd: GameCommand,
        press: GameKeyPress,
    ): Boolean {
        val now = nowMs()
        val first = press.repeatCount == 0
        val arrow = press.keyCode in InputFeel.ARROWS
        when (cmd) {
            is GameCommand.Move -> {
                if (arrow) {
                    // The trackpad: left and right turn, up and down step.
                    if (cmd.dx != 0) r.turnBy(cmd.dx * PAD_TURN)
                    if (cmd.dy < 0) held.press(TbHeld.Key.Fwd, now, PAD_STEP_MS)
                    if (cmd.dy > 0) held.press(TbHeld.Key.Back, now, PAD_STEP_MS)
                } else {
                    if (cmd.dy < 0) held.press(TbHeld.Key.Fwd, now)
                    if (cmd.dy > 0) held.press(TbHeld.Key.Back, now)
                    if (cmd.dx < 0) held.press(TbHeld.Key.Left, now)
                    if (cmd.dx > 0) held.press(TbHeld.Key.Right, now)
                }
            }

            is GameCommand.Turn -> held.press(if (cmd.dir < 0) TbHeld.Key.TurnL else TbHeld.Key.TurnR, now)

            GameCommand.QuickTurn -> if (first) r.quickTurn()

            GameCommand.Dash -> if (first) r.dash(held.axis(TbHeld.Key.Fwd, TbHeld.Key.Back, now), held.axis(TbHeld.Key.Right, TbHeld.Key.Left, now))

            is GameCommand.Digit -> if (first && cmd.value in 1..4) r.selectWeapon(cmd.value - 1)

            GameCommand.Select, GameCommand.Primary -> held.press(TbHeld.Key.Fire, now)

            GameCommand.Pause -> if (first) shell?.onPause?.invoke()

            GameCommand.NewGame -> if (first) shell?.onNew?.invoke()

            else -> return false
        }
        return true
    }

    fun draftKey(
        r: TbRun,
        cmd: GameCommand,
        press: GameKeyPress,
    ): Boolean {
        if (press.repeatCount > 0) return true
        val arrow = press.keyCode in InputFeel.ARROWS
        val n = r.draftChoices.size
        when {
            cmd is GameCommand.Digit && cmd.value in 1..3 -> r.pickBoon(cmd.value - 1)
            cmd is GameCommand.Move && arrow -> if (cmd.dx != 0 && n > 0) selected = (selected + cmd.dx).mod(n)
            cmd is GameCommand.Move -> r.pickBoon(if (cmd.dx < 0) 0 else if (cmd.dx > 0) 2 else 1)
            cmd == GameCommand.Primary || cmd == GameCommand.Select -> r.pickBoon(selected.coerceIn(0, maxOf(0, n - 1)))
            else -> return false
        }
        hud++
        return true
    }

    fun shrineKey(
        cmd: GameCommand,
        press: GameKeyPress,
    ): Boolean {
        if (press.repeatCount > 0) return true
        val all = TbUpgrade.entries
        when {
            cmd is GameCommand.Move -> if (cmd.dy != 0) shrineSel = (shrineSel + cmd.dy).mod(all.size)
            cmd == GameCommand.Primary || cmd == GameCommand.Select -> buy(all[shrineSel])
            press.keyCode == KeyEvent.KEYCODE_U || cmd == GameCommand.Pause || cmd == GameCommand.NewGame -> shrine = false
            else -> return false
        }
        hud++
        return true
    }

    fun onDown(press: GameKeyPress): Boolean {
        val cmd = command(press) ?: return false
        val r = run
        val first = press.repeatCount == 0
        if (screen == TbScreenState.Select || r == null) {
            if (!first) return true
            if (shrine) return shrineKey(cmd, press)
            when {
                cmd is GameCommand.Move -> if (cmd.dy != 0) gateIdx = (gateIdx + cmd.dy).coerceIn(0, TartarusData.gates.lastIndex)
                press.keyCode == KeyEvent.KEYCODE_U -> shrine = true
                cmd is GameCommand.Digit && cmd.value in 1..4 -> cycleSetting(cmd.value)
                cmd == GameCommand.Primary || cmd == GameCommand.Select -> startRun()
                cmd == GameCommand.Pause -> shell?.onPause?.invoke()
                else -> return false
            }
            hud++
            return true
        }
        if (shrine) return shrineKey(cmd, press)
        return when (r.phase) {
            TbPhase.Draft -> {
                draftKey(r, cmd, press)
            }

            TbPhase.Fallen, TbPhase.Sealed -> {
                if (!first) return true
                val sealed = r.phase == TbPhase.Sealed
                when {
                    press.keyCode == KeyEvent.KEYCODE_U && !sealed -> shrine = true
                    cmd == GameCommand.Primary || cmd == GameCommand.Select || (!sealed && cmd == GameCommand.Digit(4)) -> {
                        if (sealed) {
                            gateIdx = minOf(TartarusData.gates.lastIndex, if (r.gate.number >= 4) gateIdx else gateIdx + 1)
                            toSelect()
                        } else {
                            startRun()
                        }
                    }

                    cmd == GameCommand.NewGame || cmd == GameCommand.Pause -> toSelect()
                    else -> return false
                }
                hud++
                true
            }

            else -> {
                combatKey(r, cmd, press)
            }
        }
    }

    fun onUp(press: GameKeyPress) {
        val cmd = command(press) ?: return
        val arrow = press.keyCode in InputFeel.ARROWS
        when (cmd) {
            is GameCommand.Move -> {
                if (arrow) {
                    held.release(TbHeld.Key.Fwd)
                    held.release(TbHeld.Key.Back)
                } else {
                    if (cmd.dy < 0) held.release(TbHeld.Key.Fwd)
                    if (cmd.dy > 0) held.release(TbHeld.Key.Back)
                    if (cmd.dx < 0) held.release(TbHeld.Key.Left)
                    if (cmd.dx > 0) held.release(TbHeld.Key.Right)
                }
            }

            is GameCommand.Turn -> held.release(if (cmd.dir < 0) TbHeld.Key.TurnL else TbHeld.Key.TurnR)

            GameCommand.Select, GameCommand.Primary -> held.release(TbHeld.Key.Fire)

            else -> Unit
        }
    }

    DisposableEffect(host, profile, bound) {
        host.handler = { onDown(it) }
        host.upHandler = { onUp(it) }
        host.commandHandler = { cmd -> if (cmd == GameCommand.NewGame) toSelect().let { true } else false }
        onDispose {
            host.handler = null
            host.upHandler = null
            host.commandHandler = null
        }
    }
    // Focus loss or a menu on top lets go of every key, so nothing keeps walking in the dark.
    LaunchedEffect(suspended) {
        if (suspended) {
            held.clear()
            touchHeld.clear()
        }
    }

    LaunchedEffect(run, suspended, ready) {
        var last = 0L
        while (!suspended && ready) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / TB_NANOS).coerceAtMost(0.05f)
                last = now
                val r = run
                if (r != null && dt > 0f) {
                    val ms = now / 1_000_000L
                    val input =
                        TbInput(
                            forward = (held.axis(TbHeld.Key.Fwd, TbHeld.Key.Back, ms) + touchHeld.axis(TbHeld.Key.Fwd, TbHeld.Key.Back, ms)).coerceIn(-1f, 1f),
                            strafe = (held.axis(TbHeld.Key.Right, TbHeld.Key.Left, ms) + touchHeld.axis(TbHeld.Key.Right, TbHeld.Key.Left, ms)).coerceIn(-1f, 1f),
                            turn = held.axis(TbHeld.Key.TurnR, TbHeld.Key.TurnL, ms) + touchHeld.axis(TbHeld.Key.TurnR, TbHeld.Key.TurnL, ms),
                            fire = held.on(TbHeld.Key.Fire, ms) || touchHeld.on(TbHeld.Key.Fire, ms),
                        )
                    val before = r.phase
                    r.step(dt, input)
                    if (r.events.isNotEmpty()) {
                        r.events.forEach { feedback.play(it) }
                        r.events.clear()
                    }
                    if (r.phase != before) {
                        record(r)
                        hud++
                    }
                }
                frame++
            }
        }
    }

    val r = run
    if (hud < 0) return
    val label =
        when {
            screen == TbScreenState.Select || r == null -> "Choose a Gate"
            r.phase == TbPhase.Fallen -> "Fallen: ${r.wipeReason}"
            r.phase == TbPhase.Sealed -> "${r.gate.name} sealed"
            else -> "${r.gate.name} · wave ${r.wave}"
        }
    GameFrame(
        title = "Tartarus Breach",
        subtitle = "Fight down through the Underworld and seal the breach.",
        statusLabel = label,
        status = if (r != null && screen == TbScreenState.Run) "${r.hp.toInt().coerceAtLeast(0)} HP" else "",
        hint = "Turn: drag on the board, trackpad or J L. W A S D move. Space fires. Q U E R pick an artifact. Shift dashes.",
        actions =
            listOf(
                GameAction(tab = true, label = "Pause", key = "P") { shell?.onPause?.invoke() },
                GameAction(tab = true, label = "New", key = "N") { toSelect() },
            ),
        tallExtras = {
            if (screen == TbScreenState.Run && r != null) TbTouchPad(r, touchHeld) { hud++ }
        },
    ) { side ->
        val boardW = maxOf(side, LocalBoardWidth.current ?: side)
        Box(Modifier.width(boardW).height(side).clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                !ready -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Descending…", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }

                screen == TbScreenState.Select || r == null -> {
                    Box(Modifier.size(side)) {
                        TbSelect(sprites, meta, gateIdx, onPick = { gateIdx = it }, onGo = ::startRun, onShrine = { shrine = true }, onSetting = ::cycleSetting)
                    }
                }

                else -> {
                    TbArena(r, sprites, frame, keys, touchHeld)
                    Box(Modifier.size(side)) {
                        TbOverlays(r, sprites, selected, onPick = { r.pickBoon(it); hud++ }, onRetry = ::startRun, onShrine = { shrine = true }, onGates = ::toSelect, onNext = {
                            gateIdx = minOf(TartarusData.gates.lastIndex, gateIdx + if (r.gate.number >= 4) 0 else 1)
                            toSelect()
                        })
                    }
                }
            }
            if (shrine) {
                Box(Modifier.size(side)) { TbShrine(meta, shrineSel, onSelect = { shrineSel = it }, onBuy = ::buy, onClose = { shrine = false }) }
            }
        }
    }
}

private const val PAD_TURN = 0.11f
private const val PAD_STEP_MS = 140L

@Composable
private fun TbArena(
    r: TbRun,
    sprites: TbSprites,
    frame: Int,
    keys: KeyBindings,
    touch: TbHeld,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val cam = remember { TbCam() }
    val labels = (1..4).map { keys.keysFor("w$it").firstOrNull()?.let { k -> KeyBindings.label(k) } ?: "?" }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(r) { detectDragGestures { _, d -> r.turnBy(d.x / size.height * 2.4f) } }
            .pointerInput(r) {
                detectTapGestures(onPress = {
                    touch.press(TbHeld.Key.Fire, System.nanoTime() / 1_000_000L, 60_000L)
                    tryAwaitRelease()
                    touch.release(TbHeld.Key.Fire)
                })
            },
    ) {
        if (frame < 0) return@Canvas
        // The board may be wider than it is tall: draw the square in the middle and let the view and bar run out to the edges.
        translate(left = (size.width - size.height) / 2f) {
            drawTartarusWorld(r, sprites, cam)
            drawWeapon(r, sprites, cam)
            drawSight(r, cam)
            drawHud(r, sprites, cam, measurer, labels)
        }
    }
}

/** Sizes for the overlays are fractions of the board, so one layout fits the Q25's 720 and the Titan 2's 1440 squares. */
@Composable
private fun unit(w: Dp): (Float) -> TextUnit = { frac -> (w.value * frac).sp }

@Composable
private fun TbSelect(
    sprites: TbSprites,
    meta: TbMeta,
    selected: Int,
    onPick: (Int) -> Unit,
    onGo: () -> Unit,
    onShrine: () -> Unit,
    onSetting: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = unit(maxWidth)
        val s = maxWidth
        Image(sprites.background(selected + 1), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC0A0812), Color(0x660A0812), Color(0xE60A0812)))))
        Column(Modifier.fillMaxSize().padding(horizontal = s * 0.06f, vertical = s * 0.025f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Which balance this build plays with, so a phone test can be matched to a change.
                Text("BALANCE ${TbRun.BALANCE}", color = Color(0x99FFFFFF), fontSize = u(0.028f))
                Text("OBOLS: ${"%,d".format(meta.obols)}", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.04f))
            }
            Text("TARTARUS", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.105f), lineHeight = u(0.105f))
            Text("BREACH", color = Color(0xFFE6B45A), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.105f), lineHeight = u(0.105f))
            Spacer(Modifier.height(s * 0.02f))
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s * 0.015f)) {
                TartarusData.gates.forEachIndexed { i, g ->
                    val open = g.number <= meta.unlocked
                    val on = i == selected
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth().clickable { if (on) onGo() else onPick(i) },
                        shape = RoundedCornerShape(s * 0.012f),
                        color = if (on) Color(0xE6403A30) else Color(0xCC2A2620),
                        border = BorderStroke(if (on) s * 0.006f else s * 0.003f, if (on) TbColors.Gold else TbColors.GoldDark),
                    ) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "GATE 0${g.number}: ${GATE_SHORT[i]}",
                                color = if (open) Color(0xFFEDE3C8) else Color(0xFF8A8478),
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = u(0.046f),
                            )
                            val sub =
                                when {
                                    !open -> "LOCKED"
                                    meta.bestClear[i] > 0 -> "BEST: ${clock(meta.bestClear[i].toFloat())}"
                                    meta.bestWave[i] > 0 -> "FURTHEST: WAVE ${meta.bestWave[i]}"
                                    else -> "UNEXPLORED"
                                }
                            Text(sub, color = if (open) TbColors.Gold else Color(0xFF8A8478), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.03f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(s * 0.015f))
            val chips =
                listOf(
                    "DIFFICULTY: ${listOf("EASY", "NORMAL", "HARD")[meta.difficulty.coerceIn(0, 2)]}",
                    "AIM ASSIST: ${listOf("OFF", "NORMAL", "STRONG")[meta.aim.coerceIn(0, 2)]}",
                    "SOUND: ${if (meta.sound) "ON" else "OFF"}",
                    "VIBRATION: ${if (meta.vibrate) "ON" else "OFF"}",
                )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s * 0.012f)) {
                for (rowChips in listOf(0, 2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s * 0.015f)) {
                        for (k in rowChips until rowChips + 2) {
                            Surface(
                                modifier = Modifier.weight(1f).clickable { onSetting(k + 1) },
                                shape = RoundedCornerShape(s * 0.01f),
                                color = Color(0xCC2A2620),
                                border = BorderStroke(s * 0.003f, TbColors.GoldDark),
                            ) {
                                Row(Modifier.padding(horizontal = s * 0.015f, vertical = s * 0.011f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s * 0.01f)) {
                                    Text("[${k + 1}]", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.03f))
                                    Text(chips[k], color = Color(0xFFEDE3C8), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.03f))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(s * 0.015f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                KeyHint("W/S", "SELECT", u)
                KeyHint("ENTER", "DESCEND", u)
                Box(Modifier.clickable { onShrine() }) { KeyHint("U", "SHRINE", u) }
            }
        }
    }
}

@Composable
private fun KeyHint(
    key: String,
    what: String,
    u: (Float) -> TextUnit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF4A453D), border = BorderStroke(1.dp, Color(0xFF8A8478))) {
            Text("[$key]", Modifier.padding(horizontal = 5.dp, vertical = 1.dp), color = Color(0xFFE6E0D2), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.034f))
        }
        Text(what, color = Color(0xFFEDE3C8), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.034f))
    }
}

/** The boon draft, the Fallen and Gate Sealed panels, drawn over the frozen arena. */
@Composable
private fun TbOverlays(
    r: TbRun,
    sprites: TbSprites,
    selected: Int,
    onPick: (Int) -> Unit,
    onRetry: () -> Unit,
    onShrine: () -> Unit,
    onGates: () -> Unit,
    onNext: () -> Unit,
) {
    when (r.phase) {
        TbPhase.Draft -> TbDraft(r, sprites, selected, onPick)
        TbPhase.Fallen -> TbEnd(r, fallen = true, onPrimary = onRetry, onShrine = onShrine, onGates = onGates)
        TbPhase.Sealed -> TbEnd(r, fallen = false, onPrimary = onNext, onShrine = onShrine, onGates = onGates)
        else -> Unit
    }
}

@Composable
private fun TbDraft(
    r: TbRun,
    sprites: TbSprites,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = unit(maxWidth)
        val s = maxWidth
        Box(Modifier.fillMaxSize().background(Color(0xD90A0812)))
        Column(Modifier.fillMaxSize().padding(horizontal = s * 0.05f, vertical = s * 0.03f), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(s * 0.01f), color = Color(0xFFDCD6C6), border = BorderStroke(s * 0.007f, TbColors.Gold)) {
                Text(
                    "WAVE ${r.wave.toString().padStart(2, '0')} CLEARED – CHOOSE A BOON",
                    Modifier.padding(vertical = s * 0.025f),
                    color = Color(0xFF5A3F12),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Black,
                    fontSize = u(0.043f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(s * 0.03f))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s * 0.025f)) {
                r.draftChoices.forEachIndexed { i, b ->
                    val accent = colorOf(b.accent)
                    val on = i == selected
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onPick(i) },
                        shape = RoundedCornerShape(s * 0.015f),
                        color = Color(0xFF3A342C),
                        border = BorderStroke(if (on) s * 0.011f else s * 0.006f, accent),
                    ) {
                        Column(Modifier.padding(s * 0.015f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s * 0.012f)) {
                            Spacer(Modifier.height(s * 0.01f))
                            Image(sprites.boon(b.deity), null, Modifier.size(s * 0.17f))
                            Text(b.name.uppercase(), color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.034f), textAlign = TextAlign.Center, lineHeight = u(0.038f))
                            Text(b.description, color = Color(0xFFDDD6C4), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.027f), textAlign = TextAlign.Center, lineHeight = u(0.032f))
                            Spacer(Modifier.weight(1f))
                            val stacks = r.boons[b.id] ?: 0
                            if (stacks > 0) Text("OWNED x$stacks", color = accent, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.024f))
                            r.completes(b)?.let { Text("PAIR BONUS: ${it.substringBefore(":")}", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.024f), textAlign = TextAlign.Center) }
                            if (b.cursed) Text("CURSED", color = TbColors.Blood, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.026f))
                            Surface(shape = RoundedCornerShape(s * 0.01f), color = accent.copy(alpha = 0.25f), border = BorderStroke(s * 0.004f, accent)) {
                                Text("[${i + 1}]", Modifier.padding(horizontal = s * 0.02f, vertical = s * 0.006f), color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.05f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(s * 0.025f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("[1 / 2 / 3] CLAIM BOON", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.045f))
                Text("OBOLS ${r.obols}", color = Color(0xFFEDE3C8), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.04f))
            }
        }
    }
}

@Composable
private fun TbEnd(
    r: TbRun,
    fallen: Boolean,
    onPrimary: () -> Unit,
    onShrine: () -> Unit,
    onGates: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = unit(maxWidth)
        val s = maxWidth
        Box(Modifier.fillMaxSize().background(Color(0xCC0A0812)), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.fillMaxWidth(0.86f),
                shape = RoundedCornerShape(s * 0.02f),
                color = Color(0xF22A2620),
                border = BorderStroke(s * 0.007f, if (fallen) TbColors.Blood else TbColors.Gold),
            ) {
                Column(Modifier.padding(s * 0.04f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s * 0.015f)) {
                    Text(
                        if (fallen) "YOU HAVE FALLEN" else if (r.gate.number >= 4) "TARTARUS IS SEALED" else "GATE SEALED",
                        color = if (fallen) Color(0xFFFF6A6A) else TbColors.Gold,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Black,
                        fontSize = u(0.07f),
                    )
                    Text(
                        if (fallen) r.wipeReason else r.gate.name,
                        color = Color(0xFFB8B0A0),
                        fontFamily = FontFamily.Serif,
                        fontSize = u(0.032f),
                    )
                    Spacer(Modifier.height(s * 0.01f))
                    StatRow("Wave reached", if (r.sealed) "${r.gate.stages.size} of ${r.gate.stages.size}" else "${r.wave} of ${r.gate.stages.size}", u)
                    StatRow("Enemies banished", r.kills.toString(), u)
                    StatRow("Obols earned", "+${r.obols}", u)
                    if (r.sealed) StatRow("Time", clock(r.runTime), u)
                    Spacer(Modifier.height(s * 0.015f))
                    Row(horizontalArrangement = Arrangement.spacedBy(s * 0.03f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clickable { onPrimary() }) { KeyHint(if (fallen) "R" else "ENTER", if (fallen) "RETRY" else "CONTINUE", u) }
                        if (fallen) Box(Modifier.clickable { onShrine() }) { KeyHint("U", "SHRINE", u) }
                        Box(Modifier.clickable { onGates() }) { KeyHint("N", "GATES", u) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    u: (Float) -> TextUnit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.uppercase(), color = Color(0xFFDDD6C4), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.04f))
        Text(value, color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.04f))
    }
}

/** The Underworld Shrine: Obols buy permanent upgrades. */
@Composable
private fun TbShrine(
    meta: TbMeta,
    selected: Int,
    onSelect: (Int) -> Unit,
    onBuy: (TbUpgrade) -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val u = unit(maxWidth)
        val s = maxWidth
        Box(Modifier.fillMaxSize().background(Color(0xF00A0812)).clickable { onClose() }, contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(s * 0.015f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("UNDERWORLD SHRINE", color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.065f))
                Text("OBOLS: ${"%,d".format(meta.obols)}", color = Color(0xFFEDE3C8), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = u(0.042f))
                TbUpgrade.entries.forEachIndexed { i, up ->
                    val on = i == selected
                    val lvl = meta.level(up)
                    val maxed = lvl >= up.maxLevel
                    Surface(
                        Modifier.fillMaxWidth().clickable { if (on) onBuy(up) else onSelect(i) },
                        shape = RoundedCornerShape(s * 0.012f),
                        color = if (on) Color(0xFF403A30) else Color(0xFF2A2620),
                        border = BorderStroke(if (on) s * 0.006f else s * 0.003f, if (on) TbColors.Gold else TbColors.GoldDark),
                    ) {
                        Row(Modifier.padding(horizontal = s * 0.025f, vertical = s * 0.014f), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(up.label.uppercase(), color = TbColors.Gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = u(0.04f))
                                Text(up.blurb, color = Color(0xFFDDD6C4), fontFamily = FontFamily.Serif, fontSize = u(0.03f))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("●".repeat(lvl) + "○".repeat(up.maxLevel - lvl), color = TbColors.Gold, fontSize = u(0.034f))
                                Text(
                                    if (maxed) "MAX" else "${meta.cost(up)} OBOLS",
                                    color = if (maxed || meta.canBuy(up)) Color(0xFF9DE89A) else Color(0xFFFF8A7A),
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = u(0.03f),
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(s * 0.03f)) {
                    KeyHint("W/S", "CHOOSE", u)
                    KeyHint("ENTER", "BUY", u)
                    KeyHint("U", "BACK", u)
                }
            }
        }
    }
}

/** On a touch phone with no keyboard: hold buttons to walk and fire, and the four artifacts. */
@Composable
private fun TbTouchPad(
    r: TbRun,
    held: TbHeld,
    onChange: () -> Unit,
) {
    @Composable
    fun Hold(
        label: String,
        key: TbHeld.Key,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier =
                modifier.height(44.dp).pointerInput(key) {
                    detectTapGestures(onPress = {
                        held.press(key, System.nanoTime() / 1_000_000L, 60_000L)
                        tryAwaitRelease()
                        held.release(key)
                    })
                },
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF2A2620),
            border = BorderStroke(1.dp, TbColors.GoldDark),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(label, color = TbColors.Gold, fontWeight = FontWeight.Bold) }
        }
    }

    @Composable
    fun Tap(
        label: String,
        modifier: Modifier = Modifier,
        selected: Boolean = false,
        onClick: () -> Unit,
    ) {
        Surface(
            modifier = modifier.height(44.dp).clickable { onClick(); onChange() },
            shape = RoundedCornerShape(8.dp),
            color = if (selected) Color(0xFF403A30) else Color(0xFF2A2620),
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) TbColors.Gold else TbColors.GoldDark),
        ) {
            Box(contentAlignment = Alignment.Center) { Text(label, color = TbColors.Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Hold("◀ Strafe", TbHeld.Key.Left, Modifier.weight(1f))
            Hold("▲ Forward", TbHeld.Key.Fwd, Modifier.weight(1f))
            Hold("▼ Back", TbHeld.Key.Back, Modifier.weight(1f))
            Hold("Strafe ▶", TbHeld.Key.Right, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Hold("FIRE", TbHeld.Key.Fire, Modifier.weight(2f))
            Tap("DASH", Modifier.weight(1f)) { r.dash() }
            Tap("SPIN", Modifier.weight(1f)) { r.quickTurn() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.weapons.forEachIndexed { i, w ->
                Tap(w.label, Modifier.weight(1f), selected = i == r.weaponIdx) { r.selectWeapon(i) }
            }
        }
    }
}

internal val TbSerifStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
