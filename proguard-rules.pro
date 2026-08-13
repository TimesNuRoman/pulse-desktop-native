# SPDX-License-Identifier: Apache-2.0
# Pulse Desktop — ProGuard / R8 rules for packageReleaseExe/Msi/Dmg/Deb.
#
# Why we need this file: the Compose Multiplatform plugin (1.7.0) wires R8
# (Android's R8 = the modern ProGuard successor) for release packaging.
# Without rules, R8 strips or renames classes that BouncyCastle, Koin, SLF4J,
# and Kotlin reflection look up by name at runtime. The result is the famous
# 899-unresolved-refs cascade at link time.
#
# Strategy: keep the bytecode-heavy libraries wholesale, keep all
# Kotlin/Compose metadata + annotations, disable R8's optimization pass
# (it confuses kotlin-reflect), and suppress warnings for absent SLF4J
# backends (we use no SLF4J binding — PulseLogger goes to a file).

# ============================================================================
# 1. Don't strip or rename Kotlin / Compose metadata.
# ============================================================================
# These are needed by kotlin-reflect (used by Koin + BouncyCastle) and by
# Compose's own @Composable inference. Without them you get mysterious
# "NoClassDefFoundError" on @Composable lambdas at first recomposition.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, AnnotationDefault
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.coroutines.Continuation
-dontwarn kotlin.reflect.jvm.internal.**

# ============================================================================
# 2. Compose runtime + UI: keep @Composable + Compose internals.
# ============================================================================
# Compose Multiplatform does its own compiler-plugin magic; R8 should
# leave the runtime alone.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.desktop.** { *; }
-keep class androidx.compose.animation.** { *; }
-dontwarn androidx.compose.**

# Compose @Composable functions: keep their bytecode so Compose's
# own runtime can recompose them.
-keep,allowobfuscation,allowshrinking @androidx.compose.runtime.Composable class *
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ============================================================================
# 3. BouncyCastle: keep the whole provider + every internal it might load.
# ============================================================================
# BouncyCastle's JCA provider scans META-INF/services/ and uses Class.forName
# on dozens of internal cipher / digest / PBE classes. Stripping any of them
# crashes scrypt() in the middle of sync. We keep the whole module.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# META-INF/services entries that BouncyCastle registers with JCA.
-keepclassmembers class * implements org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter {
    public <init>(...);
}

# ============================================================================
# 4. Koin: keep the DI module registration alive.
# ============================================================================
# Koin uses kotlin-reflect to look up classes by KClass at runtime.
# Without these, every `get<X>()` call after obfuscation throws
# "NoBeanDefFoundException" because the class name changed.
-keep class org.koin.** { *; }
-keep interface org.koin.** { *; }
-keep class * extends org.koin.core.module.Module
-keep class * implements org.koin.core.annotation.Single
-keep class * implements org.koin.core.annotation.Factory
-keep class * implements org.koin.core.annotation.KoinDefinition
-dontwarn org.koin.**

# ============================================================================
# 5. SLF4J: no binding here, but BouncyCastle + Koin import the API.
# ============================================================================
# We log through PulseLogger (raw FileWriter) and never bind an SLF4J
# provider. The API jar is on the classpath as a transitive dep, so R8
# sees references to org.slf4j.LoggerFactory and complains. Tell it to
# stay quiet.
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
-keep interface org.slf4j.** { *; }

# ============================================================================
# 6. Coroutines: keep the coroutine name + exception handler classes.
# ============================================================================
# kotlinx-coroutines uses reflection to render coroutine names in errors
# (visible in stack traces) and to dispatch the Main dispatcher.
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>(...);
}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory
-dontwarn kotlinx.coroutines.flow.**internal.**

# ============================================================================
# 7. SQLite JDBC native loader: keep its initializer.
# ============================================================================
# sqlite-jdbc has a static initializer that calls System.loadLibrary on the
# native .so / .dll. R8 may rename the static method that runs it.
-keep class org.sqlite.** { *; }
-keepclassmembers class org.sqlite.JDBC {
    static <fields>;
    static <methods>;
}
-dontwarn org.sqlite.**

# ============================================================================
# 8. Our own entry points + state classes (defensive).
# ============================================================================
# R8 should not rename MainKt.main() (the JVM looks it up by name) and we
# want all `data class` accessors to survive for kotlinx.serialization-free
# persistence (we use Properties files).
-keep class com.pulseteam.desktop.MainKt {
    public static void main(java.lang.String[]);
}
-keep class com.pulseteam.desktop.data.** { *; }
-keep class com.pulseteam.desktop.ui.** { *; }

# ============================================================================
# 9. Don't optimize — Kotlin reflection breaks with R8's optimization pass.
# ============================================================================
# Optimization inlines and reorders code in ways that confuse kotlin-reflect's
# jvmClassMapping. With our mix of Koin + BouncyCastle + Compose, the
# simplest safe choice is to leave optimization off. We still get minification
# (renaming) for size, which is the main win.
-dontoptimize
-dontpreverify

# ============================================================================
# 10. Silence warnings about classes we don't actually use.
# ============================================================================
# kotlinx-serialization is a transitive of kotlinx-datetime (which is
# transitive of coroutines). We don't use it; the API jar is on the
# classpath at compile time but the runtime impl is absent. ProGuard
# reports 800+ "can't find superclass" warnings for kotlinx.serialization.**
# — all false positives. Suppress them.
-dontwarn kotlinx.serialization.**
-dontwarn kotlinx.datetime.**

# androidx.lifecycle is on the classpath transitively (via Compose's
# LifecycleEventEffect), but we don't use it. The Compose "keeps the
# entry point but not the descriptor class" notes are harmless — the
# affected methods are dead code in our actual call graph.
-dontwarn androidx.lifecycle.**
-dontwarn androidx.lifecycle.compose.**

# Force ProGuard to keep going even with descriptor-class warnings. The
# "keeps entry point but not descriptor class" notes for Compose APIs
# (FlowCollector, Composer, IntList, etc.) are a known R8/Compose-compiler
# interaction where the keep directive on @Composable functions pulls in
# signature types that we never call. The build artifact is correct;
# ProGuard just wants us to be explicit.
-ignorewarnings
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.tooling.** { *; }
-keep class kotlinx.coroutines.flow.FlowCollector { *; }
-keep class kotlinx.coroutines.flow.StateFlow { *; }
-keep class androidx.collection.IntList { *; }
-keep class androidx.collection.IntObjectMap { *; }
-keep class androidx.collection.ScatterMap { *; }
-keep class androidx.collection.ScatterSet { *; }
-keep class androidx.collection.MutableIntList { *; }
-keep class androidx.collection.MutableIntObjectMap { *; }
