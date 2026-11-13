package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CppDependenciesTest {
    @Test fun phoneClangErrorAndGccErrorFindDevelopmentPackage() {
        for (output in listOf("file.cpp:14:10: fatal error:\n      'ncurses.h' file not found", "file.cpp:14:10: fatal error: ncurses.h: No such file or directory")) {
            val report = CppDependencies.analyze(output, 1)
            assertEquals(listOf("ncurses-dev"), report.packages)
            assertEquals(listOf("-lncursesw"), report.flags)
        }
    }
    @Test fun multipleLibrariesAndDuplicateHeadersBecomeOneBatch() {
        val report = CppDependencies.analyze("error: 'openssl/ssl.h' file not found\nerror: 'openssl/crypto.h' file not found\nerror: 'zlib.h' file not found", 1)
        assertEquals(listOf("openssl-dev", "zlib-dev"), report.packages)
        assertEquals(listOf("-lssl", "-lcrypto", "-lz"), report.flags)
    }
    @Test fun unknownHeaderIsExplainedAndNeverBecomesPackageOrCommand() {
        val report = CppDependencies.analyze("fatal error: '\$(touch injected).h' file not found", 1)
        assertTrue(report.packages.isEmpty())
        assertEquals(listOf("\$(touch injected).h"), report.unknownHeaders)
        assertFalse(CppDependencies.singleFileCommand(report).contains("touch"))
    }
    @Test fun linkerAndCursesSymbolsSuggestFlagsEvenWhenHeadersAreAlreadyInstalled() {
        for (text in listOf("ld: cannot find -lncurses", "ld.lld: error: unable to find library -lncursesw", "ld: undefined reference to `initscr'", "ld.lld: error: undefined symbol: stdscr")) {
            assertEquals(listOf("-lncursesw"), CppDependencies.analyze(text, 1).flags, text)
        }
    }
    @Test fun coloredOutputAndBrokenOscAreBoundedAndSuccessfulProgramsDoNotPrompt() {
        val output = "\u001b[31mfatal error: 'ncurses.h' file not found\u001b[0m"
        assertEquals(listOf("ncurses-dev"), CppDependencies.analyze(output, 1).packages)
        assertFalse(CppDependencies.analyze(output, 0).useful)
        assertTrue(CppDependencies.analyze("\u001b]" + "x".repeat(300_000), 1).packages.isEmpty())
    }
    @Test fun missingToolIsMappedWithoutAcceptingArbitraryPackageFromOutput() {
        assertEquals(CppDependencies.TOOLCHAIN_PACKAGES, CppDependencies.analyze("/bin/sh: clang++: not found", 127).packages)
        assertTrue(CppDependencies.analyze("/bin/sh: malicious-package: not found", 127).packages.isEmpty())
    }
    @Test fun sourceFlagsArePlacedAfterTheFileAndOutputHasUniqueTemporaryName() {
        val command = CppDependencies.singleFileCommand(CppDependencies.analyze("error: 'ncurses.h' file not found", 1))
        assertTrue(command.contains("mktemp /tmp/dpai-cpp.XXXXXX"))
        assertTrue(command.indexOf("\"\$DPAI_FILE\"") < command.indexOf("-lncursesw"))
        assertTrue(command.contains("trap 'rm -f \"\$DPAI_BIN\"' EXIT"))
    }
    @Test fun repeatedMissingLibraryDoesNotDropEarlierKnownLinkFlags() {
        val report = CppDependencies.analyze("error: 'zlib.h' file not found", 1)
        val command = CppDependencies.singleFileCommand(report, "clang++ file.cpp -lncurses && ./program")
        assertTrue(command.contains("-lncursesw -lz"), command)
        assertFalse(command.contains("&& ./program"))
    }
    @Test fun reportedToolchainFailuresHaveOneCompletePackageSetAndNoUnsafeCompilerWorkaround() {
        for (text in listOf("clang++: error: unable to execute command: posix_spawn failed: No such file or directory", "ld.lld: error: cannot open crtbeginS.o: No such file or directory", "ld.lld: error: unable to find library -lgcc_s", "ld.lld: error: cannot open libclang_rt.builtins.a", "DPAI_CXX_TOOLCHAIN: ld.lld not found")) {
            val report = CppDependencies.analyze(text, 1)
            assertEquals(CppDependencies.TOOLCHAIN_PACKAGES, report.packages, text)
            assertTrue(CppDependencies.Issue.TOOLCHAIN in report.issues, text)
            val command = CppDependencies.singleFileCommand(report)
            assertFalse(command.contains("-nostartfiles"))
            assertFalse(command.contains("-nodefaultlibs"))
            assertFalse(command.contains("-fno-stack-protector"))
        }
    }
    @Test fun writeExecuteDiskSyntaxAndProgramFailuresHaveDifferentAdvice() {
        for ((text, issue) in listOf("ERROR: failed to extract usr/bin/ld: Permission denied" to CppDependencies.Issue.PACKAGE_WRITE,
            "/root/storage/shared/app: Permission denied" to CppDependencies.Issue.EXECUTION_PERMISSION,
            "No space left on device" to CppDependencies.Issue.DISK_FULL,
            "main.cpp:2: error: expected ';'" to CppDependencies.Issue.COMPILER_ERROR,
            "undefined reference to `my_project_function'" to CppDependencies.Issue.LINK_FLAGS,
            "invalid input" to CppDependencies.Issue.PROGRAM_EXIT)) {
            val report = CppDependencies.analyze(text, 1)
            assertTrue(issue in report.issues, text)
            assertTrue(report.advice.isNotEmpty())
        }
    }
    @Test fun failedLibraryInstallationKeepsOriginalFlagsAndAddsWriteRepairAdvice() {
        val report = CppDependencies.analyze("fatal error: 'ncurses.h' file not found", 1)
            .withFailure("ERROR: failed to extract usr/bin/ld: Permission denied")
        assertEquals(listOf("ncurses-dev"), report.packages)
        assertEquals(listOf("-lncursesw"), report.flags)
        assertTrue(CppDependencies.Issue.PACKAGE_WRITE in report.issues)
    }
}
