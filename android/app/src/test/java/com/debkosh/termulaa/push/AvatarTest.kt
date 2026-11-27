package com.debkosh.termulaa.push

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarTest {
    @Test fun namesSplitIntoProjectAndName() {
        assertEquals("phone notifications", Avatar.name("[memd] phone notifications"))
        assertEquals("memd", Avatar.project("[memd] phone notifications"))
        assertEquals("P", Avatar.initial("[memd] phone notifications"))
        assertEquals("ux: homepage", Avatar.name("❓ [project-b] ux: homepage"))
        assertEquals("project-b", Avatar.project("❓ [project-b] ux: homepage"))
        assertEquals("U", Avatar.initial("❓ [project-b] ux: homepage"))
        assertEquals("laptop", Avatar.name("laptop"))
        assertEquals("", Avatar.project("laptop"))
        assertEquals("L", Avatar.initial("laptop"))
    }

    @Test fun conversationsGroupBySenderNewestFirst() {
        val e = listOf(
            HistoryEntry("3", 30, "info", "b", "b2"),
            HistoryEntry("2", 20, "info", "a", "a1"),
            HistoryEntry("1", 10, "info", "b", "b1"),
        )
        val c = History.conversations(e)
        assertEquals(listOf("b", "a"), c.map { it.sender })
        assertEquals(listOf("b2", "b1"), c[0].entries.map { it.title })
        assertEquals(2, c[0].unread)
    }
}
