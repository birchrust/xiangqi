plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.xiangqi.assistant"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Package the executable in Android's native library directory.
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols.add("**/libpikafish.so")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val verifyArchitecture by tasks.registering {
    group = "verification"
    description = "Checks imports in platform-independent packages."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val root = "dev.xiangqi.assistant."
        val boundaries = mapOf(
            "${root}domain." to listOf("${root}domain.", "kotlin."),
            "${root}engine.model" to listOf("${root}engine.model.", "kotlin."),
            "${root}application." to listOf("${root}domain.", "${root}engine.model.", "java.", "kotlin.")
        )
        val packagePattern = Regex("(?m)^package (\\S+)")
        val importPattern = Regex("(?m)^import (\\S+)")
        for (source in sources) {
            val text = source.readText()
            val name = packagePattern.find(text)?.groupValues?.get(1) ?: continue
            val allowed = boundaries.entries.firstOrNull { name.startsWith(it.key) }?.value ?: continue
            for (match in importPattern.findAll(text)) {
                val dependency = match.groupValues[1]
                check(allowed.any { dependency.startsWith(it) }) {
                    "${source.name}: $dependency crosses the $name boundary"
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyArchitecture) }
