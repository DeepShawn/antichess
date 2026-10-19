// Pure-Kotlin chess core shared by the Android app: rules, built-in engine,
// opening book, personality and review. Runs on any JVM, so it is unit-tested in CI.
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies { testImplementation(kotlin("test-junit")) }

tasks.test {
    systemProperty("openings", rootProject.file("web/data/openings.txt").absolutePath)
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
