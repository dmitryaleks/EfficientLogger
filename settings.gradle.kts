// A JDK 17 toolchain is downloaded if none is installed, through the Foojay resolver (pinned by
// checksum in gradle/verification-metadata.xml like every other plugin), so a fresh checkout builds
// with nothing but a Java runtime to start Gradle. The JDK itself comes from its vendor over HTTPS
// through the Foojay Disco API, matched to the version asked for; it isn't checksum-pinned, since
// Gradle's dependency verification covers plugins and dependencies only. Install a JDK 17 to skip
// the download (SECURITY_AUDIT.md, F12).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "EfficientLogger"
