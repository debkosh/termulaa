package com.debkosh.termulaa.push

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakableTest {
    @Test fun markupAndLinksAreNotReadOut() {
        assertEquals(
            "release: merge 0.9 now? see a link",
            Speaker.speakable("release: **merge** `0.9` now? see https://github.com/x/y/pull/1"),
        )
    }

    @Test fun plainTextIsUnchanged() {
        assertEquals("ready to merge 0.9, needs your yes", Speaker.speakable("ready to merge 0.9, needs your yes"))
    }
}
