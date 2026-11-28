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

/** A set of games the installer can pick all at once; every game is also pickable on its own. */
internal data class InstallerGroup(
    val name: String,
    val ids: List<String>,
)

/** Every Sable game the installer offers, grouped in the order shown. */
internal object InstallerCatalog {
    private val all =
        listOf(
            InstallerApp("snakesladders", "Sable Snakes & Ladders", "Two to four players, exact finish"),
            InstallerApp("ludo", "Sable Ludo", "Four colours: bring your tokens home"),
            InstallerApp("reversi", "Sable Reversi", "Play dark against the phone"),
            InstallerApp("mahjong", "Sable Mahjong", "Remove matching free tiles in pairs"),
            InstallerApp("cards", "Sable Card Games", "Ten Indian card games in one app"),
            InstallerApp("solitaire", "Sable Solitaire", "Klondike, one card at a time"),
            InstallerApp("console", "Sable Games Console", "Lua games added without a new build; ships with eight tilt games"),
            InstallerApp("snake", "Sable Snake", "Eat to grow; don't hit a wall or yourself"),
            InstallerApp("blocks", "Sable Blocks", "Falling blocks: fill whole rows to clear them"),
            InstallerApp("bricks", "Sable Bricks", "Break the wall with the ball"),
            InstallerApp("game2048", "Sable 2048", "Slide tiles to merge them up to 2048"),
            InstallerApp("minesweeper", "Sable Minesweeper", "Clear the field without hitting a mine"),
            InstallerApp("tartarus", "Tartarus Breach", "First-person arena roguelite in the Underworld"),
            InstallerApp("gravelgears", "Gravel & Gears", "Nonstop downhill mountain biking"),
            InstallerApp("melons", "Bitter Melons", "Fling melons from a slingshot: four worlds, 36 levels"),
            InstallerApp("scaffold", "Sable Scaffold", "Climb the site, dodge rolling hazards, rescue Juno"),
            InstallerApp("sudoku", "Sable Sudoku", "Fill the 9 x 9 grid"),
            InstallerApp("words", "Sable Words", "Guess the five-letter word in six tries"),
        )

    val groups =
        listOf(
            InstallerGroup("Board games", listOf("snakesladders", "ludo", "reversi", "mahjong")),
            InstallerGroup("Card games", listOf("cards", "solitaire")),
            InstallerGroup("Lua games", listOf("console")),
            InstallerGroup("Retro", listOf("snake", "blocks", "bricks", "game2048", "minesweeper")),
            InstallerGroup("Action and adventure", listOf("tartarus", "gravelgears", "melons", "scaffold")),
            InstallerGroup("Puzzle and word", listOf("sudoku", "words")),
        )

    /** The apps in the order the groups list them. */
    val apps = groups.flatMap { g -> g.ids.map { id -> all.first { it.id == id } } }
}
