package dev.androidagent.avatar

/**
 * Atlas geometry and per-row timing for Codex pet spritesheets.
 *
 * Derived from
 * `$HOME/.codex/skills/hatch-pet/references/animation-rows.md` (the
 * authoritative spec) and `compose_atlas.py`'s `ROW_SPECS`. The atlas is
 * 8 columns x 9 rows, with 192x208 px cells. Cells past each row's used
 * column count are required to be fully transparent.
 */
object PetAnimation {
    const val COLUMNS = 8
    const val ROWS = 9
    const val CELL_WIDTH = 192
    const val CELL_HEIGHT = 208
    const val ATLAS_WIDTH = COLUMNS * CELL_WIDTH
    const val ATLAS_HEIGHT = ROWS * CELL_HEIGHT

    enum class State(val row: Int, val frameDurationsMs: IntArray) {
        Idle(0, intArrayOf(280, 110, 110, 140, 140, 320)),
        RunningRight(1, intArrayOf(120, 120, 120, 120, 120, 120, 120, 220)),
        RunningLeft(2, intArrayOf(120, 120, 120, 120, 120, 120, 120, 220)),
        Waving(3, intArrayOf(140, 140, 140, 280)),
        Jumping(4, intArrayOf(140, 140, 140, 140, 280)),
        Failed(5, intArrayOf(140, 140, 140, 140, 140, 140, 140, 240)),
        Waiting(6, intArrayOf(150, 150, 150, 150, 150, 260)),
        Running(7, intArrayOf(120, 120, 120, 120, 120, 220)),
        Review(8, intArrayOf(150, 150, 150, 150, 150, 280));

        val frameCount: Int get() = frameDurationsMs.size
    }
}
