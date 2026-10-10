// SSH-клиент на JVM для targets там, где нет системного OpenSSH —
// приложение Android (D-35). Ядро от него не зависит: модуль подключает
// только оболочка android.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":execution"))
    // api: EcdsaSignerIdentity — это com.jcraft.jsch.Identity.
    api(libs.jsch)
    testImplementation(project(":testkit"))
}
