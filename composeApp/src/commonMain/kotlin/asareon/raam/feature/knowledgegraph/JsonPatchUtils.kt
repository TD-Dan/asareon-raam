package asareon.raam.feature.knowledgegraph

import kotlinx.serialization.json.*

/**
 * Represents a single validated patch operation.
 */
data class PatchOperation(
    val op: String,
    val path: List<String>,  // parsed path segments (without leading empty segment)
    val value: JsonElement? = null
)

/**
 * The set of header paths that are structurally protected and cannot be modified by PATCH_HOLON.
 * Writes to these paths are rejected during validation.
 */
private val PROTECTED_HEADER_FIELDS = setOf("id", "filePath", "parentId", "depth", "sub_holons", "created_at")

/**
 * The set of header fields that agents ARE allowed to modify via PATCH_HOLON.
 */
private val WRITABLE_HEADER_FIELDS = setOf("name", "summary", "version", "type", "relationships", "modified_at")

/**
 * Parses a JSON Pointer string (RFC 6901) into a list of unescaped path segments.
 *
 * @param pointer The JSON Pointer string (e.g., "/payload/state/project_status")
 * @return A list of unescaped path segments
 * @throws IllegalArgumentException if the pointer is malformed
 */
fun parseJsonPointer(pointer: String): List<String> {
    if (pointer.isEmpty()) return emptyList()
    if (!pointer.startsWith("/")) {
        throw IllegalArgumentException("JSON Pointer must start with '/'. Received: '$pointer'")
    }
    return pointer.substring(1).split("/").map { segment ->
        segment.replace("~1", "/").replace("~0", "~")
    }
}

/**
 * Resolves a JSON Pointer path to retrieve the value at that location in a JsonElement tree.
 *
 * @param root The root JsonElement to traverse
 * @param segments The parsed path segments
 * @return The JsonElement at the path, or null if the path does not exist
 */
fun resolveJsonPointer(root: JsonElement, segments: List<String>): JsonElement? {
    var current: JsonElement = root
    for (segment in segments) {
        when (current) {
            is JsonObject -> {
                current = current[segment] ?: return null
            }
            is JsonArray -> {
                val index = segment.toIntOrNull() ?: return null
                if (index < 0 || index >= current.size) return null
                current = current[index]
            }
            else -> return null
        }
    }
    return current
}

/**
 * Applies a single patch operation to a JsonElement tree, returning a new tree with the modification.
 * JsonElement is immutable in kotlinx.serialization, so this performs a copy-on-write rebuild.
 *
 * @param root The root JsonElement to modify
 * @param segments The parsed path segments pointing to the target location
 * @param op The operation type: "replace", "add", or "remove"
 * @param value The value to set (required for "replace" and "add")
 * @return A new JsonElement tree with the modification applied
 * @throws IllegalArgumentException if the operation cannot be applied
 */
