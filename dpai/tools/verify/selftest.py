#!/usr/bin/env python3
"""Самотесты проверок: каждая ловит внесённое нарушение и пропускает чистый образец.

Правило docs/PROCESS.md, «Правила»: новая проверка доказывает себя. Для
каждой проверки — пара деревьев во временном каталоге: «плохое» (проверка
обязана упасть с кодом 1) и «чистое» (обязана пройти с кодом 0). Проверка,
которая не ловит своё нарушение, хуже отсутствия проверки: она даёт
ложную уверенность.

Образцы секретов собираются из частей: готовыми их в репозитории нет.

    python3 tools/verify/selftest.py

Код возврата: 0 — все проверки доказали себя; 1 — нет.
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

DECISIONS = "### D-01 — Решение\n\n**Проверка.** тест.\n"
ROADMAP = ("| Этап | Суть | Состояние |\n|---|---|---|\n| 0 | Начало | в работе |\n\n"
           "| Этап | Файл |\n|---|---|\n| 0 | `00-start.md` |\n")
STAGE = "# Этап 0\n\nСостояние: **в работе**.\n\n- [x] сделано\n- [ ] осталось\n"
BASE = {
    "docs/direction/DECISIONS.md": DECISIONS,
    "docs/direction/ROADMAP/README.md": ROADMAP,
    "docs/direction/ROADMAP/00-start.md": STAGE,
    "docs/SECURITY_MODEL.md": "### T1 — Угроза\n",
    "docs/direction/MASTER_TECHNICAL_TASK.md": "1. Введение\n\n5.1. Подраздел\n",
}

ARCH = """# A

