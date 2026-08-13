// Pulse Desktop - native Kotlin + Jetpack Compose Multiplatform Desktop
// Stack: Kotlin 2.0.21 + Compose 1.7.0 + Koin
// Window: 1280x800 (min 1024x640), Tokyo Night, square edges.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

group = "com.pulseteam.desktop"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://packages.jetbrains.team/maven/p/cmp/dev")
}

dependencies {
    // The compose plugin auto-resolves ui-desktop, material3-desktop, etc.
    // for the current OS. Just declare the BOM-less artifacts we need.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    // Skiko native runtime is auto-resolved by `compose.desktop.currentOs` per host.
    // We used to hardcode skiko-awt-runtime-windows-x64:0.8.18, which broke Linux builds.
    // If you need to pin a Skiko version, do it via the compose plugin's skiko config.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.45.0.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("io.insert-koin:koin-compose:4.0.0")
    implementation("io.insert-koin:koin-core:4.0.0")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.pulseteam.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Pulse"
            packageVersion = "1.0.0"
        }
        // ProGuard / R8 rules for release packaging. Without these, R8
        // fails with ~900 unresolved references on BouncyCastle / SLF4J /
        // Koin reflection lookups (see proguard-rules.pro for the full
        // breakdown). We disable optimization (kotlin-reflect breaks) but
        // keep minification, so the .exe is meaningfully smaller than a
        // debug-variant build.
        buildTypes {
            release {
                proguard {
                    configurationFiles.from(file("proguard-rules.pro"))
                    obfuscate = true
                    optimize = false
                }
            }
        }
    }
}
