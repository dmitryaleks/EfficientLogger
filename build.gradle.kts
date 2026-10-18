plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
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

    // Benchmarks only (src/jmh): log4j2 async loggers as a baseline.
    "jmhImplementation"("org.apache.logging.log4j:log4j-core:2.24.3")
    "jmhImplementation"("com.lmax:disruptor:4.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    when (name) {
        "jmhCompileGeneratedClasses" -> {} // JMH's generated code isn't ours to lint
        "compileJmhJava" -> options.compilerArgs.add("-Xlint:all,-classfile") // log4j-core refers to annotations it doesn't ship
        else -> options.compilerArgs.add("-Xlint:all")
    }
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

// Benchmarks: .\gradlew.bat jmh (results in build/reports/jmh), or run the jar for JMH's own options:
// java -jar build/libs/EfficientLogger-1.0-SNAPSHOT-jmh.jar -prof gc -p output=disk
jmh {
    jmhVersion = "1.37"
    includeTests = false
    resultFormat = "JSON"
    (findProperty("jmhIncludes") as String?)?.let { includes = listOf(it) }
    (findProperty("jmhProfilers") as String?)?.let { profilers = it.split(",") }
}

tasks.named<Jar>("jmhJar") {
    manifest {
        attributes("Multi-Release" to "true") // log4j-core has Java 9+ versions of some classes
    }
}
