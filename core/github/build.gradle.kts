// GitHub для DPmetaApp (ТЗ, §6.2, I-09; D-17): REST поверх :core:net — PR, ветки,
// коммиты, содержимое файлов; вход — device flow GitHub App или PAT.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core:net"))
    api(project(":core:model"))
}
