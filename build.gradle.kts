plugins {
    `java-library`
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("alloc")
    }
    maxHeapSize = "1g"
}

// Allocation tests (@Tag("alloc")) run in their own JVMs: once normally, and once with
// escape analysis off to prove the hot paths are allocation-free by construction.
fun registerAllocationTest(name: String, escapeAnalysis: Boolean) =
    tasks.register<Test>(name) {
        description = "Runs @Tag(\"alloc\") tests" + if (escapeAnalysis) "" else " with escape analysis off"
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            includeTags("alloc")
        }
        maxHeapSize = "512m"
        if (!escapeAnalysis) {
            jvmArgs("-XX:-DoEscapeAnalysis")
        }
        systemProperty("efficientlogger.escapeAnalysis", escapeAnalysis) // checked by the tests
        shouldRunAfter(tasks.test)
    }

val allocationTest = registerAllocationTest("allocationTest", escapeAnalysis = true)
val allocationTestNoEA = registerAllocationTest("allocationTestNoEA", escapeAnalysis = false)

tasks.check {
    dependsOn(allocationTest, allocationTestNoEA)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.example.efficientlogger")
    }
}
