package dev.minusgix.hyperbeam.input

/** Where keyboard input for the remote browser goes. */
interface RemoteKeySink {
    fun key(key: String, mods: Mods = Mods.NONE, phase: Phase = Phase.PRESS)
    fun text(text: String)
    fun backspace(count: Int)

    enum class Phase(val wire: String) { DOWN("down"), UP("up"), PRESS("press") }
}
