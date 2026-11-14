package com.dpsmpx.dpai.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorSessionsTest {
    @Test
    fun switchingAndRecreatingFieldPreservesSessionUntilTabCloses() {
        val book = EditorSessions<Any>()
        val owner = Any()
        book.enter("ide", owner)
        book.retain("ide", owner, setOf("main.cpp", "other.cpp"))
        val first = book.acquire("ide", "/root/project", "main.cpp", ::Any).session
        val second = book.acquire("ide", "/root/project", "other.cpp", ::Any).session
        assertSame(first, book.acquire("ide", "/root/project", "main.cpp", ::Any).session)
        assertEquals(listOf(first), book.retain("ide", owner, setOf("other.cpp")))
        assertEquals(listOf(second), book.close("ide", owner))
        assertFalse(book.keeps("ide", "/root/project", CodeLanguage.CPP))
    }

    @Test
    fun staleOwnerCannotRetireNewInterface() {
        val book = EditorSessions<Any>()
        val old = Any()
        val fresh = Any()
        book.enter("ide", old)
        val session = book.acquire("ide", "/root/project", "main.kt", ::Any).session
        book.enter("ide", fresh)
        assertTrue(book.close("ide", old).isEmpty())
        assertTrue(book.retain("ide", old, emptySet()).isEmpty())
        assertSame(session, book.acquire("ide", "/root/project", "main.kt", ::Any).session)
        assertEquals(listOf(session), book.close("ide", fresh))
    }

    @Test
    fun unvisitedTabsKeepOnlyTheirProjectAndLanguage() {
        val book = EditorSessions<Any>()
        val owner = Any()
        book.enter("ide", owner)
        book.acquire("ide", "/root/projects", "one/main.cpp", ::Any)
        book.retain("ide", owner, setOf("one/include/new.hpp", "two/run.py"))
        assertTrue(book.keeps("ide", "/root/projects/one", CodeLanguage.CPP))
        assertFalse(book.keeps("ide", "/root/projects/on", CodeLanguage.CPP))
        assertFalse(book.keeps("ide", "/root/projects/one", CodeLanguage.PYTHON))
        assertFalse(book.keeps("another", "/root/projects/one", CodeLanguage.CPP))
        assertTrue(book.keeps("ide", "/root/projects/two", CodeLanguage.PYTHON))
        assertTrue(book.keeps("ide", "/", CodeLanguage.CPP))
    }

    @Test
    fun changedGuestRootRetiresOldSessionsAndRootSlashJoinsOnce() {
        val book = EditorSessions<Any>()
        val first = book.acquire("ide", "/old/", "main.py", ::Any).session
        val next = book.acquire("ide", "/", "main.py", ::Any)
        assertEquals(listOf(first), next.retired)
        assertTrue(book.keeps("ide", "/", CodeLanguage.PYTHON))
        assertFalse(book.keeps("ide", "/old", CodeLanguage.PYTHON))
        assertSame(next.session, book.acquire("ide", "/", "main.py", ::Any).session)
    }
}