fun applyPatchOp(root: JsonElement, segments: List<String>, op: String, value: JsonElement?): JsonElement {
    if (segments.isEmpty()) {
        // Operating on the root itself
        return when (op) {
            "replace", "add" -> value ?: throw IllegalArgumentException("Value required for '$op' operation.")
            "remove" -> throw IllegalArgumentException("Cannot remove the root element.")
            else -> throw IllegalArgumentException("Unknown op: '$op'")
        }
    }

    val firstSegment = segments.first()
    val remainingSegments = segments.drop(1)

    return when (root) {
        is JsonObject -> {
            if (remainingSegments.isEmpty()) {
                // We're at the parent — apply the operation here
                when (op) {
                    "replace" -> {
                        if (!root.containsKey(firstSegment)) {
                            throw IllegalArgumentException("Cannot replace: key '$firstSegment' does not exist.")
                        }
                        JsonObject(root.toMutableMap().apply {
                            put(firstSegment, value ?: throw IllegalArgumentException("Value required for 'replace'."))
                        })
                    }
                    "add" -> {
                        JsonObject(root.toMutableMap().apply {
                            put(firstSegment, value ?: throw IllegalArgumentException("Value required for 'add'."))
                        })
                    }
                    "remove" -> {
                        if (!root.containsKey(firstSegment)) {
                            throw IllegalArgumentException("Cannot remove: key '$firstSegment' does not exist.")
                        }
                        JsonObject(root.toMutableMap().apply { remove(firstSegment) })
                    }
                    else -> throw IllegalArgumentException("Unknown op: '$op'")
                }
            } else {
                // Recurse deeper
                val child = root[firstSegment]
                    ?: throw IllegalArgumentException("Path segment '$firstSegment' does not exist in object.")
                val modifiedChild = applyPatchOp(child, remainingSegments, op, value)
                JsonObject(root.toMutableMap().apply { put(firstSegment, modifiedChild) })
            }
        }
        is JsonArray -> {
            if (firstSegment == "-" && remainingSegments.isEmpty() && op == "add") {
                // Special case: append to array
                JsonArray(root.toMutableList().apply {
                    add(value ?: throw IllegalArgumentException("Value required for 'add'."))
                })
            } else {
                val index = firstSegment.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid array index: '$firstSegment'")
                if (index < 0 || index >= root.size) {
                    throw IllegalArgumentException("Array index $index out of bounds (size: ${root.size}).")
                }
                if (remainingSegments.isEmpty()) {
                    when (op) {
                        "replace" -> {
                            JsonArray(root.toMutableList().apply {
                                set(index, value ?: throw IllegalArgumentException("Value required for 'replace'."))
                            })
                        }
                        "add" -> {
                            JsonArray(root.toMutableList().apply {
                                add(index, value ?: throw IllegalArgumentException("Value required for 'add'."))
                            })
                        }
                        "remove" -> {
                            JsonArray(root.toMutableList().apply { removeAt(index) })
                        }
                        else -> throw IllegalArgumentException("Unknown op: '$op'")
                    }
                } else {
                    val child = root[index]
                    val modifiedChild = applyPatchOp(child, remainingSegments, op, value)
                    JsonArray(root.toMutableList().apply { set(index, modifiedChild) })
                }
            }
        }
        else -> throw IllegalArgumentException("Cannot traverse into primitive at segment '$firstSegment'.")
    }
}

/**
 * Validates and parses a list of raw patch operation JSON objects into PatchOperation instances.
 * Performs all validation checks and returns either a list of validated operations or a list of errors.
 *
 * Validation checks per operation:
 * 1. 'op' must be one of: replace, add, remove
 * 2. 'path' must be a valid JSON Pointer starting with '/'
 * 3. 'value' must be present for replace/add, absent for remove
 * 4. Path must not target a protected header field
 * 5. For 'replace': the target path must exist in the holon
 *
 * @param operations The raw JSON array of operation objects from the action payload
 * @param holonRoot The full holon as a JsonObject (header + payload + execute) for path existence checks
 * @return A Pair: first is the list of validated PatchOperations (empty if errors), second is the list of error strings
 */
