package com.yoyostudios.clashcompanion.local

import java.io.File

/** Two bounded app-private files; no screenshots or credentials are accepted by the recorder. */
class BoundedTrace(private val file: File, private val maxBytes: Int = 1_000_000) {
    private val previous = File(file.parentFile, file.name + ".previous")
    init { require(maxBytes >= 128) }
    @Synchronized fun append(line: String): Boolean {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes || line.contains('\n')) return false
        file.parentFile?.mkdirs()
        if (file.length() + bytes.size > maxBytes) {
            previous.delete()
            check(file.renameTo(previous))
        }
        file.appendBytes(bytes)
        return true
    }
    @Synchronized fun snapshot(): String =
        (if (previous.exists()) previous.readText() else "") + (if (file.exists()) file.readText() else "")
}
