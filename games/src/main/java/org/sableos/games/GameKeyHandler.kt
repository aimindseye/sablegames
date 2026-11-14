package org.sableos.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Routes key-downs to [onCommand] while this game screen is shown; false leaves the key to the focused view. */
@Composable
internal fun GameKeyHandler(
    game: GameKind,
    onCommand: (GameCommand) -> Boolean,
) {
    val host = LocalGameKeyHost.current
    val profile = LocalDeviceProfile.current
    val latest by rememberUpdatedState(onCommand)
    DisposableEffect(host, game, profile) {
        host.handler = { press -> GameKeymap.command(game, press, profile)?.let { latest(it) } ?: false }
        onDispose { host.handler = null }
    }
}
