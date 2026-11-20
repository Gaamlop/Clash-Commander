package com.yoyostudios.clashcompanion.local

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BoundedTraceTest {
    @Test fun rotatesWithoutGrowingUnboundedOrBreakingRecords() {
        val directory = Files.createTempDirectory("hypno-trace").toFile()
        try {
            val file = directory.resolve("local.jsonl")
            val log = BoundedTrace(file, 128)
            repeat(100) { assertTrue(log.append("{\"frame\":$it,\"state\":\"observed\"}")) }
            assertTrue(directory.listFiles()!!.all { it.length() <= 128 })
            val lines = log.snapshot().trim().lines()
            assertTrue(lines.last().contains("99"))
            assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsMultilineAndOversizedPayloadsWithoutCorruptingTheTrace() {
        val directory = Files.createTempDirectory("hypno-trace").toFile()
        try {
            val log = BoundedTrace(directory.resolve("local.jsonl"), 128)
            assertFalse(log.append("x".repeat(129)))
            assertFalse(log.append("one\ntwo"))
            assertTrue(log.append("{}"))
            assertEquals("{}\n", log.snapshot())
        } finally { directory.deleteRecursively() }
    }
}
