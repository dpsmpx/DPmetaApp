"""Явные профили IDE: разрешение версий остаётся менеджерам проекта."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path.cwd().resolve()
MAX = 8 * 1024 * 1024


def target(relative):
    path = ROOT / relative
    if not path.resolve().is_relative_to(ROOT) or any(p.is_symlink() for p in [path, *path.parents] if p != ROOT.parent):
        raise ValueError("путь инструмента выходит из проекта или является ссылкой")
    return path


def write(relative, content):
    path = target(relative)
    data = content.encode("utf-8")
    if len(data) > MAX:
        raise ValueError("отчёт больше 8 МиБ")
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".dp-ide-tmp")
    if temp.exists():
        raise ValueError("временный файл уже существует; проверьте прерванную операцию")
    try:
        with temp.open("xb") as stream:
            stream.write(data)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def run(argv, capture=False):
    if not capture:
        result = subprocess.run(argv, check=False)
        if result.returncode:
            raise ValueError("менеджер зависимостей завершился с кодом " + str(result.returncode))
        return
    with subprocess.Popen(argv, stdout=subprocess.PIPE) as child:
        chunks = []
        size = 0
        while True:
            block = child.stdout.read(8192)
            if not block:
                break
            size += len(block)
            if size > MAX:
                child.kill()
                child.wait()
                raise ValueError("ответ менеджера больше 8 МиБ")
            chunks.append(block)
        if child.wait():
            raise ValueError("менеджер зависимостей завершился с кодом " + str(child.returncode))
    return b"".join(chunks).decode("utf-8")


LOCKING = """// Создано DP_IDE: закрепление выполняет Gradle, без отдельного resolver.
allprojects {
    configurations.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
}
"""

GRADLE = """import groovy.json.JsonOutput
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

