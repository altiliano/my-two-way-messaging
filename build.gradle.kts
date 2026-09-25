plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:5.35.2.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-format-jackson")

    testImplementation(kotlin("test"))
    testImplementation("org.http4k:http4k-testing-hamkrest")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("messaging.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
