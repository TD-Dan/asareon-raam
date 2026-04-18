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
// generateActionRegistry Gradle task
// ============================================================================
// Reads the unified *.actions.json format and generates ActionRegistry.kt.
// ============================================================================

tasks.register("generateActionRegistry") {
    description = "Generates ActionRegistry.kt (unified action catalog) and ActionNames.kt (compat shim) from *.actions.json manifests."
    group = "raam"

    // --- Configuration ---
    val inputDir = file("src/commonMain/kotlin/asareon/raam")
    val generatedDir = layout.buildDirectory.dir("generated/kotlin/asareon/raam/core/generated")
    val actionRegistryOutputFile = generatedDir.map { it.file("ActionRegistry.kt") }
    val actionNamesOutputFile = generatedDir.map { it.file("ActionNames.kt") }

    // --- Gradle Inputs/Outputs ---
    inputs.dir(inputDir)
    outputs.file(actionRegistryOutputFile)
    outputs.file(actionNamesOutputFile)

    // --- Task Action ---
    doLast {
        val json = Json { ignoreUnknownKeys = true }

        // Valid dangerLevel values
        val validDangerLevels = setOf("LOW", "CAUTION", "DANGER")

        // Plain-map collections only — no data classes in Gradle doLast blocks!
        // featureMap: featureName → { "name", "summary", "permissionDeclarations", "actions": List<Map> }
        val featureMap = mutableMapOf<String, MutableMap<String, Any>>()

        // Collect all declared permission keys across all features for cross-referencing
        // Map: permissionKey → { key, description, dangerLevel, featureName }
        val allDeclaredPermissions = mutableMapOf<String, Map<String, String>>()

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

                    // ====== Parse permissions declarations ======

                    val permissionsArray = manifest["permissions"] as? JsonArray
                    val permissionDeclarations = mutableListOf<Map<String, String>>()

                    if (permissionsArray != null) {
                        for (permEl in permissionsArray) {
                            if (permEl is JsonObject) {
                                // New format: { key, description, dangerLevel }
                                val key = permEl["key"]?.jsonPrimitive?.content
                                    ?: throw GradleException("Permission declaration missing 'key' in ${manifestFile.path}")
                                val description = permEl["description"]?.jsonPrimitive?.content ?: ""
                                val dangerLevel = permEl["dangerLevel"]?.jsonPrimitive?.content ?: "LOW"

                                // Validate dangerLevel
                                if (dangerLevel !in validDangerLevels) {
                                    throw GradleException(
                                        "Invalid dangerLevel '$dangerLevel' for permission '$key' in ${manifestFile.path}. " +
                                                "Must be one of: ${validDangerLevels.joinToString(", ")}"
                                    )
                                }

                                // Validate colon format: exactly one colon
                                val colonCount = key.count { it == ':' }
                                if (colonCount != 1) {
                                    throw GradleException(
                                        "Permission key '$key' in ${manifestFile.path} must contain exactly one colon. " +
                                                "Format: '<domain>:<capability>'. Found $colonCount colon(s)."
                                    )
                                }

                                // Validate key length
                                if (key.length > 64) {
                                    throw GradleException(
                                        "Permission key '$key' in ${manifestFile.path} exceeds 64 characters (${key.length})."
                                    )
                                }

                                val decl = mapOf(
                                    "key" to key,
                                    "description" to description,
                                    "dangerLevel" to dangerLevel,
                                    "featureName" to featureName
                                )
                                permissionDeclarations.add(decl)
                                allDeclaredPermissions[key] = decl
                            } else if (permEl is JsonPrimitive) {
                                // Legacy format: plain string — backward compat, no dangerLevel
                                val key = permEl.content
                                val decl = mapOf(
                                    "key" to key,
                                    "description" to "",
                                    "dangerLevel" to "LOW",
                                    "featureName" to featureName
                                )
                                permissionDeclarations.add(decl)
                                allDeclaredPermissions[key] = decl
                            }
                        }
                    }

                    // Legacy permissions list (simple strings) for backward compat
                    val legacyPermissions = permissionDeclarations.map { it["key"]!! }

                    val featureData = featureMap.getOrPut(featureName) {
                        mutableMapOf(
                            "name" to featureName,
                            "summary" to featureSummary,
                            "permissions" to legacyPermissions,
                            "permissionDeclarations" to permissionDeclarations,
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
                        val hiddenFlag = obj["hidden"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        val responseFlag = obj["response"]?.jsonPrimitive?.content?.toBoolean() ?: false

                        // Validate: targeted + broadcast is forbidden
                        if (targetedFlag && broadcastFlag) {
                            throw GradleException(
                                "Action '$actionName' in ${manifestFile.path} has both targeted=true and broadcast=true. These are mutually exclusive."
                            )
                        }

                        // Validate: hidden is only meaningful on public actions
                        if (hiddenFlag && !publicFlag) {
                            throw GradleException(
                                "Action '$actionName' in ${manifestFile.path} has hidden=true but public=false. " +
                                        "Hidden is only meaningful on public actions (non-public actions are already feature-restricted)."
                            )
                        }

                        // Validate: response + broadcast is nonsense — responses complete a specific request
                        if (responseFlag && broadcastFlag) {
                            throw GradleException(
                                "Action '$actionName' in ${manifestFile.path} has both response=true and broadcast=true. " +
                                        "A response completes a specific request — it can't also be a broadcast."
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

                        // Parse required_permissions
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
                            "hidden" to hiddenFlag,
                            "response" to responseFlag,
                            "payloadFields" to payloadFields,
                            "requiredFields" to requiredFields,
                            "requiredPermissions" to reqPerms
                        ))
                    }
                } catch (e: Exception) {
                    if (e is GradleException) throw e
                    throw GradleException("Failed to parse action manifest: ${manifestFile.path}. Error: ${e.message}", e)
                }
            }
        }

        // ====== Cross-reference validation: required_permissions vs declared keys ======
        val allActions = featureMap.values.flatMap { feature ->
            @Suppress("UNCHECKED_CAST")
            feature["actions"] as List<Map<String, Any?>>
        }.sortedBy { it["fullName"] as String }

        for (action in allActions) {
            @Suppress("UNCHECKED_CAST")
            val reqPerms = action["requiredPermissions"] as? List<String> ?: continue
            for (permKey in reqPerms) {
                if (permKey !in allDeclaredPermissions) {
                    throw GradleException(
                        "Action '${action["fullName"]}' requires permission '$permKey' which is not declared " +
                                "in any feature's 'permissions' array. Declare it in the appropriate *.actions.json manifest."
                    )
                }
            }
        }

        // ====== Enforce required_permissions on public actions ======
        // Public actions MUST declare required_permissions explicitly because any originator
        // (including non-trusted user/agent identities) can dispatch them. Non-public actions
        // are restricted to the owning feature (Step 2 authorization), and feature identities
        // are trusted (uuid == null, skip permission check) — so requiring the field on them
        // would be pointless ceremony.
        for (action in allActions) {
            val isPublic = action["public"] as Boolean
            val reqPerms = action["requiredPermissions"]
            if (isPublic && reqPerms == null) {
                throw GradleException(
                    "Public action '${action["fullName"]}' is missing 'required_permissions' field. " +
                            "All public actions must declare required_permissions explicitly. " +
                            "Use \"required_permissions\": [] for actions that require no permissions."
                )
            }
        }

        // ============================================================
        // Generate ActionRegistry.kt
        // ============================================================

        // Section: Name constants
        val nameConstants = allActions.joinToString("\n") { action ->
            val constName = toConstName(action["fullName"] as String)
            "        const val $constName = \"${action["fullName"]}\""
        }

        val allNamesSet = allActions.joinToString(",\n") { action ->
            val constName = toConstName(action["fullName"] as String)
            "            $constName"
        }

        // Section: Feature descriptors
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

                // autoFillRules as top-level field
                @Suppress("UNCHECKED_CAST")
                val autoFills = action["autoFillRules"] as? Map<String, String> ?: emptyMap()
                val autoFillStr = if (autoFills.isEmpty()) "emptyMap()"
                else "mapOf(${autoFills.entries.joinToString(", ") { "\"${it.key}\" to \"${it.value}\"" }})"

                @Suppress("UNCHECKED_CAST")
                val reqPerms = action["requiredPermissions"] as? List<String>
                val reqPermsStr = if (reqPerms == null) "null"
                else if (reqPerms.isEmpty()) "emptyList()"
                else "listOf(${reqPerms.joinToString(", ") { "\"$it\"" }})"

                """                "${action["suffix"]}" to ActionDescriptor(
                |                    fullName = "${action["fullName"]}",
                |                    featureName = "${action["featureName"]}",
                |                    suffix = "${action["suffix"]}",
                |                    summary = "${(action["summary"] as String).escKt()}",
                |                    public = ${action["public"]},
                |                    broadcast = ${action["broadcast"]},
                |                    targeted = ${action["targeted"]},
                |                    hidden = ${action["hidden"]},
                |                    response = ${action["response"]},
                |                    payloadFields = $fieldsStr,
                |                    requiredFields = $reqFieldsStr,
                |                    autoFillRules = $autoFillStr,
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

        // Section: Permission declarations map
        val permDeclEntries = allDeclaredPermissions.entries.sortedBy { it.key }.joinToString(",\n") { (key, decl) ->
            """        "$key" to PermissionDeclaration(
            |            key = "$key",
            |            description = "${decl["description"]!!.escKt()}",
            |            dangerLevel = asareon.raam.core.DangerLevel.${decl["dangerLevel"]!!}
            |        )""".trimMargin()
        }

        val actionRegistryContent = """
            |package asareon.raam.core.generated
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
            |    data class ActionDescriptor(
            |        val fullName: String,
            |        val featureName: String,
            |        val suffix: String,
            |        val summary: String,
            |        val public: Boolean,
            |        val broadcast: Boolean,
            |        val targeted: Boolean,
            |        val hidden: Boolean = false,
            |        val response: Boolean = false,
            |        val payloadFields: List<PayloadField>,
            |        val requiredFields: List<String>,
            |        val autoFillRules: Map<String, String> = emptyMap(),
            |        val requiredPermissions: List<String>? = null
            |    ) {
            |        /** A Command is a public action the receiver is expected to acknowledge via ACTION_RESULT. Responses are not commands. */
            |        val isCommand: Boolean get() = public && !response
            |        /** An Event is a restricted-origin broadcast (feature announces something happened). */
            |        val isEvent: Boolean get() = !public && broadcast
            |        /** An Internal action is restricted to the owning feature only. */
            |        val isInternal: Boolean get() = !public && !broadcast && !targeted && !response
            |        /** A Response completes a specific prior request. Can be private+targeted (default) or explicitly declared response=true (for public response protocols). */
            |        val isResponse: Boolean get() = response || (!public && targeted)
            |        /** A hidden action is public but not discoverable by users or agents. Feature-to-feature only. */
            |        val isHiddenCommand: Boolean get() = public && hidden && !response
            |    }
            |
            |    data class FeatureDescriptor(
            |        val name: String,
            |        val summary: String,
            |        val permissions: List<String>,
            |        val actions: Map<String, ActionDescriptor>
            |    )
            |
            |    /**
            |     * A declared permission key with its description and danger level.
            |     * Generated from the "permissions" arrays in *.actions.json manifests.
            |     */
            |    data class PermissionDeclaration(
            |        val key: String,
            |        val description: String,
            |        val dangerLevel: asareon.raam.core.DangerLevel
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
            |    /** Actions visible to users and agents (excludes hidden actions). */
            |    val visibleActions: Map<String, ActionDescriptor> = byActionName.values
            |        .filter { !it.hidden }
            |        .associateBy { it.fullName }
            |
            |    /** Auto-fill rules for agent actions (field name → template). */
            |    val agentAutoFillRules: Map<String, Map<String, String>> = byActionName.values
            |        .filter { it.autoFillRules.isNotEmpty() }
            |        .associate { it.fullName to it.autoFillRules }
            |
            |    // ================================================================
            |    // Section 5: Permission Declarations (Phase 1)
            |    // ================================================================
            |
            |    /** All declared permission keys with their descriptions and danger levels. */
            |    val permissionDeclarations: Map<String, PermissionDeclaration> = mapOf(
            |$permDeclEntries
            |    )
            |}
        """.trimMargin()

        val registryOut = actionRegistryOutputFile.get().asFile
        registryOut.parentFile.mkdirs()
        registryOut.writeText(actionRegistryContent)

        // Summary
        println("Generated ActionRegistry.kt with ${allActions.size} actions across ${featureMap.size} features, ${allDeclaredPermissions.size} permission declarations.")
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
            implementation("org.luaj:luaj-jse:3.0.1")
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
            //kotlin.exclude("**/feature/agent/AgentRuntimeFeatureT2**")
            //kotlin.exclude("**/feature/agent/AgentRuntimeFeatureT3**")
            //kotlin.exclude("**/feature/commandbot/**")
            //kotlin.exclude("**/feature/core/**")
            //kotlin.exclude("**/feature/filesystem/**")
            //kotlin.exclude("**/feature/gateway/**")
            //kotlin.exclude("**/feature/knowledgegraph/**")
            //kotlin.exclude("**/feature/session/**")
            //kotlin.exclude("**/feature/settings/**")
            //kotlin.exclude("**/ui/**")
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
    namespace = "asareon.raam"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "asareon.raam"
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
        mainClass = "asareon.raam.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "asareon.raam"
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