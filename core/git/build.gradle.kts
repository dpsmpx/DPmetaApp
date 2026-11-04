// Локальные клоны репозиториев (D-65): клонирование и обновление, ветки и
// файлы из клона, расхождение с GitHub — конфликтом. Git — JGit (каталог
// версий, docs/PROVENANCE.md); сеть — только через пропуск списка :core:net
// (D-10). Чистый Kotlin/JVM, Android-типов нет (D-03).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:net"))
    implementation(libs.jgit)
}