<!-- check_modules: begin -->
| Модуль | Отвечает за | Может зависеть от | Этап |
|---|---|---|---|
| `core` | ядро | — | 1 |
| `app` | приложение | `core` | 1 |
<!-- check_modules: end -->
"""
SETTINGS = 'include(":core", ":app")\n'


def secret_sample():
    return "AKIA" + "ABCDEFGHIJKLMNOP"


# gradle_log.py: каждый «плохой» журнал — временный отказ (429) и ровно одна
# причина не повторять (или нет временной причины вовсе); каждый «чистый» —
# обёртки Gradle и ровно одна временная причина. Так каждое правило разбора
# доказывает себя отдельно: убрать любое — какой-то случай покраснеет.
GRADLE_WRAP = ("> Task :core:compileKotlin FAILED\n* What went wrong:\n"
               "> Could not resolve all files for configuration ':core:compileClasspath'.\n"
               "   > Could not GET 'https://repo.maven.apache.org/maven2/org/x/a/1.0/a-1.0.pom'.\n")
GRADLE_429 = "      > Received status code 429 from server: Too Many Requests\n"
GRADLE_NO_RETRY = [GRADLE_WRAP + GRADLE_429 + line for line in [
    "e: file:///core/A.kt:1:5 Unresolved reference 'x'.\n",
    "> Compilation error. See log for more details\n",
    "> Compilation failed; see the compiler output below.\n",
    "> There were failing tests. See the report at: file:///cli/build/index.html\n",
    "CliTest > version() FAILED\n    org.opentest4j.AssertionFailedError\n",
    "> Test process encountered an unexpected problem.\n",
    "    org.gradle.process.ProcessExecutionException: Process 'Gradle Test Executor 2' finished with non-zero exit value 137\n",
    "> There are test sources present and no filters are applied, but the test task did not discover any tests to execute.\n",
    "A problem occurred evaluating project ':tasks'.\n",
    "   > Could not find org.typo:junit-bomm:6.1.3.\n",
    "      > Received status code 403 from server: Forbidden\n",
    "      > PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException\n",
    "      > unable to find valid certification path to requested target\n",
    "   > No matching variant of org.x:a:1.0 was found.\n",
]] + [GRADLE_WRAP + "      > Gradle build daemon disappeared unexpectedly\n"]
GRADLE_RETRY = [GRADLE_WRAP + line for line in [
    GRADLE_429,
    "      > Received status code 502 from server: Bad Gateway\n",
    "      > Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 429 Too Many Requests\"\n",
    "      > Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 502 Bad Gateway\"\n",
    "java.io.IOException: Server returned HTTP response code: 503 for URL: https://services.gradle.org/g.zip\n",
    "java.io.IOException: Server returned HTTP response code: 429 for URL: https://services.gradle.org/g.zip\n",
    "      > Connection reset\n",
    "      > Read timed out\n",
    "      > Connect to 127.0.0.1:35523 [/127.0.0.1] failed: Connect timed out\n",
    "      > java.net.SocketTimeoutException: timeout\n",
]]

CASES = [
    # (проверка, плохое дерево, чистое дерево, аргументы)
    ("check_format.py",
     {"a.md": "строка \n"},
     {"a.md": "строка\n"}, []),
    ("check_format.py",
     {"a.kt": "val x = 1" + chr(0x202E) + "\n"},
     {"a.kt": "val x = 1\n"}, []),
    ("check_docs.py",
     {**BASE, "a.md": "Решение D-02 и ТЗ, §2.\n"},
     {**BASE, "a.md": "Решение D-01 и ТЗ, §5.1, этап 0.\n"}, []),
    # Трёхзначные номера (D-100 и дальше) сверяются так же.
    ("check_docs.py",
     {**BASE, "docs/direction/DECISIONS.md": DECISIONS + "### D-101 — Решение\n\n**Проверка.** тест.\n", "a.md": "Решение D-106.\n"},
     {**BASE, "docs/direction/DECISIONS.md": DECISIONS + "### D-101 — Решение\n\n**Проверка.** тест.\n", "a.md": "Решение D-101.\n"}, []),
    ("check_docs.py",
     {**BASE, "tools/audit/final_stability.py": 'MAP = [("A", "x", ["GoneTest.ok"], None)]\n'},
     {**BASE, "tools/audit/final_stability.py": 'MAP = [("A", "x", ["FooTest.ok"], None)]\n',
      "m/src/test/kotlin/FooTest.kt": "class FooTest { fun ok() {} }\n"}, []),
    ("check_docs.py",
     {**BASE, "a.md": "Тест `FooTest` и `docs/NOPE.md`.\n"},
     {**BASE, "a.md": "Документ `docs/SECURITY_MODEL.md`, угроза T1 (SECURITY_MODEL).\n",
      "m/src/test/kotlin/FooTest.kt": "class FooTest { fun ok() {} }\n"}, []),
    ("check_modules.py",
     {"docs/ARCHITECTURE.md": ARCH, "settings.gradle.kts": SETTINGS,
      "core/build.gradle.kts": 'dependencies {\n    api(project(":app"))\n}\n',
      "app/build.gradle.kts": 'dependencies {\n    api(project(":core"))\n}\n'},
     {"docs/ARCHITECTURE.md": ARCH, "settings.gradle.kts": SETTINGS,
      "core/build.gradle.kts": "plugins {}\n",
      "app/build.gradle.kts": 'dependencies {\n    api(project(":core"))\n}\n'}, []),
    ("check_modules.py",
     {"docs/ARCHITECTURE.md": ARCH, "settings.gradle.kts": SETTINGS,
      "core/build.gradle.kts": "plugins {}\n",
      "app/build.gradle.kts": 'dependencies {\n    api(project(":core"))\n}\n',
      "core/src/main/kotlin/A.kt": "package com.dpsmpx.dpai.core\nimport android.os.Bundle\n"},
     {"docs/ARCHITECTURE.md": ARCH, "settings.gradle.kts": SETTINGS,
      "core/build.gradle.kts": "plugins {}\n",
      "app/build.gradle.kts": 'dependencies {\n    api(project(":core"))\n}\n',
      "core/src/main/kotlin/A.kt": "package com.dpsmpx.dpai.core.x\n"}, []),
    ("check_modules.py",
     {"docs/ARCHITECTURE.md": ARCH, "settings.gradle.kts": SETTINGS,
      "core/build.gradle.kts": "dependencies {\n    implementation(libs.some.lib)\n}\n",
      "app/build.gradle.kts": 'dependencies {\n    api(project(":core"))\n}\n'},
     {"docs/ARCHITECTURE.md": ARCH, "settings.gradle.kts": SETTINGS,
      "core/build.gradle.kts": "dependencies {\n    testImplementation(libs.some.lib)\n}\n",
      "app/build.gradle.kts": 'dependencies {\n    api(project(":core"))\n}\n'}, []),
    ("check_forbidden_apis.py",
     {"core/src/main/kotlin/com/dpsmpx/dpai/core/A.kt": "val t = System.currentTimeMillis()\n"},
     {"core/src/main/kotlin/com/dpsmpx/dpai/core/A.kt": "// System.currentTimeMillis() — нельзя\nval s = \"Thread.sleep(1)\"\n",
      "core/src/main/kotlin/com/dpsmpx/dpai/core/clock/C.kt": "val t = System.currentTimeMillis()\n"}, []),
    ("check_forbidden_apis.py",
     {"tasks/src/main/kotlin/A.kt": "val p = ProcessBuilder(\"ls\")\n"},
     {"execution/src/main/kotlin/A.kt": "val p = ProcessBuilder(\"ls\")\n"}, []),
    ("check_forbidden_apis.py",
     {"tasks/src/main/kotlin/A.kt": "fun f(p: java.nio.file.Path) = java.nio.file.Files.writeString(p, \"x\")\n"},
     {"storage/src/main/kotlin/A.kt": "fun f(p: java.nio.file.Path) = java.nio.file.Files.writeString(p, \"x\")\n",
      "tasks/src/test/kotlin/T.kt": "fun f(p: java.io.File) = p.writeText(\"x\")\n"}, []),
    ("check_forbidden_apis.py",
     {"storage/src/main/kotlin/A.kt": "class A(val key: Secret)\n"},
     {"execution/src/main/kotlin/A.kt": "class A(val key: Secret)\n"}, []),
    ("check_forbidden_apis.py",
     {"execution/src/main/kotlin/A.kt": "val p = \"~/.claude/\" + \".credentials.json\".substring(0) + \"/.credentials.json\"\n"},
     {"execution/src/main/kotlin/A.kt": "val env = mapOf(\"CLAUDE_CONFIG_DIR\" to dir)\n"}, []),
    ("check_forbidden_apis.py",
     {"monitoring/src/main/kotlin/A.kt": "fun f(v: Double) = \"%.1f %%\".format(v)\n"},
     {"monitoring/src/main/kotlin/A.kt": "fun f(v: Double) = String.format(java.util.Locale.ROOT, \"%.1f %%\", v)\n"
      "fun h(v: Double) = String.format(java.util.Locale.ROOT, \"%.2f\", v)\n"}, []),
    ("check_secrets.py",
     {"a.txt": "key = " + secret_sample() + "\n"},
     {"a.txt": "key = <из окружения>\n"}, []),
    ("check_secrets.py",
     {"id_rsa": "x\n"},
     {"id_rsa.pub": "ssh-ed25519 открытый ключ\n"}, []),
    ("check_contracts.py",
     {"core/src/main/kotlin/com/dpsmpx/dpai/core/diagnostics/DiagCode.kt":
      "enum class DiagCode(val x: Int) {\n    A(1),\n    B(2),\n    ;\n}\n",
      "tools/verify/registry/diag_codes.txt": "A\nC\n"},
     {"core/src/main/kotlin/com/dpsmpx/dpai/core/diagnostics/DiagCode.kt":
      "enum class DiagCode(val x: Int) {\n    A(1),\n    B(2),\n    ;\n}\n",
      "tools/verify/registry/diag_codes.txt": "# реестр\nA\nB\n"}, []),
    ("check_dependencies.py",
     {"gradle/libs.versions.toml": '[versions]\nkotlin = "2"\n',
      "docs/PROVENANCE.md": "<!-- provenance: begin -->\n| `kotlin` | 1 |\n<!-- provenance: end -->\n"},
     {"gradle/libs.versions.toml": '[versions]\nkotlin = "1"\n',
      "docs/PROVENANCE.md": "<!-- provenance: begin -->\n| `kotlin` | 1 |\n<!-- provenance: end -->\n"}, []),
    ("check_dependencies.py",
     {"gradle/libs.versions.toml": "[versions]\n", "docs/PROVENANCE.md": "<!-- provenance: begin -->\n<!-- provenance: end -->\n",
      "a/build.gradle.kts": 'dependencies { implementation("org.x:y:1.0") }\n',
      ".github/workflows/ci.yml": "on: push\n"},
     {"gradle/libs.versions.toml": "[versions]\n", "docs/PROVENANCE.md": "<!-- provenance: begin -->\n<!-- provenance: end -->\n",
      "a/build.gradle.kts": "dependencies { implementation(libs.x) }\n"}, []),
    ("check_dependencies.py",
     # Двоичный файл без строки с суммой — находка; заголовок C — текст, как исходник (D-115).
     {"gradle/libs.versions.toml": "[versions]\n", "docs/PROVENANCE.md": "<!-- provenance: begin -->\n<!-- provenance: end -->\n",
      "tools/linux/libx.so": "\x7fELF"},
     {"gradle/libs.versions.toml": "[versions]\n", "docs/PROVENANCE.md": "<!-- provenance: begin -->\n<!-- provenance: end -->\n",
      "tools/linux/compat.h": "#define A 1\n"}, []),
    ("check_secure_screens.py",
     # Экран из реестра без флага — дыра: содержимое попадает в превью и снимки (D-86).
     {"android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt":
      "class TerminalActivity : ComponentActivity() {\n    override fun onCreate(b: Bundle?) {\n        super.onCreate(b)\n    }\n}\n",
      "tools/verify/registry/secure_screens.txt":
      "# реестр\nandroid/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt:TerminalActivity\n"},
     {"android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt":
      "class TerminalActivity : ComponentActivity() {\n    override fun onCreate(b: Bundle?) {\n"
      "        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)\n    }\n}\n",
      "tools/verify/registry/secure_screens.txt":
      "# реестр\nandroid/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt:TerminalActivity\n"}, []),
    ("check_secure_screens.py",
     # У диалога своё окно: флаг активности его не закрывает (D-86).
     {"android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt":
      "class TerminalActivity : ComponentActivity() {\n    override fun onCreate(b: Bundle?) {\n"
      "        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)\n    }\n"
      "    fun ask() {\n        AlertDialog.Builder(this).setTitle(\"x\").show()\n    }\n}\n",
      "tools/verify/registry/secure_screens.txt":
      "android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt:TerminalActivity\n"},
     {"android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt":
      "class TerminalActivity : ComponentActivity() {\n    override fun onCreate(b: Bundle?) {\n"
      "        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)\n    }\n"
      "    fun ask() {\n"
      "        val d = AlertDialog.Builder(this).setTitle(\"x\").create()\n"
      "        d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)\n"
      "        d.show()\n    }\n}\n",
      "tools/verify/registry/secure_screens.txt":
      "android/src/main/kotlin/com/dpsmpx/dpai/android/TerminalActivity.kt:TerminalActivity\n"}, []),
    ("check_secure_screens.py",
     # Диалог Compose с кодом сопряжения (D-86): без защищённого окна — находка.
     {"android/src/main/kotlin/com/dpsmpx/dpai/android/TargetsPane.kt":
      "@Composable\nfun Code(c: String) {\n    AlertDialog(onDismissRequest = {}, text = { Text(c) }, confirmButton = {})\n}\n",
      "tools/verify/registry/secure_screens.txt":
      "android/src/main/kotlin/com/dpsmpx/dpai/android/TargetsPane.kt:PairingCode\n"},
     {"android/src/main/kotlin/com/dpsmpx/dpai/android/TargetsPane.kt":
      "@Composable\nfun Code(c: String) {\n    AlertDialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),\n"
      "        text = { Text(c) }, confirmButton = {})\n}\n",
      "tools/verify/registry/secure_screens.txt":
      "android/src/main/kotlin/com/dpsmpx/dpai/android/TargetsPane.kt:PairingCode\n"}, []),
    ("readme_progress.py",
     {**BASE, "TODO.md": "## Раздел\n\n- [ ] пункт\n",
      "README.md": "<!-- progress:begin -->\nстарое\n<!-- progress:end -->\n"},
     None, ["--check"]),
]

# check_android_api.py --jars: сверяется jar текущей версии; прежний jar в
# build/libs (осталась сборка до смены версии) — не замена текущему.
ANDROID_ARCH = ("<!-- check_modules: begin -->\n| Модуль | Отвечает за | Может зависеть от | Этап |\n|---|---|---|---|\n"
                "| `core` | ядро | — | 1 |\n| `android` | оболочка | `core` | 11 |\n<!-- check_modules: end -->\n")
CASES += [("check_android_api.py",
           {"docs/ARCHITECTURE.md": ANDROID_ARCH, "gradle.properties": "version=0.2.0\n",
            "core/build/libs/core-0.1.0.jar": "старый\n"},
           {"docs/ARCHITECTURE.md": ANDROID_ARCH, "gradle.properties": "version=0.2.0\n",
            "core/build/libs/core-0.1.0.jar": "старый\n", "core/build/libs/core-0.2.0.jar": "текущий\n"},
           ["--jars"])]

# Плохих образцов больше, чем чистых: чистые идут по кругу, каждый встречается.
CASES += [("gradle_log.py", {"build.log": bad}, {"build.log": GRADLE_RETRY[i % len(GRADLE_RETRY)]}, ["build.log"])
          for i, bad in enumerate(GRADLE_NO_RETRY)]


def write_tree(base, tree):
    for rel, text in tree.items():
        path = os.path.join(base, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
    subprocess.run(["git", "init", "-q"], cwd=base, capture_output=True)


def run(script, tree, args):
    with tempfile.TemporaryDirectory(prefix="dpai-selftest-") as tmp:
        write_tree(tmp, tree)
        env = dict(os.environ, DPAI_VERIFY_ROOT=tmp)
        r = subprocess.run([sys.executable, "-B", os.path.join(HERE, script), *args],
                           env=env, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


def readme_clean_tree():
    """Чистое дерево для README: тот же вход, раздел — как его строит сам скрипт."""
    tree = {**BASE, "TODO.md": "## Раздел\n\n- [ ] пункт\n",
            "README.md": "<!-- progress:begin -->\n<!-- progress:end -->\n"}
    with tempfile.TemporaryDirectory(prefix="dpai-selftest-") as tmp:
        write_tree(tmp, tree)
        env = dict(os.environ, DPAI_VERIFY_ROOT=tmp)
        subprocess.run([sys.executable, "-B", os.path.join(HERE, "readme_progress.py")],
                       env=env, capture_output=True)
        with open(os.path.join(tmp, "README.md"), encoding="utf-8") as fh:
            tree["README.md"] = fh.read()
    return tree


def main():
    failures = 0
    for script, bad, good, args in CASES:
        if good is None:
            good = readme_clean_tree()
        code, out = run(script, bad, args)
        if code != 1:
            failures += 1
            print(f"✗ {script}: не поймала нарушение (код {code})\n{out}")
        code, out = run(script, good, args)
        if code != 0:
            failures += 1
            print(f"✗ {script}: упала на чистом образце (код {code})\n{out}")
    if failures:
        print(f"самотесты проверок: провалов — {failures}")
        return 1
    print(f"✓ самотесты проверок: случаев — {len(CASES)}, каждый пойман и чистый пропущен")
    return 0


if __name__ == "__main__":
    sys.exit(main())
