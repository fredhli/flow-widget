// Flow widget / Dashboard app — root build file. Version pins live here and in
// app/build.gradle.kts.
//
// WHY THESE THREE NUMBERS (moved up from AGP 8.7.3 / Kotlin 2.1.0 for the 2.0.0 app shell):
//
// * AGP 8.11.1 — the shell targets Android 16 (API 36) so it gets the platform's own
//   predictive-back and edge-to-edge behaviour on Fred's One UI 9 phone rather than the
//   compatibility path. AGP 8.7.3 refuses a compileSdk above 35 outright; compileSdk 36
//   needs AGP >= 8.10. 8.11.1 is the newest AGP the rest of this pin set allows.
// * Kotlin 2.2.21 — AGP 8.11.1 pairs with it, and the Kotlin Gradle Plugin compatibility
//   matrix puts 2.2.20-2.2.21 at Gradle <= 8.14 and AGP <= 8.11.1. The wrapper here is
//   Gradle 8.14.5 on JDK 17, which sits inside that box. Bumping any one of the four
//   without checking the other three is how this build breaks.
// * org.jetbrains.kotlin.plugin.compose 2.2.21 — since Kotlin 2.0 the Compose compiler
//   ships with Kotlin and its plugin version MUST EQUAL the Kotlin version. A mismatch is
//   a configuration-time error, not a subtle one.
//
// Glance 1.1.1 (the widget's whole UI) is built against compose-runtime 1.7.0 and compiles
// fine under Kotlin 2.2 — the widget's Kotlin is unchanged in 2.0.0 and must stay so.
//
// If this set cannot resolve or compile, docs/APP-SHELL-SPEC.md §8 has the fallback ladder:
// (1) AGP 8.10.1 + Kotlin 2.1.0 + compose plugin 2.1.0; (2) back to compileSdk/targetSdk 35
// with AGP 8.7.3 + Kotlin 2.1.0 and the older androidx pins listed there.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
