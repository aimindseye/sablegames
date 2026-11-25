package org.sableos.games

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GamesCoreTest {
    private val puzzle = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"

    private fun ok(response: String) = response.removePrefix("OK:").also { assertTrue(response, response.startsWith("OK:")) }

    @Test
    fun sudokuRejectsConflictsAndFixedEdits() {
        val state = ok(GamesCore.sudokuStart(puzzle))
        assertEquals("ERR:FIXED_CELL", GamesCore.sudokuSet(state, 0, 0, 4))
        assertEquals("ERR:CONFLICT", GamesCore.sudokuSet(state, 0, 2, 5))
        assertEquals("OK:S1:$puzzle:0,2,4", GamesCore.sudokuSet(state, 0, 2, 4))
        assertEquals("ERR:INVALID_PUZZLE", GamesCore.sudokuStart("5".repeat(81)))
    }

    @Test
    fun sudokuStateReplaysEntries() {
        var state = ok(GamesCore.sudokuStart("0".repeat(81)))
        state = ok(GamesCore.sudokuSet(state, 0, 0, 5))
        assertEquals("ERR:CONFLICT", GamesCore.sudokuSet(state, 0, 1, 5))
        state = ok(GamesCore.sudokuSet(state, 0, 0, 4))
        assertTrue(GamesCore.sudokuBoard(state).startsWith("OK:4"))
        assertEquals("ERR:INVALID_STATE", GamesCore.sudokuBoard("S1:$puzzle:x"))
    }

    @Test
    fun sudokuKnowsWhenSolved() {
        val solution = "534678912672195348198342567859761423426853791713924856961537284287419635345286179"
        val state = ok(GamesCore.sudokuStart(solution.dropLast(1) + "0"))
        assertEquals(false, GamesCore.sudokuSolved(state))
        assertEquals(true, GamesCore.sudokuSolved(ok(GamesCore.sudokuSet(state, 8, 8, 9))))
    }

    @Test
    fun minesweeperIsDeterministicAndReplayable() {
        val state = ok(GamesCore.minesweeperStart(9, 9, 10, 1803))
        assertEquals("M1:9:9:10:1803:", state)
        val reveal = GamesCore.minesweeperReveal(state, 4, 4).split('|')
        assertEquals(reveal, GamesCore.minesweeperReveal(state, 4, 4).split('|'))
        assertEquals("OK", reveal[0])
        val view = ok(GamesCore.minesweeperView(reveal[1]))
        assertEquals(81, view.length)
        assertTrue(view[4 * 9 + 4] != 'H')
        assertEquals("ERR:INVALID_BOARD", GamesCore.minesweeperStart(3, 3, 9, 1))
        assertEquals("ERR:INVALID_SEED", GamesCore.minesweeperStart(3, 3, 1, -1))
        assertEquals("ERR:OUT_OF_BOUNDS", GamesCore.minesweeperReveal(state, 9, 0))
    }

    @Test
    fun minesweeperFlagsToggle() {
        val state = ok(GamesCore.minesweeperStart(9, 9, 10, 1803))
        val flagged = GamesCore.minesweeperToggleFlag(state, 0, 0).split('|')
        assertEquals("FLAGGED", flagged[2])
        assertEquals("F", ok(GamesCore.minesweeperView(flagged[1])).take(1))
        assertEquals("HIDDEN", GamesCore.minesweeperToggleFlag(flagged[1], 0, 0).split('|')[2])
    }

    @Test
    fun game2048MergesOncePerMove() {
        val board = intArrayOf(2, 2, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val result = GamesCore.game2048Slide(board, 0)
        assertArrayEquals(longArrayOf(4, 8, 0, 0), result.copyOfRange(0, 4))
        assertEquals(12L, result[16])
        val down = GamesCore.game2048Slide(intArrayOf(2, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0), 3)
        assertEquals(listOf(0L, 0L, 4L, 4L), listOf(down[0], down[4], down[8], down[12]))
    }

    @Test
    fun game2048AddsATileAfterEveryMoveThatChangesTheBoard() {
        val random = Random(7)
        val start = GamesCore.game2048Start(random)
        assertEquals(2, start.count { it != 0 })
        assertTrue(start.all { it == 0 || it == 2 || it == 4 })
        val board = intArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val spawned = GamesCore.game2048Spawn(board, random)
        assertEquals(2, spawned.count { it != 0 })
        assertEquals(2, spawned[0])
        val full = IntArray(16) { if ((it / 4 + it % 4) % 2 == 0) 2 else 4 }
        assertArrayEquals(full, GamesCore.game2048Spawn(full, random))
        assertTrue(GamesCore.game2048Stuck(full))
        assertTrue(!GamesCore.game2048Stuck(board))
    }

    @Test
    fun minesweeperFirstRevealIsSafeAndOpenFieldsFlood() {
        // Eight mines in nine cells: the first cell opened is never one of them, and it wins.
        val tight = ok(GamesCore.minesweeperStart(3, 3, 8, 99))
        val first = GamesCore.minesweeperReveal(tight, 1, 1).split('|')
        assertEquals("WON", first[2])
        // No mines at all: one reveal opens everything.
        val empty = ok(GamesCore.minesweeperStart(9, 9, 0, 5))
        val opened = GamesCore.minesweeperReveal(empty, 0, 0).split('|')
        assertEquals("WON", opened[2])
        assertTrue(ok(GamesCore.minesweeperView(opened[1])).none { it == 'H' })
    }

    @Test
    fun minesweeperEndsOnAMineAndShowsThemAll() {
        var state = ok(GamesCore.minesweeperStart(3, 3, 7, 42))
        state = GamesCore.minesweeperReveal(state, 0, 0).split('|')[1]
        var last = ""
        for (i in 1 until 9) {
            val r = GamesCore.minesweeperReveal(state, i % 3, i / 3).split('|')
            state = r[1]
            if (r[2] == "MINE" || r[2] == "WON") {
                last = r[2]
                break
            }
        }
        assertEquals("MINE", last)
        val view = ok(GamesCore.minesweeperView(state))
        assertEquals(7, view.count { it == '*' })
        // Nothing opens or flags after the end.
        assertEquals("IGNORED", GamesCore.minesweeperReveal(state, 2, 2).split('|')[2])
        assertEquals(view, ok(GamesCore.minesweeperView(GamesCore.minesweeperToggleFlag(state, 2, 2).split('|')[1])))
    }

    @Test
    fun sudokuShuffleGivesADifferentValidPuzzle() {
        val random = Random(3)
        val shuffled = GamesCore.sudokuShuffle(puzzle, random)
        assertTrue(shuffled != puzzle)
        assertEquals(puzzle.count { it == '0' }, shuffled.count { it == '0' })
        ok(GamesCore.sudokuStart(shuffled))
        // No digit twice in a row, column or box among the clues.
        val units =
            (0 until 9).map { r -> (0 until 9).map { r * 9 + it } } +
                (0 until 9).map { c -> (0 until 9).map { it * 9 + c } } +
                (0 until 9).map { b -> (0 until 9).map { (b / 3 * 3 + it / 3) * 9 + b % 3 * 3 + it % 3 } }
        units.forEach { unit ->
            val digits = unit.map { shuffled[it] }.filter { it != '0' }
            assertEquals(digits.size, digits.toSet().size)
        }
    }
}
