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
    // The benchmark's null-device sink bypasses the library's regular-file check on purpose, so a
    // test (NullDeviceSinksTest) checks that a logger on it loses nothing; otherwise the benchmark
    // could silently measure a logger that drops every message, as it once did.
    testImplementation(sourceSets["jmh"].output)

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

// A one-minute soak through the public API: 100 loggers, one worker each, 5 traces/s each to 100
// files on the shared pool, then every logger's stats. .\gradlew.bat manyLoggers [-Pmany.loggers=100
// -Pmany.rate=5 -Pmany.seconds=60 -Pmany.producers=4 -Pmany.dir=build/many-loggers]
tasks.register<JavaExec>("manyLoggers") {
    description = "Runs ManyLoggersBenchmark: many loggers writing a slow trickle each for a fixed time"
    group = "benchmark"
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass = "org.example.efficientlogger.ManyLoggersBenchmark"
    for (option in listOf("loggers", "rate", "seconds", "producers", "dir")) {
        (findProperty("many.$option") as String?)?.let { systemProperty("many.$option", it) }
    }
    (findProperty("many.jvmArgs") as String?)?.let { jvmArgs(it.split(" ")) } // e.g. -Xlog:safepoint,gc:file=build/jvm.log
}

tasks.named<Jar>("jmhJar") {
    manifest {
        attributes("Multi-Release" to "true") // log4j-core has Java 9+ versions of some classes
    }
}
