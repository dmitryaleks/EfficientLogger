// No toolchain resolver plugin: the build uses a locally installed JDK 17 and never downloads one
// (gradle.properties turns toolchain auto-download off), so there is one plugin less to trust.
rootProject.name = "EfficientLogger"
