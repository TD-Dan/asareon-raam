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


// ============================================================================
// Phase 1 — New generateActionRegistry Gradle task
// ============================================================================
// Drop-in replacement for the old task in build.gradle.kts (lines 29–346).
//
// Reads the unified *.actions.json format (actions[] with public/broadcast/targeted)
// and generates two files:
//   1. ActionRegistry.kt — the unified registry (single source of truth)
//   2. ActionNames.kt — typealias + old→new name compat shim
//
// IMPORTANT: No data classes inside doLast{} — Gradle script closures don't
// support them reliably. Uses plain Map<String, Any> throughout, matching the
// pattern of the original working task.
// ============================================================================

tasks.register("generateActionRegistry") {
    description = "Generates ActionRegistry.kt (unified action catalog) and ActionNames.kt (compat shim) from *.actions.json manifests."
    group = "auf"

    // --- Configuration ---
    val inputDir = file("src/commonMain/kotlin/app/auf")
    val generatedDir = layout.buildDirectory.dir("generated/kotlin/app/auf/core/generated")
    val actionRegistryOutputFile = generatedDir.map { it.file("ActionRegistry.kt") }
    val actionNamesOutputFile = generatedDir.map { it.file("ActionNames.kt") }

    // --- Gradle Inputs/Outputs ---
    inputs.dir(inputDir)
    outputs.file(actionRegistryOutputFile)
    outputs.file(actionNamesOutputFile)

    // --- Task Action ---
    doLast {
        val json = Json { ignoreUnknownKeys = true }

        // Plain-map collections only — no data classes in Gradle doLast blocks!
        // featureMap: featureName → { "name", "summary", "permissions", "actions": List<Map> }
        val featureMap = mutableMapOf<String, MutableMap<String, Any>>()

        // Helper to escape strings for Kotlin source
        fun String.escKt() = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        // Helper to convert action name → Kotlin const name
        fun toConstName(actionName: String): String =
            actionName.replace('.', '_').replace('-', '_').uppercase()

        // ====== Parse all unified manifests ======
        inputDir.walkTopDown().forEach { manifestFile ->
            if (manifestFile.isFile && manifestFile.name.endsWith(".actions.json")) {
                try {
                    val content = manifestFile.readText()
                    val manifest = json.parseToJsonElement(content).jsonObject

                    val featureName = manifest["feature_name"]?.jsonPrimitive?.content
                        ?: throw GradleException("Missing feature_name in ${manifestFile.path}")
                    val featureSummary = manifest["summary"]?.jsonPrimitive?.content ?: ""
                    val permissions = (manifest["permissions"] as? JsonArray)
                        ?.map { it.jsonPrimitive.content } ?: emptyList<String>()

                    val featureData = featureMap.getOrPut(featureName) {
                        mutableMapOf(
                            "name" to featureName,
                            "summary" to featureSummary,
                            "permissions" to permissions,
                            "actions" to mutableListOf<Map<String, Any?>>()
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    val actionsList = featureData["actions"] as MutableList<Map<String, Any?>>

                    val actionsArray = manifest["actions"] as? JsonArray
                        ?: throw GradleException("Missing 'actions' array in ${manifestFile.path}. Has this manifest been migrated to the unified format?")

                    for (i in 0 until actionsArray.size) {
                        val obj = actionsArray[i] as? JsonObject ?: continue
                        val actionName = obj["action_name"]?.jsonPrimitive?.content ?: continue
                        val summary = obj["summary"]?.jsonPrimitive?.content ?: ""
                        val publicFlag = obj["public"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        val broadcastFlag = obj["broadcast"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        val targetedFlag = obj["targeted"]?.jsonPrimitive?.content?.toBoolean() ?: false

                        // Validate: targeted + broadcast is forbidden
                        if (targetedFlag && broadcastFlag) {
                            throw GradleException(
                                "Action '$actionName' in ${manifestFile.path} has both targeted=true and broadcast=true. These are mutually exclusive."
                            )
                        }

                        val actionFeature = actionName.substringBefore('.')
                        val suffix = actionName.substringAfter('.')

                        // Parse payload schema fields → List<Map<String, Any>>
                        val payloadFields = mutableListOf<Map<String, Any>>()
                        val requiredFields = mutableListOf<String>()
                        val schema = obj["payload_schema"] as? JsonObject
                        if (schema != null) {
                            val requiredArr = schema["required"] as? JsonArray
                            if (requiredArr != null) {
                                for (j in 0 until requiredArr.size) {
                                    requiredFields.add(requiredArr[j].jsonPrimitive.content)
                                }
                            }
                            val properties = schema["properties"]?.jsonObject
                            if (properties != null) {
                                for (fieldName in properties.keys) {
                                    val fieldObj = properties[fieldName]!!.jsonObject
                                    val fieldType = fieldObj["type"]?.let { typeEl ->
                                        when (typeEl) {
                                            is JsonPrimitive -> typeEl.content
                                            is JsonArray -> typeEl.joinToString("/") { it.jsonPrimitive.content }
                                            else -> "string"
                                        }
                                    } ?: "string"
                                    val fieldMap = mutableMapOf<String, Any>(
                                        "name" to fieldName,
                                        "type" to fieldType,
                                        "description" to (fieldObj["description"]?.jsonPrimitive?.content ?: ""),
                                        "required" to (fieldName in requiredFields)
                                    )
                                    val defaultVal = fieldObj["default"]?.toString()
                                    if (defaultVal != null) {
                                        fieldMap["default"] = defaultVal
                                    }
                                    payloadFields.add(fieldMap)
                                }
                            }
                        }

                        // Parse agent_exposure → Map<String, Any>?
                        val agentExposureObj = obj["agent_exposure"] as? JsonObject
                        val agentExposure: Map<String, Any>? = if (agentExposureObj != null) {
                            val aeMap = mutableMapOf<String, Any>()
                            aeMap["requiresApproval"] = agentExposureObj["requires_approval"]?.jsonPrimitive?.content?.toBoolean() ?: false

                            val autoFillObj = agentExposureObj["auto_fill_rules"] as? JsonObject
                            if (autoFillObj != null) {
                                val fills = mutableMapOf<String, String>()
                                for (key in autoFillObj.keys) {
                                    fills[key] = autoFillObj[key]!!.jsonPrimitive.content
                                }
                                aeMap["autoFillRules"] = fills
                            } else {
                                aeMap["autoFillRules"] = emptyMap<String, String>()
                            }

                            val sandboxObj = agentExposureObj["sandbox_rule"] as? JsonObject
                            if (sandboxObj != null) {
                                val srMap = mutableMapOf<String, Any>()
                                srMap["strategy"] = sandboxObj["strategy"]?.jsonPrimitive?.content ?: ""
                                srMap["pathPrefixTemplate"] = sandboxObj["path_prefix_template"]?.jsonPrimitive?.content ?: ""
                                val rewriteObj = sandboxObj["payload_rewrites"] as? JsonObject
                                val rewrites = mutableMapOf<String, String>()
                                if (rewriteObj != null) {
                                    for (key in rewriteObj.keys) {
                                        rewrites[key] = rewriteObj[key]!!.jsonPrimitive.content
                                    }
                                }
                                srMap["payloadRewrites"] = rewrites
                                aeMap["sandboxRule"] = srMap
                            }

                            aeMap
                        } else null

                        // Parse required_permissions (future hook)
                        val reqPerms = (obj["required_permissions"] as? JsonArray)
                            ?.map { it.jsonPrimitive.content }

                        actionsList.add(mapOf(
                            "fullName" to actionName,
                            "featureName" to actionFeature,
                            "suffix" to suffix,
                            "summary" to summary,
                            "public" to publicFlag,
                            "broadcast" to broadcastFlag,
                            "targeted" to targetedFlag,
                            "payloadFields" to payloadFields,
                            "requiredFields" to requiredFields,
                            "agentExposure" to agentExposure,
                            "requiredPermissions" to reqPerms
                        ))
                    }
                } catch (e: Exception) {
                    if (e is GradleException) throw e
                    throw GradleException("Failed to parse action manifest: ${manifestFile.path}. Error: ${e.message}", e)
                }
            }
        }

        // ====== Collect all actions sorted ======
        val allActions = featureMap.values.flatMap { feature ->
            @Suppress("UNCHECKED_CAST")
            feature["actions"] as List<Map<String, Any?>>
        }.sortedBy { it["fullName"] as String }

        // ============================================================
        // Generate ActionRegistry.kt
        // ============================================================

        // Section 1: Name constants
        val nameConstants = allActions.joinToString("\n") { action ->
            val constName = toConstName(action["fullName"] as String)
            "        const val $constName = \"${action["fullName"]}\""
        }

        val allNamesSet = allActions.joinToString(",\n") { action ->
            val constName = toConstName(action["fullName"] as String)
            "            $constName"
        }

        // Section 3: Feature descriptors
        val sortedFeatures = featureMap.values.sortedBy { it["name"] as String }
        val featureEntries = sortedFeatures.joinToString(",\n") { feature ->
            @Suppress("UNCHECKED_CAST")
            val actions = (feature["actions"] as List<Map<String, Any?>>).sortedBy { it["suffix"] as String }

            val actionEntries = actions.joinToString(",\n") { action ->
                @Suppress("UNCHECKED_CAST")
                val fields = action["payloadFields"] as List<Map<String, Any>>
                @Suppress("UNCHECKED_CAST")
                val reqFields = action["requiredFields"] as List<String>

                // PayloadFields
                val fieldsStr = if (fields.isEmpty()) "emptyList()"
                else {
                    val fieldLines = fields.joinToString(",\n") { f ->
                        val defVal = f["default"]
                        val defStr = if (defVal != null) "\"${(defVal as String).escKt()}\"" else "null"
                        "                        PayloadField(\"${(f["name"] as String)}\", \"${(f["type"] as String).escKt()}\", \"${(f["description"] as String).escKt()}\", ${f["required"]}, $defStr)"
                    }
                    "listOf(\n$fieldLines\n                    )"
                }

                val reqFieldsStr = if (reqFields.isEmpty()) "emptyList()"
                else "listOf(${reqFields.joinToString(", ") { "\"$it\"" }})"

                // AgentExposure
                @Suppress("UNCHECKED_CAST")
                val ae = action["agentExposure"] as? Map<String, Any>
                val agentStr = if (ae == null) "null"
                else {
                    @Suppress("UNCHECKED_CAST")
                    val autoFills = ae["autoFillRules"] as? Map<String, String> ?: emptyMap()
                    val autoFillStr = if (autoFills.isEmpty()) "emptyMap()"
                    else "mapOf(${autoFills.entries.joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }})"

                    @Suppress("UNCHECKED_CAST")
                    val sr = ae["sandboxRule"] as? Map<String, Any>
                    val sandboxStr = if (sr == null) "null"
                    else {
                        @Suppress("UNCHECKED_CAST")
                        val rw = sr["payloadRewrites"] as? Map<String, String> ?: emptyMap()
                        val rwStr = if (rw.isEmpty()) "emptyMap()"
                        else "mapOf(${rw.entries.joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }})"
                        "SandboxRule(\n                        strategy = \"${sr["strategy"]}\",\n                        pathPrefixTemplate = \"${sr["pathPrefixTemplate"]}\",\n                        payloadRewrites = $rwStr\n                    )"
                    }
                    "AgentExposure(\n                    requiresApproval = ${ae["requiresApproval"]},\n                    autoFillRules = $autoFillStr,\n                    sandboxRule = $sandboxStr\n                )"
                }

                @Suppress("UNCHECKED_CAST")
                val reqPerms = action["requiredPermissions"] as? List<String>
                val reqPermsStr = if (reqPerms == null) "null"
                else "listOf(${reqPerms.joinToString(", ") { "\"$it\"" }})"

                """                "${action["suffix"]}" to ActionDescriptor(
                |                    fullName = "${action["fullName"]}",
                |                    featureName = "${action["featureName"]}",
                |                    suffix = "${action["suffix"]}",
                |                    summary = "${(action["summary"] as String).escKt()}",
                |                    public = ${action["public"]},
                |                    broadcast = ${action["broadcast"]},
                |                    targeted = ${action["targeted"]},
                |                    payloadFields = $fieldsStr,
                |                    requiredFields = $reqFieldsStr,
                |                    agentExposure = $agentStr,
                |                    requiredPermissions = $reqPermsStr
                |                )""".trimMargin()
            }

            @Suppress("UNCHECKED_CAST")
            val perms = feature["permissions"] as? List<String> ?: emptyList()
            val permsStr = if (perms.isEmpty()) "emptyList()"
            else "listOf(${perms.joinToString(", ") { "\"$it\"" }})"

            """        "${feature["name"]}" to FeatureDescriptor(
            |            name = "${feature["name"]}",
            |            summary = "${(feature["summary"] as String).escKt()}",
            |            permissions = $permsStr,
            |            actions = mapOf(
            |$actionEntries
            |            )
            |        )""".trimMargin()
        }

        val actionRegistryContent = """
            |package app.auf.core.generated
            |
            |/**
            | * THIS IS A GENERATED FILE. DO NOT EDIT.
            | *
            | * The unified action registry — single source of truth for every action in the system.
            | * Generated from the 'actions[]' arrays in *.actions.json manifests.
            | */
            |object ActionRegistry {
            |
            |    // ================================================================
            |    // Section 1: Compile-Time Constants
            |    // ================================================================
            |    object Names {
            |$nameConstants
            |
            |        val allActionNames: Set<String> = setOf(
            |$allNamesSet
            |        )
            |    }
            |
            |    // ================================================================
            |    // Section 2: Descriptor Data Classes
            |    // ================================================================
            |    data class PayloadField(
            |        val name: String,
            |        val type: String,
            |        val description: String,
            |        val required: Boolean,
            |        val default: String? = null
            |    )
            |
            |    data class SandboxRule(
            |        val strategy: String,
            |        val pathPrefixTemplate: String,
            |        val payloadRewrites: Map<String, String> = emptyMap()
            |    )
            |
            |    data class AgentExposure(
            |        val requiresApproval: Boolean = false,
            |        val autoFillRules: Map<String, String> = emptyMap(),
            |        val sandboxRule: SandboxRule? = null
            |    )
            |
            |    data class ActionDescriptor(
            |        val fullName: String,
            |        val featureName: String,
            |        val suffix: String,
            |        val summary: String,
            |        val public: Boolean,
            |        val broadcast: Boolean,
            |        val targeted: Boolean,
            |        val payloadFields: List<PayloadField>,
            |        val requiredFields: List<String>,
            |        val agentExposure: AgentExposure?,
            |        val requiredPermissions: List<String>? = null
            |    ) {
            |        /** A Command is any action public to all originators. */
            |        val isCommand: Boolean get() = public
            |        /** An Event is a restricted-origin broadcast (feature announces something happened). */
            |        val isEvent: Boolean get() = !public && broadcast
            |        /** An Internal action is restricted to the owning feature only. */
            |        val isInternal: Boolean get() = !public && !broadcast && !targeted
            |        /** A Response is a restricted-origin targeted delivery (reply to a requester). */
            |        val isResponse: Boolean get() = !public && targeted
            |    }
            |
            |    data class FeatureDescriptor(
            |        val name: String,
            |        val summary: String,
            |        val permissions: List<String>,
            |        val actions: Map<String, ActionDescriptor>
            |    )
            |
            |    // ================================================================
            |    // Section 3: Feature Registry (generated from manifests)
            |    // ================================================================
            |    val features: Map<String, FeatureDescriptor> = mapOf(
            |$featureEntries
            |    )
            |
            |    // ================================================================
            |    // Section 4: Derived Views
            |    // ================================================================
            |
            |    /** All action descriptors keyed by full action name (e.g., "session.POST"). */
            |    val byActionName: Map<String, ActionDescriptor> = features.values
            |        .flatMap { it.actions.values }.associateBy { it.fullName }
            |
            |    /** Action names that agents are permitted to invoke. */
            |    val agentAllowedNames: Set<String> = byActionName.values
            |        .filter { it.agentExposure != null }.map { it.fullName }.toSet()
            |
            |    /** Actions that require human approval before agent execution. */
            |    val agentRequiresApproval: Set<String> = byActionName.values
            |        .filter { it.agentExposure?.requiresApproval == true }.map { it.fullName }.toSet()
            |
            |    /** Auto-fill rules for agent actions (field name → template). */
            |    val agentAutoFillRules: Map<String, Map<String, String>> = byActionName.values
            |        .filter { it.agentExposure?.autoFillRules?.isNotEmpty() == true }
            |        .associate { it.fullName to it.agentExposure!!.autoFillRules }
            |
            |    /** Sandbox rules for agent actions. */
            |    val agentSandboxRules: Map<String, SandboxRule> = byActionName.values
            |        .mapNotNull { d -> d.agentExposure?.sandboxRule?.let { d.fullName to it } }.toMap()
            |}
        """.trimMargin()

        val registryOut = actionRegistryOutputFile.get().asFile
        registryOut.parentFile.mkdirs()
        registryOut.writeText(actionRegistryContent)

        // Summary
        println("Generated ActionRegistry.kt with ${allActions.size} actions across ${featureMap.size} features.")
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
        // TEMPORARY DISABLING OF NON COMPILING TESTS:
        named("commonTest") {
            //kotlin.exclude("**/feature/agent/**")
            //kotlin.exclude("**/feature/commandbot/**")
            //kotlin.exclude("**/feature/core/**")
            //kotlin.exclude("**/feature/filesystem/**")
            //kotlin.exclude("**/feature/gateway/**")
            //kotlin.exclude("**/feature/knowledgegraph/**")
            //kotlin.exclude("**/feature/session/**")
            //kotlin.exclude("**/feature/settings/**")
            kotlin.exclude("**/ui/**")
        }
        named("jvmTest") {
            kotlin.exclude(
                //      "**"
            )
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