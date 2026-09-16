plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.example.artlibrary"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // The library does not minify itself, but consumers should be
            // able to. Keep this off here so the AAR ships unstripped.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
    // Maven Central requires a sources jar AND a javadoc jar.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.aiotrixdev"
                artifactId = "art-kotlin-adk"
                version = "1.0.3"
                // Maven Central requires complete POM metadata.
                pom {
                    name.set("ART Kotlin ADK")
                    description.set("Realtime communication SDK for Android — WebSocket, CRDT sync, end-to-end crypto, and AI agent/orchestrator integration.")
                    url.set("https://github.com/aiotrixdev/art-kotlin-adk")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("aiotrixdev")
                            name.set("Aiotrixdev")
                        }
                    }
                    scm {
                        url.set("https://github.com/aiotrixdev/art-kotlin-adk")
                        connection.set("scm:git:git://github.com/aiotrixdev/art-kotlin-adk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/aiotrixdev/art-kotlin-adk.git")
                    }
                }
            }
        }
        // Writes the full signed layout to <root>/build/central-bundle/ for a
        // manual bundle upload on central.sonatype.com.
        repositories {
            maven {
                name = "centralBundle"
                url = uri("${rootProject.layout.buildDirectory.get().asFile}/central-bundle")
            }
        }
    }
    // Maven Central requires a GPG signature (.asc) on every file.
    // useGpgCmd() delegates to your gpg CLI (signs ed25519 natively, passphrase
    // via gpg-agent) instead of the bundled BouncyCastle, which fails on modern
    // GnuPG-exported keys with "checksum mismatch".
    signing {
        useGpgCmd()
        sign(publishing.publications["release"])
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    /* Network */
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    /* Crypto */
    implementation("com.github.joshjdevl.libsodiumjni:libsodium-jni-aar:2.0.2")

    /* Coroutines (core + Android dispatchers) */
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    /* JSON */
    implementation("com.google.code.gson:gson:2.11.0")
}
