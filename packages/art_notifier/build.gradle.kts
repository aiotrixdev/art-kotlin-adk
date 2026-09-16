plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.example.art_notifier"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
                artifactId = "art-notifier"
                version = "1.0.0"
                // Maven Central requires complete POM metadata.
                pom {
                    name.set("ART Notifier")
                    description.set("Notifications plugin for the ART Kotlin ADK — live (WebSocket) and REST notifications, plus FCM push device registration.")
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
    api(project(":art_adk_library"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
