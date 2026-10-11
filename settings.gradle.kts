// Сборка DPmetaApp из корня (D-28; ТЗ, §6.1): одна команда — `./gradlew build`.
// Ядро и подсистема «AI» — dpai/: подключены целиком и собираются и сами, из dpai/
// (DP_AI_Orcestrator:D-55); модули DPmetaApp (ТЗ, §6.2) появятся здесь же на этапе 1.
rootProject.name = "DPmetaApp"

includeBuild("dpai")
