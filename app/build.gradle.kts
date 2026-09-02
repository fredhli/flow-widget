// FOR THE INTEGRATOR, if this file will not resolve or compile (docs/APP-SHELL-SPEC.md §8
// is the authority; this is the short form):
//   1. AGP 8.10.1 with Kotlin 2.1.0 and the compose plugin at 2.1.0 (all three in the root
//      build file). AGP then prints a KGP-version warning and nothing else changes.
//   2. If 36 itself is the problem, go back to compileSdk = targetSdk = 35 with AGP 8.7.3 +
//      Kotlin 2.1.0, and pin the shell's libraries to the versions that support 35:
//      core-ktx 1.16.0, activity-ktx 1.10.1, webkit 1.14.0, browser 1.8.0,
//      core-splashscreen 1.0.1. The manifest keeps android:enableOnBackInvokedCallback and
//      the shell behaves as specified, except predictive back becomes opt-in rather than
//      the default. Record the fallback in the integration notes; nothing else moves.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fredhli.flowwidget"
    // 36 (Android 16) since 2.0.0. The shell wants the platform's own behaviour on Fred's
    // One UI 9 / Android 17 phone rather than a compatibility path: predictive back on by
    // default at targetSdk 36, mandatory edge-to-edge (which is what the insets policy in
    // docs/APP-SHELL-SPEC.md §5 is written against), and the current WebView/insets
    // contract. Needs AGP >= 8.10 — see the root build.gradle.kts for the whole pin set and
    // its fallback ladder.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fredhli.flowwidget"
        minSdk = 31
        targetSdk = 36
        // 2.0.0: the same APK is now the widget AND the Dashboard app (a launcher
        // activity, a WebView shell, App Links). versionCode has to move for the phone to
        // accept the install over 1.2.0 (versionCode 4, the last widget-only release).
        versionCode = 6
        versionName = "2.0.1"
    }

    buildFeatures {
        compose = true
        // The shell reads BuildConfig.VERSION_NAME (the About line in app settings and the
        // metrics reply to the page) and BuildConfig.DEBUG (WebView contents debugging).
        // AGP 8 generates no BuildConfig class unless asked, and the failure is a
        // compile error in code that looks obviously correct.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // A DIFFERENT applicationId from the shipped widget. This is a safety
            // interlock, not tidiness. The debug build carries PreviewActivity, whose job
            // is to overwrite the app's DataStore with fixture values — base URL and token
            // included. Without a suffix both variants are `com.fredhli.flowwidget` at
            // versionCode 1 signed with the same debug key, so `adb install -r
            // app-debug.apk` installs cleanly OVER the release, keeps /data/data, and the
            // next preview run replaces Fred's hand-typed DASHBOARD_TOKEN with
            // "preview-fixture-token" — on the phone, recoverable only by typing it again.
            // The docs actively invite that target (README's `adb connect <phone-ip>:5555`,
            // VERIFY.md §5 "on the phone"). With the suffix the two coexist and neither can
            // see the other's DataStore. Class names are NOT suffixed — the namespace is
            // still com.fredhli.flowwidget — so `am start -n` needs the fully qualified
            // component, which is why widget-shots.sh spells the class out.
            applicationIdSuffix = ".debug"
        }
        release {
            // The shipped artifact. R8 tree-shakes what glance-appwidget pulls in but this
            // app never draws with — the Compose runtime and the parts of ui-graphics /
            // ui-unit behind a handful of imports — and the phone stops carrying it.
            // proguard-rules.pro turns obfuscation off, so nothing that resolves a class
            // by name can break.
            //
            // The comment here used to blame glance-material3 "for one ColorProviders
            // call". There is no such call: the palette is explicit day/night
            // ColorProviders from glance core (FlowWidget.kt), and both material3
            // dependencies were removed at review — glance-material3 never dragged in the
            // UI toolkit anyway (its only edges are annotation, compose-runtime, glance,
            // glance-appwidget); the separate compose material3 line was doing that.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key on purpose: this is sideloaded from Dropbox, never
            // published, and sharing the certificate with the previous debug builds is what
            // lets it install over them. Without a signingConfig, assembleRelease emits an
            // unsigned APK that the phone refuses outright.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // glance-appwidget alone supplies every Compose symbol this app imports:
    // compose-runtime (Composable/remember/collectAsState), ui-graphics (Color) and
    // ui-unit (DpSize/dp/sp). glance-material3 and compose material3 were declared for a
    // GlanceTheme/ColorProviders call that this app has never made — verified with
    // `grep -rniE "material3|ColorProviders|GlanceTheme" app/src`, whose only hit is the
    // comment in FlowWidget.kt saying it deliberately does the opposite.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The app shell (2.0.0). Five libraries, each for one thing the platform API does not
    // give us at minSdk 31, and nothing else — the APK is sideloaded from Dropbox and every
    // megabyte is one Fred waits for on the phone.
    //
    // core-ktx: ViewCompat.setOnApplyWindowInsetsListener + WindowInsetsCompat (the insets
    //   policy, §5) and WindowInsetsControllerCompat (light status/nav bar icons flipped
    //   from the page's theme colour). Also FileProvider, which Files.kt hands fetched
    //   documents to a viewer through.
    //   1.18.0, not 1.19.0: core 1.19 declares compileSdk 37 + AGP 9.1 as its floor, and
    //   this project compiles against 36 with AGP 8.11 (checkDebugAarMetadata fails
    //   otherwise). 1.18.0 is what activity 1.13.0 transitively resolves to anyway.
    implementation("androidx.core:core-ktx:1.18.0")
    // activity-ktx: ComponentActivity, enableEdgeToEdge() and the back-pressed dispatcher.
    //   Back has to be a dispatcher callback, not an onBackPressed override, or predictive
    //   back (default at targetSdk 36) closes the app instead of walking the hash history.
    implementation("androidx.activity:activity-ktx:1.13.0")
    // webkit: WebViewCompat.addWebMessageListener — the ONLY origin-scoped bridge into the
    //   page (addJavascriptInterface is not, and is not used). Plus addDocumentStartJavaScript,
    //   getCurrentWebViewPackage (the IME decision and the About line) and
    //   WebSettingsCompat.setAlgorithmicDarkeningAllowed(false), because the page owns dark
    //   mode.
    implementation("androidx.webkit:webkit:1.17.0")
    // browser: CustomTabsIntent, for the "Chrome Custom Tab" link policy.
    implementation("androidx.browser:browser:1.10.0")
    // core-splashscreen: the cold-start splash held until first paint, backported so the
    //   behaviour is one thing across the versions the phone might run.
    implementation("androidx.core:core-splashscreen:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // org.json is a stub in local unit tests; this puts the real implementation on the
    // test classpath so FeedParser can be tested off-device.
    testImplementation("org.json:json:20240303")
}