fun validatePatchOperations(
    operations: JsonArray,
    holonRoot: JsonObject
): Pair<List<PatchOperation>, List<String>> {
    val validated = mutableListOf<PatchOperation>()
    val errors = mutableListOf<String>()

    operations.forEachIndexed { index, element ->
        val opObj = element as? JsonObject
        if (opObj == null) {
            errors.add("Operation [$index]: Not a valid JSON object.")
            return@forEachIndexed
        }

        val opStr = opObj["op"]?.jsonPrimitive?.contentOrNull
        val pathStr = opObj["path"]?.jsonPrimitive?.contentOrNull
        val valueElement = opObj["value"]

        // 1. Validate op
        if (opStr == null || opStr !in listOf("replace", "add", "remove")) {
            errors.add("Operation [$index]: 'op' must be one of: replace, add, remove. Got: '$opStr'.")
            return@forEachIndexed
        }

        // 2. Validate path
        if (pathStr == null) {
            errors.add("Operation [$index]: Missing required 'path' field.")
            return@forEachIndexed
        }

        val segments: List<String>
        try {
            segments = parseJsonPointer(pathStr)
        } catch (e: IllegalArgumentException) {
            errors.add("Operation [$index]: Invalid path: ${e.message}")
            return@forEachIndexed
        }

        if (segments.isEmpty()) {
            errors.add("Operation [$index]: Cannot operate on the root path '/'.")
            return@forEachIndexed
        }

        // 3. Validate value presence
        if (opStr in listOf("replace", "add") && valueElement == null) {
            errors.add("Operation [$index]: '$opStr' requires a 'value' field.")
            return@forEachIndexed
        }
        if (opStr == "remove" && valueElement != null) {
            errors.add("Operation [$index]: 'remove' must not include a 'value' field.")
            return@forEachIndexed
        }

        // 4. Check protected paths
        if (segments.first() == "header" && segments.size >= 2) {
            val headerField = segments[1]
            if (headerField in PROTECTED_HEADER_FIELDS) {
                errors.add("Operation [$index]: Path '$pathStr' targets protected field 'header.$headerField'. This field cannot be modified.")
                return@forEachIndexed
            }
        }

        // 5. For 'replace': verify the target path exists
        if (opStr == "replace") {
            if (resolveJsonPointer(holonRoot, segments) == null) {
                errors.add("Operation [$index]: Cannot replace — path '$pathStr' does not exist in the holon.")
                return@forEachIndexed
            }
        }

        // 6. For 'add' with array append: verify parent exists and is an array
        if (opStr == "add" && segments.last() == "-") {
            val parentSegments = segments.dropLast(1)
            val parent = resolveJsonPointer(holonRoot, parentSegments)
            if (parent == null) {
                errors.add("Operation [$index]: Cannot append — parent path '${parentSegments.joinToString("/", "/")}' does not exist.")
                return@forEachIndexed
            }
            if (parent !is JsonArray) {
                errors.add("Operation [$index]: Cannot append with '/-' — target is not an array.")
                return@forEachIndexed
            }
        }

        validated.add(PatchOperation(opStr, segments, valueElement))
    }

    return if (errors.isNotEmpty()) {
        Pair(emptyList(), errors)
    } else {
        Pair(validated, emptyList())
    }
}

/**
 * Builds a JsonObject representation of a Holon suitable for JSON Pointer traversal.
 * This combines header, payload, and execute into a single navigable tree.
 *
 * @param holon The holon to convert
 * @param json The Json instance for serialization
 * @return A JsonObject with keys: "header", "payload", and optionally "execute"
 */
fun buildHolonJsonTree(holon: Holon, json: Json): JsonObject {
    return buildJsonObject {
        put("header", json.encodeToJsonElement(HolonHeader.serializer(), holon.header))
        put("payload", holon.payload)
        holon.execute?.let { put("execute", it) }
    }
}

/**
 * Extracts the modified header, payload, and execute from a patched JsonObject tree
 * back into Holon components.
 *
 * @param patchedTree The modified JsonObject with "header", "payload", "execute" keys
 * @param originalHolon The original holon (for preserving protected fields)
 * @param json The Json instance for deserialization
 * @return A Triple of (updatedHeader, updatedPayload, updatedExecute)
 */
fun extractPatchedComponents(
    patchedTree: JsonObject,
    originalHolon: Holon,
    json: Json
): Triple<HolonHeader, JsonElement, JsonElement?> {
    // Deserialize the potentially modified header, then re-apply protected fields from original
    val patchedHeaderJson = patchedTree["header"] as? JsonObject
        ?: throw IllegalStateException("Patched tree missing 'header' object.")
    val patchedHeader = json.decodeFromJsonElement(HolonHeader.serializer(), patchedHeaderJson)

    // Force-preserve all protected structural fields from the original
    val safeHeader = patchedHeader.copy(
        id = originalHolon.header.id,
        filePath = originalHolon.header.filePath,
        parentId = originalHolon.header.parentId,
        depth = originalHolon.header.depth,
        subHolons = originalHolon.header.subHolons,
        createdAt = originalHolon.header.createdAt
    )

    val patchedPayload = patchedTree["payload"]
        ?: throw IllegalStateException("Patched tree missing 'payload'.")
    val patchedExecute = patchedTree["execute"]

    return Triple(safeHeader, patchedPayload, patchedExecute)
}