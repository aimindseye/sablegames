package org.sableos.games

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import org.sableos.design.SableGlobalTheme

/** The key host of the running game; screens install their handler in it. */
internal val LocalGameKeyHost = staticCompositionLocalOf { GameKeyHost() }

class MainActivity : ComponentActivity() {
    private val keyHost = GameKeyHost()

    /** Key codes whose key-down was taken by the game, so their key-up is swallowed too. */
    private val consumedKeyCodes = mutableSetOf<Int>()

    private val profile by lazy { DeviceProfile.current(resources.configuration) }
    private val pacer = TrackpadPacer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InputFeel.load(this)
        setContent {
            SableGlobalTheme(window = window) {
                CompositionLocalProvider(
                    LocalGameKeyHost provides keyHost,
                    LocalDeviceProfile provides profile,
                ) {
                    when (BuildConfig.SABLE_GAME) {
                        "sudoku" -> {
                            GameShell(GameCatalog.sudoku) { SudokuScreen() }
                        }

                        "minesweeper" -> {
                            GameShell(GameCatalog.minesweeper) { MinesweeperScreen() }
                        }

                        "2048" -> {
                            GameShell(GameCatalog.game2048) { Game2048Screen() }
                        }

                        "bricks" -> {
                            GameShell(GameCatalog.bricks) { BricksScreen() }
                        }

                        "snake" -> {
                            GameShell(GameCatalog.snake) { SnakeScreen() }
                        }

                        "blocks" -> {
                            GameShell(GameCatalog.blocks) { BlocksScreen() }
                        }

                        "reversi" -> {
                            GameShell(GameCatalog.reversi) { ReversiScreen() }
                        }

                        "solitaire" -> {
                            GameShell(GameCatalog.solitaire) { SolitaireScreen() }
                        }

                        "mahjong" -> {
                            GameShell(GameCatalog.mahjong) { MahjongScreen() }
                        }

                        "words" -> {
                            GameShell(GameCatalog.words) { WordsScreen() }
                        }

                        "cards" -> {
                            GameShell(
                                GameCatalog.cards,
                            ) { IndianGamesApp("Sable Card Games", CARD_GAMES, emptyList()) }
                        }

                        "snakesladders" -> {
                            GameShell(GameCatalog.snakesLadders) { BoardGameApp("snakes") }
                        }

                        "ludo" -> {
                            GameShell(GameCatalog.ludo) { BoardGameApp("ludo") }
                        }

                        "melons" -> {
                            GameShell(GameCatalog.melons) { MelonsScreen() }
                        }

                        "gravelgears" -> {
                            GameShell(GameCatalog.gravelGears) { GravelGearsScreen() }
                        }

                        "tartarus" -> {
                            GameShell(GameCatalog.tartarus) { TartarusScreen() }
                        }

                        "scaffold" -> {
                            ScaffoldApp()
                        }

                        // The console flavor starts org.sableos.games.console.ConsoleActivity instead.
                        else -> {
                            error("Unsupported Sable game flavor: ${BuildConfig.SABLE_GAME}")
                        }
                    }
                }
            }
        }
    }

    // ComponentActivity.dispatchKeyEvent is a public framework override; lint flags it as androidx-restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val taken =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    val press = event.toGameKeyPress()
                    // Trackpad ticks go at the player's speed from Controls: some are swallowed, or repeated.
                    val steps =
                        if (InputFeel.fromTrackpad(event.keyCode, event.source, profile)) {
                            pacer.steps(event.keyCode, event.eventTime, InputFeel.ticksPerStep())
                        } else {
                            1
                        }
                    var taken = steps == 0
                    repeat(steps) {
                        taken = (keyHost.gate?.invoke(press) ?: (keyHost.handler?.invoke(press) == true)) || taken
                    }
                    taken.also { if (it) consumedKeyCodes += event.keyCode }
                }

                KeyEvent.ACTION_UP -> {
                    keyHost.upHandler?.invoke(event.toGameKeyPress())
                    consumedKeyCodes.remove(event.keyCode)
                }

                else -> {
                    false
                }
            }
        return taken || super.dispatchKeyEvent(event)
    }
}
