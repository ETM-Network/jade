plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "network.etm.tts"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { test ->
            // The desktop tests render with real models: pass -PmodelDir=<an onnx model folder> to enable them.
            providers.gradleProperty("modelDir").orNull?.let { test.systemProperty("etm.tts.modelDir", it) }
            providers.gradleProperty("referenceDir").orNull?.let { test.systemProperty("etm.tts.referenceDir", it) }
            providers.gradleProperty("watermarkReference").orNull?.let { test.systemProperty("etm.tts.watermarkReference", it) }
            // ONNX Runtime's Windows DLL needs a newer VC++ runtime than some bundled JDKs ship; -PtestJava=<java>
            // runs only the unit tests on another JVM.
            providers.gradleProperty("testJava").orNull?.let { test.executable = it }
            test.maxHeapSize = "4g"
            test.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Unit tests run on the desktop JVM: the desktop ONNX Runtime and a real org.json replace the Android ones.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
