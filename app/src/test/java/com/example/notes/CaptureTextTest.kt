package com.example.notes

import org.junit.Assert.*
import org.junit.Test

class CaptureTextTest {
    @Test fun truncationDoesNotSplitSurrogatePair() {
        val result = ("x".repeat(9_999) + "\uD83D\uDE00").takeCapture()
        assertEquals("x".repeat(9_999), result)
        assertFalse(result.last().isHighSurrogate())
    }

    @Test fun truncationKeepsCompletePairsAndOrdinaryBoundaries() {
        val complete = "x".repeat(9_998) + "\uD83D\uDE00"
        assertEquals(complete, (complete + "y").takeCapture())
        assertEquals("x".repeat(10_000), "x".repeat(10_001).takeCapture())
        assertEquals("short", "short".takeCapture())
        assertEquals("", "short".takeCapture(0))
    }
}
