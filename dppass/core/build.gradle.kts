// Доменные примитивы: идентификаторы, адреса, CIDR, время, контрольные суммы.
plugins {
    id("dppass.kotlin-library")
}

dependencies {
    api(project(":diagnostics"))
}
