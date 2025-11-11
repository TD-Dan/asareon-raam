@file:OptIn(ExperimentalComposeLibrary::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import kotlinx.serialization.json.*
import org.jetbrains.compose.ExperimentalComposeLibrary

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinxSerialization)
}

tasks.register("generateActionRegistry") {
    description = "Generates a Kotlin source file with compile-time constants for all public action names and private envelope types from *.actions.json manifests."
    group = "auf"

    // --- Configuration ---
    val inputDir = file("src/commonMain/kotlin/app/auf")
    val outputFile = file("$buildDir/generated/kotlin/app/auf/core/generated/ActionNames.kt")

    // --- Gradle Inputs/Outputs for build caching and up-to-date checks ---
    inputs.dir(inputDir)
    outputs.file(outputFile)

    // --- Task Action ---
    doLast {
        val json = Json { ignoreUnknownKeys = true }
        val actionNames = mutableSetOf<String>()
        val envelopeTypes = mutableSetOf<String>()

        inputDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".actions.json")) {
                try {
                    val content = file.readText()
                    val manifest = json.parseToJsonElement(content).jsonObject

                    // Parse public actions
                    (manifest["listensFor"] as? JsonArray)?.forEach {
                        val actionName = (it as? JsonObject)?.get("action_name")?.jsonPrimitive?.content
                        if (actionName != null) actionNames.add(actionName)
                    }
                    (manifest["publishes"] as? JsonArray)?.forEach {
                        val actionName = (it as? JsonObject)?.get("action_name")?.jsonPrimitive?.content
                        if (actionName != null) actionNames.add(actionName)
                    }

                    // Parse private envelope types
                    (manifest["private_envelopes"] as? JsonArray)?.forEach {
                        val typeName = (it as? JsonObject)?.get("type_name")?.jsonPrimitive?.content
                        if (typeName != null) envelopeTypes.add(typeName)
                    }
                } catch (e: Exception) {
                    throw GradleException("Failed to parse action manifest: ${file.path}. Error: ${e.message}", e)
                }
            }
        }

        val sortedActionNames = actionNames.sorted()
        val sortedEnvelopeTypes = envelopeTypes.sorted()

        val actionConstants = sortedActionNames.joinToString("\n") { actionName ->
            val constName = actionName.replace('.', '_').replace('-', '_').uppercase()
            "    const val $constName = \"$actionName\""
        }

        val envelopeConstants = sortedEnvelopeTypes.joinToString("\n") { typeName ->
            val constName = typeName.replace('.', '_').replace('-', '_').uppercase()
            "        const val $constName = \"$typeName\""
        }

        val setEntries = sortedActionNames.joinToString(",\n") { actionName ->
            val constName = actionName.replace('.', '_').replace('-', '_').uppercase()
            "        $constName"
        }

        val fileContent = """
            package app.auf.core.generated

            /**
             * THIS IS A GENERATED FILE. DO NOT EDIT.
             * Contains compile-time constants for all communication contracts,
             * generated from the *.actions.json manifests during the build process.
             */
            object ActionNames {
            $actionConstants

                /**
                 * Contains compile-time constants for all valid private data envelope types.
                 */
                object Envelopes {
            $envelopeConstants
                }

                /**
                 * A set of all valid public action names for runtime validation in the Store.
                 * This is constructed from the compile-time constants above.
                 */
                val allActionNames: Set<String> = setOf(
            $setEntries
                )
            }
        """.trimIndent()

        outputFile.parentFile.mkdirs()
        outputFile.writeText(fileContent)
        println("Generated ActionNames.kt with ${actionNames.size} actions and ${envelopeTypes.size} envelope types.")
    }
}


kotlin {

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside the browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }

    // This ensures our file is generated before any compilation happens.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        dependsOn(tasks.getByName("generateActionRegistry"))
    }

    sourceSets {
        // This tells the compiler to include our generated file in the build.
        commonMain.get().kotlin.srcDir("$buildDir/generated/kotlin")

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.android)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(compose.materialIconsExtended)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(compose.uiTest)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            implementation(libs.junit.jupiter.api)
            runtimeOnly(libs.junit.jupiter.engine)

            implementation(libs.compose.ui.test.junit4)
            runtimeOnly(libs.junit.vintage.engine)

            // Dependencies for the code under test
            implementation(libs.ktor.client.cio)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        listOf("iosX64Main", "iosArm64Main", "iosSimulatorArm64Main").forEach {
            getByName(it).dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        getByName("wasmJsMain").dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

android {
    namespace = "app.auf"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.auf"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    dependencies {
        debugImplementation(compose.uiTooling)
    }
}

compose.desktop {
    application {
        mainClass = "app.auf.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "app.auf"
            packageVersion = "1.0.0"
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
            }
        }
    }
}

compose.resources {
    publicResClass = true
}