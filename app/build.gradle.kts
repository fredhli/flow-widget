plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fredhli.flowwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fredhli.flowwidget"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    buildFeatures {
        compose = true
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

    testImplementation("junit:junit:4.13.2")
    // org.json is a stub in local unit tests; this puts the real implementation on the
    // test classpath so FeedParser can be tested off-device.
    testImplementation("org.json:json:20240303")
}
