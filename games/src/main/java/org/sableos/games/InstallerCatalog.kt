package org.sableos.games

/** One app the installer can fetch: [id] is also the flavor name in the release's file names. */
internal data class InstallerApp(
    val id: String,
    val name: String,
    val blurb: String,
) {
    val packageName get() = "$PACKAGE_PREFIX$id"

    companion object {
        const val PACKAGE_PREFIX = "io.github.aimindseye.sablegames."
    }
}

/** Every Sable game the installer offers, in the order shown. */
internal object InstallerCatalog {
    val apps =
        listOf(
            InstallerApp("tartarus", "Tartarus Breach", "First-person arena roguelite in the Underworld"),
            InstallerApp("gravelgears", "Gravel & Gears", "Nonstop downhill bike ride"),
            InstallerApp("melons", "Bitter Melons", "Physics puzzle of fruit and gravity"),
            InstallerApp("cards", "Sable Card Games", "Ten Indian card games in one app"),
            InstallerApp("ludo", "Sable Ludo", "Race four tokens home"),
            InstallerApp("snakesladders", "Sable Snakes & Ladders", "The classic board race"),
            InstallerApp("sudoku", "Sable Sudoku", "Number puzzles"),
            InstallerApp("minesweeper", "Sable Minesweeper", "Clear the field"),
            InstallerApp("game2048", "Sable 2048", "Slide and merge tiles"),
            InstallerApp("bricks", "Sable Bricks", "Break the wall"),
            InstallerApp("snake", "Sable Snake", "Eat, grow, don't crash"),
            InstallerApp("blocks", "Sable Blocks", "Stack and clear lines"),
            InstallerApp("reversi", "Sable Reversi", "Outflank your opponent"),
            InstallerApp("solitaire", "Sable Solitaire", "Klondike"),
            InstallerApp("mahjong", "Sable Mahjong", "Match the tiles"),
            InstallerApp("words", "Sable Words", "Word puzzles"),
            InstallerApp("scaffold", "Sable Scaffold", "Build and balance"),
            InstallerApp("console", "Sable Games Console", "Lua games you add from a folder"),
        )
}
