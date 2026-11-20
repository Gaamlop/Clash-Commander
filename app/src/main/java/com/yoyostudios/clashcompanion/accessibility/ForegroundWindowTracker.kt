package com.yoyostudios.clashcompanion.accessibility

/** Tracks window metadata only; does not require reading accessibility window content. */
class ForegroundWindowTracker {
    @Volatile private var packageName: String? = null
    @Volatile private var modal = false
    val gameInForeground: Boolean? get() = packageName?.let { it == "com.supercell.clashroyale" && !modal }
    fun observe(pkg: String?, className: String?, fullscreen: Boolean) {
        if (pkg.isNullOrBlank()) return
        val dialog = className?.let { it.contains("Dialog") || it.contains("PopupWindow") } == true
        if (dialog || fullscreen || className?.endsWith("Activity") == true || pkg == "com.supercell.clashroyale") {
            packageName = pkg
            modal = dialog
        }
    }
}
