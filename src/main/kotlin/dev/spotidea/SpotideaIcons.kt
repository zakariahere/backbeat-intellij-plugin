package dev.spotidea

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The IDE picks the `_dark` sibling of each SVG automatically when a dark theme is active. */
object SpotideaIcons {
    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", SpotideaIcons::class.java)

    @JvmField val Play = load("play")
    @JvmField val Pause = load("pause")
    @JvmField val Next = load("next")
    @JvmField val Previous = load("previous")
    @JvmField val Shuffle = load("shuffle")
    @JvmField val ShuffleOn = load("shuffleOn")
    @JvmField val Repeat = load("repeat")
    @JvmField val RepeatOn = load("repeatOn")
    @JvmField val RepeatOne = load("repeatOne")
    @JvmField val Heart = load("heart")
    @JvmField val HeartFilled = load("heartFilled")
    @JvmField val Volume = load("volume")
    @JvmField val Device = load("device")
    @JvmField val Note = load("note")
    @JvmField val Focus = load("focus")
    @JvmField val FocusOn = load("focusOn")
    @JvmField val Queue = load("queue")
}
