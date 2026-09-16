// Top-level build file for the ART ADK workspace.
//
// This root project is an umbrella only — it holds NO source and NO library
// configuration. Each publishable package is an independent Gradle module under
// `packages/` (see settings.gradle.kts). Declaring the plugins here with
// `apply false` pins a single version for every module without applying them to
// the root.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
}
