// Общие задачи подсистемы для сборки DPmetaApp из корня (D-55): `./gradlew build` в
// dpai/ по-прежнему собирает всё само, а корень DPmetaApp зовёт `:buildAll` —
// включённая сборка задачи по имени во всех проектах не ищет.
for (name in listOf("build", "check", "clean")) {
    tasks.register("${name}All") {
        group = "build"
        description = "Задача $name во всех модулях подсистемы."
        // Контейнер без своего build.gradle.kts (`:tests`) задач не имеет.
        dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:$name" })
    }
}