gradle.projectsEvaluated {
    rootProject.tasks.register('dpIdeDependencyReport') {
        doLast {
            def rows = []
            def failures = 0
            def edges = 0
            rootProject.allprojects.sort { it.path }.each { p ->
                p.configurations.findAll { it.canBeResolved }.sort { it.name }.each { c ->
                    if (rows.size() >= 512) throw new GradleException('Dependency report exceeds 512 configurations')
                    def modules = []
                    def missing = []
                    c.incoming.resolutionResult.allDependencies.each { d ->
                        if (++edges > 100000) throw new GradleException('Dependency report exceeds 100000 edges')
                        if (d instanceof ResolvedDependencyResult) {
                            modules.add([requested:d.requested.displayName, selected:d.selected.id.displayName,
                                reason:d.selected.selectionReason.descriptions.collect { it.description }])
                        } else if (d instanceof UnresolvedDependencyResult) {
                            missing.add(d.requested.displayName)
                        }
                    }
                    failures += missing.size()
                    rows.add([project:p.path, configuration:c.name, dependencies:modules, unresolved:missing])
                    println(p.path + ' / ' + c.name + ': ' + modules.size() + ' dependencies, ' + missing.size() + ' unresolved')
                }
            }
            def destination = rootProject.file('.dp-ide/dependencies/gradle-report.json')
            def json = JsonOutput.prettyPrint(JsonOutput.toJson([format:'dp-ide.gradle-dependencies', version:1, configurations:rows]))
            if (json.getBytes('UTF-8').length > 8 * 1024 * 1024) throw new GradleException('Dependency report exceeds 8 MiB')
            destination.setText(json, 'UTF-8')
            if (failures) throw new GradleException('Unresolved dependencies: ' + failures + '; see .dp-ide/dependencies/gradle-report.json')
        }
    }
}
"""


def gradle(mode):
    wrapper = target("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise ValueError("нет Gradle Wrapper: добавьте проверенный wrapper проекта")
    script = ".dp-ide/dependencies/report.init.gradle"
    write(script, LOCKING + GRADLE)
    target(".dp-ide/dependencies/gradle-report.json")
    command = (["cmd.exe", "/c", str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]) + [ "--no-daemon", "--no-parallel", "--max-workers=1", "--no-configuration-cache", "--init-script", script, "dpIdeDependencyReport"]
    if mode == "pin":
        command.append("--write-locks")
    try:
        run(command)
    except ValueError:
        if mode == "pin":
            print("Закрепление не завершено: проверьте Git diff, Gradle мог записать часть lock-файлов.", file=sys.stderr)
        raise
    if mode == "pin":
        # Подключение сохраняется только после успешного разрешения всех конфигураций.
        locking = "./gradle/dp-ide-locking.gradle"
        existing = target(locking)
        if existing.exists() and existing.read_text() != LOCKING:
            raise ValueError("./gradle/dp-ide-locking.gradle изменён вручную: подключение не перезаписано")
        build = "build.gradle.kts" if target("build.gradle.kts").is_file() else "build.gradle"
        file = target(build)
        before = file.read_text() if file.exists() else ""
        if len(before.encode("utf-8")) > MAX:
            raise ValueError("сценарий Gradle больше 8 МиБ")
        line = 'apply(from = rootProject.file("./gradle/dp-ide-locking.gradle"))' if build.endswith(".kts") else 'apply from: rootProject.file("./gradle/dp-ide-locking.gradle")'
        write(locking, LOCKING)
        block = "// DP_IDE-BEGIN dependency locking\n" + line + "\n// DP_IDE-END dependency locking\n"
        if block not in before:
            write(build, before.rstrip() + "\n\n" + block)
        print("Версии закреплены Gradle; lock-файлы и подключение сохраните вместе в Git. Плагины и included builds проверяются отдельно.")
    print("Отчёт: .dp-ide/dependencies/gradle-report.json")


def python(mode):
    if mode == "restore":
        if not target("requirements.dp-ide.lock").is_file():
            raise ValueError("нет requirements.dp-ide.lock")
        run([sys.executable, "-m", "pip", "install", "--require-virtualenv", "--requirement", "requirements.dp-ide.lock"])
    packages = json.loads(run([sys.executable, "-m", "pip", "inspect", "--local"], True))
    run([sys.executable, "-m", "pip", "check"])
    selected = []
    direct = []
    for item in packages["installed"]:
        metadata = item["metadata"]
        name, version = metadata["name"], metadata["version"]
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.!+-]*", version):
            raise ValueError("pip вернул неподдерживаемые имя или версию пакета")
        selected.append({"name": name, "version": version, "requested": bool(item.get("requested")), "requires": metadata.get("requires_dist", [])})
        if item.get("direct_url"):
            direct.append(name)
    selected.sort(key=lambda x: x["name"].lower())
    write(".dp-ide/dependencies/python-report.json", json.dumps({"format":"dp-ide.python-dependencies", "version":1, "environment":packages["environment"], "packages":selected, "direct_packages":direct}, ensure_ascii=False, indent=2) + "\n")
    for item in selected:
        print(item["name"] + "==" + item["version"])
    if mode == "pin":
        if sys.prefix == sys.base_prefix:
            raise ValueError("для закрепления выберите виртуальное окружение проекта, а не общий Python")
        if direct:
            raise ValueError("есть локальные/VCS/editable пакеты; закрепите их отдельно: " + ", ".join(direct))
        write("requirements.dp-ide.lock", "# DP_IDE: снимок всего выбранного venv; Python/платформа — в python-report.json.\n" + "\n".join(x["name"] + "==" + x["version"] for x in selected) + "\n")
        print("Снимок версий: requirements.dp-ide.lock. Исходные requirements/pyproject не изменены; хеши архивов не добавляются.")
    print("Отчёт: .dp-ide/dependencies/python-report.json")


def conan(mode):
    if not any(target(p).is_file() for p in ("conanfile.py", "conanfile.txt")):
        raise ValueError("нет conanfile.py или conanfile.txt; CMake сам не закрепляет сторонние библиотеки")
    if mode == "pin":
        run(["conan", "lock", "create", ".", "--lockfile-out=conan.lock"])
    else:
        report = run(["conan", "graph", "info", ".", "--format=json"], True)
        data = json.loads(report)
        # URL remotes не копируются в отчёт; сохраняется только граф пакетов.
        nodes = data["graph"]["nodes"]
        safe = [{k:v for k,v in node.items() if k in ("ref", "package_id", "prev", "requires", "context", "binary", "dependencies")} for node in nodes.values()]
        write(".dp-ide/dependencies/conan-report.json", json.dumps({"format":"dp-ide.conan-dependencies", "version":1, "nodes":safe}, indent=2) + "\n")
        print("Граф Conan: .dp-ide/dependencies/conan-report.json")


try:
    manager, mode = sys.argv[1:]
    if mode not in ("report", "pin", "restore") or manager not in ("gradle", "python", "conan") or mode == "restore" and manager != "python":
        raise ValueError("неподдерживаемый профиль зависимостей")
    {"gradle":gradle, "python":python, "conan":conan}[manager](mode)
except (ValueError, OSError, KeyError) as error:
    print("Зависимости: " + str(error), file=sys.stderr)
    sys.exit(1)
