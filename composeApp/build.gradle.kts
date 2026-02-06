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
    description = "Generates Kotlin source files with compile-time constants for all communication contracts and exposed agent actions from *.actions.json manifests."
    group = "auf"

    // --- Configuration ---
    val inputDir = file("src/commonMain/kotlin/app/auf")
    val generatedDir = layout.buildDirectory.dir("generated/kotlin/app/auf/core/generated")
    val actionNamesOutputFile = generatedDir.map { it.file("ActionNames.kt") }
    val exposedActionsOutputFile = generatedDir.map { it.file("ExposedActions.kt") }

    // --- Gradle Inputs/Outputs for build caching and up-to-date checks ---
    inputs.dir(inputDir)
    outputs.file(actionNamesOutputFile)
    outputs.file(exposedActionsOutputFile)

    // --- Task Action ---
    doLast {
        val json = Json { ignoreUnknownKeys = true }
        val actionNames = mutableSetOf<String>()
        val envelopeTypes = mutableSetOf<String>()

        // Exposed Actions — plain maps only (no data classes in Gradle DSL doLast blocks)
        val exposedActionNames = mutableSetOf<String>()
        val sandboxRules = mutableMapOf<String, Map<String, Any>>()
        val exposedDocs = mutableListOf<Map<String, Any>>()

        inputDir.walkTopDown().forEach { manifestFile ->
            if (manifestFile.isFile && manifestFile.name.endsWith(".actions.json")) {
                try {
                    val content = manifestFile.readText()
                    val manifest = json.parseToJsonElement(content).jsonObject

                    // Parse public actions
                    val listensFor = manifest["listensFor"] as? JsonArray
                    if (listensFor != null) {
                        for (i in 0 until listensFor.size) {
                            val name = (listensFor[i] as? JsonObject)?.get("action_name")?.jsonPrimitive?.content
                            if (name != null) actionNames.add(name)
                        }
                    }
                    val publishes = manifest["publishes"] as? JsonArray
                    if (publishes != null) {
                        for (i in 0 until publishes.size) {
                            val name = (publishes[i] as? JsonObject)?.get("action_name")?.jsonPrimitive?.content
                            if (name != null) actionNames.add(name)
                        }
                    }

                    // Parse private envelope types
                    val privateEnvelopes = manifest["private_envelopes"] as? JsonArray
                    if (privateEnvelopes != null) {
                        for (i in 0 until privateEnvelopes.size) {
                            val typeName = (privateEnvelopes[i] as? JsonObject)?.get("type_name")?.jsonPrimitive?.content
                            if (typeName != null) envelopeTypes.add(typeName)
                        }
                    }

                    // Parse exposed agent actions
                    val exposed = manifest["exposedToAgents"] as? JsonArray
                    if (exposed != null) {
                        for (i in 0 until exposed.size) {
                            val obj = exposed[i] as? JsonObject ?: continue
                            val actionName = obj["action_name"]?.jsonPrimitive?.content ?: continue
                            val summary = obj["summary"]?.jsonPrimitive?.content ?: ""

                            exposedActionNames.add(actionName)

                            // Parse sandboxing rule
                            val sandboxing = obj["sandboxing"]?.jsonObject
                            if (sandboxing != null) {
                                val strategy = sandboxing["strategy"]?.jsonPrimitive?.content ?: ""
                                val prefix = sandboxing["subpath_prefix_template"]?.jsonPrimitive?.content ?: ""
                                val rewriteObj = sandboxing["payload_rewrites"]?.jsonObject
                                val rewrites = mutableMapOf<String, String>()
                                if (rewriteObj != null) {
                                    for (key in rewriteObj.keys) {
                                        rewrites[key] = rewriteObj[key].toString()
                                    }
                                }
                                sandboxRules[actionName] = mapOf(
                                    "strategy" to strategy,
                                    "subpathPrefixTemplate" to prefix,
                                    "payloadRewrites" to rewrites
                                )
                            }

                            // Parse payload schema fields for documentation
                            val payloadFields = mutableListOf<Map<String, Any>>()
                            val schema = obj["payload_schema"]?.jsonObject
                            if (schema != null) {
                                val requiredArr = schema["required"] as? JsonArray
                                val requiredFields = mutableSetOf<String>()
                                if (requiredArr != null) {
                                    for (j in 0 until requiredArr.size) {
                                        requiredFields.add(requiredArr[j].jsonPrimitive.content)
                                    }
                                }
                                val properties = schema["properties"]?.jsonObject
                                if (properties != null) {
                                    for (fieldName in properties.keys) {
                                        val fieldObj = properties[fieldName]!!.jsonObject
                                        payloadFields.add(mapOf(
                                            "name" to fieldName,
                                            "type" to (fieldObj["type"]?.jsonPrimitive?.content ?: "string"),
                                            "description" to (fieldObj["description"]?.jsonPrimitive?.content ?: ""),
                                            "required" to requiredFields.contains(fieldName),
                                            "default" to (fieldObj["default"]?.toString() ?: "")
                                        ))
                                    }
                                }
                            }

                            exposedDocs.add(mapOf(
                                "actionName" to actionName,
                                "summary" to summary,
                                "payloadFields" to payloadFields
                            ))
                        }
                    }

                } catch (e: Exception) {
                    throw GradleException("Failed to parse action manifest: ${manifestFile.path}. Error: ${e.message}", e)
                }
            }
        }

        // ====== Generate ActionNames.kt ======
        val sortedActionNames = actionNames.sorted()
        val sortedEnvelopeTypes = envelopeTypes.sorted()

        val actionConstants = sortedActionNames.joinToString("\n") { name ->
            val constName = name.replace('.', '_').replace('-', '_').uppercase()
            "    const val $constName = \"$name\""
        }

        val envelopeConstants = sortedEnvelopeTypes.joinToString("\n") { tn ->
            val constName = tn.replace('.', '_').replace('-', '_').uppercase()
            "        const val $constName = \"$tn\""
        }

        val setEntries = sortedActionNames.joinToString(",\n") { name ->
            val constName = name.replace('.', '_').replace('-', '_').uppercase()
            "        $constName"
        }

        val actionNamesContent = """
            |package app.auf.core.generated
            |
            |/**
            | * THIS IS A GENERATED FILE. DO NOT EDIT.
            | * Contains compile-time constants for all communication contracts,
            | * generated from the *.actions.json manifests during the build process.
            | */
            |object ActionNames {
            |$actionConstants
            |
            |    /**
            |     * Contains compile-time constants for all valid private data envelope types.
            |     */
            |    object Envelopes {
            |$envelopeConstants
            |    }
            |
            |    /**
            |     * A set of all valid public action names for runtime validation in the Store.
            |     * This is constructed from the compile-time constants above.
            |     */
            |    val allActionNames: Set<String> = setOf(
            |$setEntries
            |    )
            |}
        """.trimMargin()

        val actionNamesOut = actionNamesOutputFile.get().asFile
        actionNamesOut.parentFile.mkdirs()
        actionNamesOut.writeText(actionNamesContent)

        // ====== Generate ExposedActions.kt ======
        val sortedExposedNames = exposedActionNames.sorted()

        val allowedSetStr = sortedExposedNames.joinToString(",\n") { "        \"$it\"" }

        val sandboxRuleStr = sandboxRules.entries.sortedBy { it.key }.joinToString(",\n") { entry ->
            val actionName = entry.key
            val rule = entry.value
            val strategy = rule["strategy"] as String
            val prefix = rule["subpathPrefixTemplate"] as String
            @Suppress("UNCHECKED_CAST")
            val rewrites = rule["payloadRewrites"] as Map<String, String>
            val rewritesStr = if (rewrites.isEmpty()) {
                "emptyMap()"
            } else {
                "mapOf(${rewrites.entries.joinToString(", ") { rw -> "\"${rw.key}\" to \"${rw.value}\"" }})"
            }
            """        "$actionName" to SandboxRule(
            |            strategy = "$strategy",
            |            subpathPrefixTemplate = "$prefix",
            |            payloadRewrites = $rewritesStr
            |        )""".trimMargin()
        }

        val sortedDocs = exposedDocs.sortedBy { it["actionName"] as String }
        val docStr = sortedDocs.joinToString(",\n") { doc ->
            val actionName = doc["actionName"] as String
            val summary = (doc["summary"] as String).replace("\"", "\\\"")
            @Suppress("UNCHECKED_CAST")
            val fields = doc["payloadFields"] as List<Map<String, Any>>

            val fieldsStr = if (fields.isEmpty()) {
                "emptyList()"
            } else {
                val fieldLines = fields.joinToString(",\n") { field ->
                    val fname = field["name"] as String
                    val ftype = field["type"] as String
                    val fdesc = (field["description"] as String).replace("\"", "\\\"").replace("\n", "\\n")
                    val freq = field["required"] as Boolean
                    val fdefault = field["default"] as String
                    val fdefaultStr = if (fdefault.isEmpty()) "null" else "\"${fdefault.replace("\"", "\\\"")}\""
                    "                PayloadField(\"$fname\", \"$ftype\", \"$fdesc\", $freq, $fdefaultStr)"
                }
                "listOf(\n$fieldLines\n            )"
            }

            """        ExposedActionDoc(
            |            actionName = "$actionName",
            |            summary = "$summary",
            |            payloadFields = $fieldsStr
            |        )""".trimMargin()
        }

        val exposedActionsContent = """
            |package app.auf.core.generated
            |
            |/**
            | * THIS IS A GENERATED FILE. DO NOT EDIT.
            | * Contains the compile-time registry of actions that agents are permitted to invoke,
            | * along with sandboxing rules and documentation for prompt injection.
            | * Generated from the 'exposedToAgents' arrays in *.actions.json manifests.
            | */
            |object ExposedActions {
            |
            |    val allowedActionNames: Set<String> = setOf(
            |$allowedSetStr
            |    )
            |
            |    data class SandboxRule(
            |        val strategy: String,
            |        val subpathPrefixTemplate: String,
            |        val payloadRewrites: Map<String, String> = emptyMap()
            |    )
            |
            |    val sandboxRules: Map<String, SandboxRule> = mapOf(
            |$sandboxRuleStr
            |    )
            |
            |    data class ExposedActionDoc(
            |        val actionName: String,
            |        val summary: String,
            |        val payloadFields: List<PayloadField>
            |    )
            |
            |    data class PayloadField(
            |        val name: String,
            |        val type: String,
            |        val description: String,
            |        val required: Boolean,
            |        val default: String? = null
            |    )
            |
            |    val documentation: List<ExposedActionDoc> = listOf(
            |$docStr
            |    )
            |}
        """.trimMargin()

        val exposedActionsOut = exposedActionsOutputFile.get().asFile
        exposedActionsOut.parentFile.mkdirs()
        exposedActionsOut.writeText(exposedActionsContent)

        println("Generated ActionNames.kt with ${actionNames.size} actions and ${envelopeTypes.size} envelope types.")
        println("Generated ExposedActions.kt with ${exposedActionNames.size} exposed agent actions and ${sandboxRules.size} sandbox rules.")
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

    // This ensures our files are generated before any compilation happens.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        dependsOn(tasks.getByName("generateActionRegistry"))
    }

    sourceSets {
        // This tells the compiler to include our generated files in the build.
        commonMain.get().kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))

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