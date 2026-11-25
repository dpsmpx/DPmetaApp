package com.dpsmpx.dpai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleTextTest {
    @Test fun terminalCommandsAndInvisibleDirectionChangesBecomeVisibleText() {
        val unsafe = "\u001b]52;c;payload\u0007\u001b[2J\u202e\n\ttext"
        assertEquals("\\u001b]52;c;payload\\u0007\\u001b[2J\\u202e\n\ttext", ConsoleText.visible(unsafe))
    }
}
