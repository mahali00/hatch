package dev.mahali.hatch.core

public enum class KeyBarKey(public val label: String) {
    Escape("Escape"), Control("Control"), Option("Option"), Command("Command"), Shift("Shift"), Tab("Tab")
}

public data class ShortcutGroup(val id: String, val name: String, val shortcuts: List<String>)
