import java.time.Duration

plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit) }
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

tasks.test {
    exclude("**/FfmpegIntegrationTest*")
}

val generateFfmpegFixtures by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("fixtures")
    val executable = providers.environmentVariable("FFMPEG")
    doFirst {
        require(executable.isPresent) { "Set FFMPEG to an installed FFmpeg executable (libx264 required)." }
        output.get().asFile.mkdirs()
        commandLine(
            executable.get(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=5",
            "-frames:v", "3", "-an", "-c:v", "libx264", "-preset", "ultrafast",
            "-tune", "zerolatency", "-pix_fmt", "yuv420p", "-bf", "0",
            "-x264-params", "aud=1:repeat-headers=1:keyint=5:slices=2",
            "-f", "h264", output.get().file("synthetic.h264").asFile.absolutePath
        )
    }
    outputs.dir(output)
    outputs.upToDateWhen { false }
    timeout.set(Duration.ofSeconds(30))
}

tasks.register<Test>("ffmpegIntegrationTest") {
    description = "Opt-in file-only Annex-B validation with FFmpeg-generated testsrc2."
    group = "verification"
    dependsOn(generateFfmpegFixtures)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/FfmpegIntegrationTest*")
    inputs.file(layout.buildDirectory.file("fixtures/synthetic.h264"))
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("fixturePath", layout.buildDirectory.file("fixtures/synthetic.h264").get().asFile.absolutePath)
}
