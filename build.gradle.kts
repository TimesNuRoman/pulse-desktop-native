// Pulse Desktop - native Kotlin + Jetpack Compose Multiplatform Desktop
// Stack: Kotlin 2.0.21 + Compose 1.7.0 + Koin
// Window: 1280x800 (min 1024x640), Tokyo Night, square edges.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.time.Duration

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
    implementation("org.json:json:20231013")
    implementation("io.insert-koin:koin-compose:4.0.0")
    implementation("io.insert-koin:koin-core:4.0.0")

    // Test deps — JUnit 5 + kotlin-test for the unit suite. We use the
    // JVM-only `kotlin-test-junit5` artifact; no Compose UI tests yet
    // (those would need `createDesktopComposeTestRule`).
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // Crypto tests take ~1s each (scrypt is slow on purpose); set a
    // generous timeout so the suite doesn't flake on slow CI.
    timeout.set(Duration.ofSeconds(120))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

compose.desktop {
    application {
        mainClass = "com.pulseteam.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Pulse"
            packageVersion = "1.0.0"

            // ---------------------------------------------------------------
            // Windows code-signing placeholder. Activated when the build
            // host has an Authenticode .pfx available; see tools/sign-windows.ps1
            // and docs/signing.md. Compose-jb's DSL doesn't expose jpackage's
            // `--win-sign` flag, so we do post-build signing via signtool.exe
            // (or sign-code if cross-platform). The block below is a no-op
            // when PULSE_SIGN_PFX is unset.
            // ---------------------------------------------------------------
            windows {
                // The compose-jb DSL does not have a first-class sign hook
                // (jpackage does, but the plugin wraps it behind
                // `nativeDistributions.windows { signConfig { ... } }` which
                // is undocumented and version-dependent). Instead, we sign
                // after `packageReleaseExe` via tools/sign-windows.ps1.
                // See that script for the signtool.exe invocation.
                //
                // To activate locally:
                //   $env:PULSE_SIGN_PFX = 'C:\keys\pulse-2026.pfx'
                //   $env:PULSE_SIGN_PWD = (read-host -assecurestring)
                //   $env:PULSE_SIGN_TSA = 'http://timestamp.digicert.com'
                //   .\tools\sign-windows.ps1
            }

            // ---------------------------------------------------------------
            // macOS notarization placeholder. Same shape as Windows:
            // activated by env vars, runs through tools/sign-mac.sh.
            // ---------------------------------------------------------------
            macOS {
                // jpackage's --mac-sign and --mac-package-signing-prefix are
                // also not exposed by the compose-jb DSL. We do post-build
                // signing + notarization in tools/sign-mac.sh using
                // `codesign`, `xcrun notarytool`, and `xcrun stapler`.
                //
                // To activate locally:
                //   export PULSE_MAC_SIGN_IDENTITY='Developer ID Application: Your Name (TEAMID)'
                //   export PULSE_MAC_KEYCHAIN_PROFILE='pulse-notary'
                //   ./tools/sign-mac.sh
            }
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
