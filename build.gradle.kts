// Задачи корня DPmetaApp — поверх задач подсистемы dpai/ (D-28): включённая сборка не
// выполняет `build` по имени во всех своих проектах, поэтому корень зовёт её общие
// задачи (`dpai/build.gradle.kts`).
plugins {
    base
}

val dpai = gradle.includedBuild("dpai")

tasks.named("build") { dependsOn(dpai.task(":buildAll")) }
tasks.named("check") { dependsOn(dpai.task(":checkAll")) }
tasks.named("clean") { dependsOn(dpai.task(":cleanAll")) }
