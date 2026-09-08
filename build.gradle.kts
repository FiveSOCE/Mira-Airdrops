import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "com.mira"
version = "0.1.8"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

val miraCoreVersion = "0.4.1"
val miraCoreSha256 = "4a20f538762bb550b4f8c359eb16945eee786ed0741ba60c0dbfc7e07e2249a9"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile
val miraFactionsVersion = "0.2.18"
val miraFactionsSha256 = "4bbd2867a00aafffb4f0ded82aba07cac92d3f11a21cf8359289fc8cd2def751"
val miraFactionsJar = layout.projectDirectory.file("libs/MiraFactions-$miraFactionsVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

fun downloadVerified(url: String, target: File, expectedSha256: String) {
    if (target.exists() && sha256(target) == expectedSha256) return
    target.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    check(sha256(target) == expectedSha256) {
        "Downloaded dependency failed SHA-256 verification: ${target.name}"
    }
}

val downloadMiraDependencies by tasks.registering {
    doLast {
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar",
            miraCoreJar, miraCoreSha256
        )
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-Factions/releases/download/v$miraFactionsVersion/MiraFactions-$miraFactionsVersion.jar",
            miraFactionsJar, miraFactionsSha256
        )
    }
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse("1.21.11-R0.1-SNAPSHOT")
val compileJavaVersion = providers.gradleProperty("compileJavaVersion").map(String::toInt).orElse(21)
val bytecodeJavaVersion = providers.gradleProperty("bytecodeJavaVersion").map(String::toInt).orElse(21)

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19") {
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "com.google.code.gson", module = "gson")
    }
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraFactionsJar))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(compileJavaVersion.get())) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraDependencies)
    options.encoding = "UTF-8"
    options.release.set(bytecodeJavaVersion.get())
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar { archiveFileName.set("MiraAirdrops-${project.version}.jar") }
