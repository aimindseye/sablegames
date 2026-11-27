# Tartarus Breach — Physical Keyboard (PKB) Keymap & Input Spec
**Target Hardware:** Zinwa Q25, Zinwa P26, Zinwa Q27, Unihertz Titan 2 / Titan 2 Elite  
**Target OS:** Android 10+ (API 29+)  
**Input Philosophy:** Zero on-screen touch controls. All combat, aiming, and menu navigation must operate cleanly within a 2–3 key rollover envelope.

---

## 1. Master Android `KeyEvent` Binding Table

> **No-Number-Row Safeguard:** Compact 35-key PKB layouts (e.g., Zinwa Q25/Q27) place digits behind an `ALT` modifier layer. Every numeric action (`1/2/3`) **must** accept unshifted letter fallbacks so players never press `ALT` mid-run.

| Action | Primary Android `KeyEvent` | Fallback / Alt Binding | Input Mode |
|---|---|---|---|
| **Move Forward** | `KEYCODE_W` | `KEYCODE_DPAD_UP` | Hold |
| **Move Backward** | `KEYCODE_S` | `KEYCODE_DPAD_DOWN` | Hold |
| **Strafe Left** | `KEYCODE_A` | `KEYCODE_DPAD_LEFT` | Hold |
| **Strafe Right** | `KEYCODE_D` | `KEYCODE_DPAD_RIGHT` | Hold |
| **Turn Left (Yaw -)** | `KEYCODE_J` | Trackpad / Capacitive Swipe Left | Hold / Continuous |
| **Turn Right (Yaw +)** | `KEYCODE_L` | Trackpad / Capacitive Swipe Right | Hold / Continuous |
| **180° Quick-Turn** | `KEYCODE_K` | Fast Trackpad Flick (`dx > threshold`) | Tap / Gesture |
| **Primary Attack** | `KEYCODE_SPACE` | `KEYCODE_DPAD_CENTER` (Trackpad Click) | Tap or Hold (Auto-Repeat) |
| **Hermes' Dash** | `KEYCODE_SHIFT_LEFT` | `KEYCODE_SYM` / `KEYCODE_ALT_LEFT` | Tap (Buffered 120ms) |
| **Weapon 1 (Sunbow)** | `KEYCODE_Q` | — | Tap |
| **Weapon 2 (Trident)** | `KEYCODE_U` (WASD) / `KEYCODE_W` (ESDF) | `KEYCODE_2` | Tap |
| **Weapon 3 (Bident)** | `KEYCODE_E` | `KEYCODE_3` | Tap |
| **Weapon 4 (Aegis Bolt)** | `KEYCODE_R` | `KEYCODE_4` | Tap |
| **Boon Draft Left** | `KEYCODE_1` | `KEYCODE_A` or `KEYCODE_Q` | Tap (Modal Only) |
| **Boon Draft Center** | `KEYCODE_2` | `KEYCODE_S` or `KEYCODE_W` | Tap (Modal Only) |
| **Boon Draft Right** | `KEYCODE_3` | `KEYCODE_D` or `KEYCODE_E` | Tap (Modal Only) |
| **Confirm / Descend** | `KEYCODE_ENTER` | `KEYCODE_DPAD_CENTER` | Tap |
| **Pause / Back** | `KEYCODE_DEL` (Backspace) | `KEYCODE_ESCAPE` | Tap |
| **Instant Retry (Death)** | `KEYCODE_R` | `KEYCODE_ENTER` | Tap (<200ms reset) |

> **Weapon Hotkey Conflict Note:** Expose two presets in Settings: **Preset A (WASD + Q/U/E/R)** and **Preset B (ESDF + Q/W/R/T)** so players can keep Poseidon's Trident on `W` without conflicting with Move Forward.

---

## 2. Trackpad & Capacitive Keyboard Turning Spec
- **Event Hook:** Override `onGenericMotionEvent(event: MotionEvent)` on the primary `SurfaceView` / `GLSurfaceView`.
- **Supported Input Sources:** `SOURCE_TRACKBALL`, `SOURCE_TOUCHPAD` (Zinwa optical trackpad), `SOURCE_MOUSE` / `SOURCE_STYLUS` (Titan 2 capacitive scroll).
- **Axis Reading:** Read `AXIS_X` / `AXIS_HSCROLL` with a **deadzone of `0.08f`** and a non-linear response curve: `yawDelta = sign(dx) * (abs(dx) ^ 1.35f) * userSensitivity`.
- **180° Quick-Turn Flick:** If `abs(dx) > 0.85f` within `< 60ms`, trigger a smooth `140ms` eased 180° camera spin.

---

## 3. Rollover & Input Buffering Safeguards
1. **Auto-Repeat Primary Attack:** Holding `KEYCODE_SPACE` continuously fires at `fire_rate_ms` from `weapons.json`.
2. **SOCD Resolution:** Last-Input Priority when opposite directions (`A+D` or `W+S`) are held simultaneously.
3. **Focus-Loss Flush:** Clear `pressedKeys` on `onWindowFocusChanged(false)` or `onPause()`.
4. **Horizontal Auto-Aim Magnetism:** Apply `auto_aim_magnetism_deg` (`4.5°–10.0°`) horizontally; vertical aim is 100% automatic.

---

## 4. Reference Kotlin Input Handler
```kotlin
class PkbInputController(private val gameEngine: ArenaEngine) {

    private val pressedKeys = HashSet<Int>()
    var trackpadSensitivity = 2.4f

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        pressedKeys.add(keyCode)

        when (gameEngine.currentState) {
            GameState.BOON_DRAFT -> handleBoonDraftKey(keyCode)
            GameState.GAME_OVER -> if (keyCode == KeyEvent.KEYCODE_R || keyCode == KeyEvent.KEYCODE_ENTER) {
                gameEngine.instantRestart()
            }
            GameState.COMBAT -> handleCombatTap(keyCode)
            else -> Unit
        }
        return true
    }

    fun onKeyUp(keyCode: Int): Boolean {
        pressedKeys.remove(keyCode)
        return true
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isTrackpad = (event.source and InputDevice.SOURCE_CLASS_TRACKBALL) != 0 ||
                         (event.source and InputDevice.SOURCE_TOUCHPAD) != 0
        if (!isTrackpad) return false

        val dx = event.getAxisValue(MotionEvent.AXIS_X)
        if (kotlin.math.abs(dx) > 0.08f) {
            val curved = kotlin.math.sign(dx) * Math.pow(kotlin.math.abs(dx).toDouble(), 1.35).toFloat()
            gameEngine.applyYawDelta(curved * trackpadSensitivity)
        }
        return true
    }

    fun clearAll() = pressedKeys.clear()
}
```
