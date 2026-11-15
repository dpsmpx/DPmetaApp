package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Команда ключа выпуска (DPmetaApp:D-158): поля → keytool, пароль — через окружение. */
class ReleaseKeyCommandTest {
    private val pass = "parol-" + "x'y" + "z".repeat(8)
    private fun form(password: String = pass, name: String = "dpsmpx", country: String = "RU") =
        ReleaseKeyCommand.Form(name, "", country, "release", "dpmetaapp-release.p12", 30, Secret(password))

    @Test
    fun posixAndPowerShellCommands() {
        val sh = (ReleaseKeyCommand.build(form(), ReleaseKeyCommand.Shell.POSIX) as Outcome.Success).value
        assertTrue(sh.startsWith("DPMETA_KS_PASS='parol-x'\\''yzzzzzzzz' keytool -genkeypair"), sh)
        assertTrue("-storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10957 -alias release -keystore ~/dpmetaapp-release.p12" in sh, sh)
        assertTrue("-dname 'CN=dpsmpx, C=RU' -storepass:env DPMETA_KS_PASS -keypass:env DPMETA_KS_PASS; " in sh, sh)
        // Строка с паролем удаляет себя из истории bash сама (проверка PR #190): «set +o history» в той же строке не помогает.
        assertTrue(sh.endsWith("; history -d -1 2>/dev/null || unset HISTFILE"), sh)
        assertFalse("set +o history" in sh, sh)
        assertFalse(" -storepass $" in sh || "-storepass '" in sh, "пароль не аргументом процесса")
        // PowerShell (PSReadLine) хранит строки в файле истории — пароль не встраивается, его спросит keytool.
        val ps = (ReleaseKeyCommand.build(form(), ReleaseKeyCommand.Shell.POWERSHELL) as Outcome.Success).value
        assertTrue(ps.startsWith("keytool -genkeypair"), ps)
        assertTrue(ps.endsWith("-keystore \"\$HOME\\dpmetaapp-release.p12\" -dname 'CN=dpsmpx, C=RU'"), ps)
        assertFalse("parol" in ps || "storepass" in ps || "DPMETA_KS_PASS" in ps, ps)
    }

    @Test
    fun refusesBadFields() {
        assertNotNull(ReleaseKeyCommand.problem(form(password = "короткий")), "пароль от 12")
        assertNotNull(ReleaseKeyCommand.problem(form(password = "кириллица-длинная")), "только ASCII — файл потом импортируется")
        assertNotNull(ReleaseKeyCommand.problem(form(name = "a,b")), "запятая сломала бы dname")
        assertNotNull(ReleaseKeyCommand.problem(form(country = "ru")), "страна — две заглавные")
        assertEquals(null, ReleaseKeyCommand.problem(form()))
    }
}
