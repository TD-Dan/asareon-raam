package asareon.raam.feature.knowledgegraph

import asareon.raam.fakes.FakePlatformDependencies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1 Unit Test for HolonOperations.
 *
 * Mandate (P-TEST-001, T1): To test the pure functions in HolonOperations
 * in complete isolation to verify their contracts.
 */
class KnowledgeGraphFeatureT1HolonOperationsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }
    private val platform = FakePlatformDependencies("test")

    // --- Test Group: normalizeHolonId ---

    @Test
    fun `normalizeHolonId should return a valid, sanitized ID`() {
        val rawId = "  My-!@#Valid-Name-123-20251112T183000Z  "
        val expected = "my-valid-name-123-20251112T183000Z"
        assertEquals(expected, normalizeHolonId(rawId))
    }

    @Test
    fun `normalizeHolonId should throw if timestamp is missing`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("just-a-name")
        }
    }

    @Test
    fun `normalizeHolonId should throw if hyphen separator is missing`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("nametimestamp20251112T183000Z")
        }
    }

    @Test
    fun `normalizeHolonId should throw if timestamp is malformed`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("name-2025-11-12T18:30:00Z")
        }
    }

    @Test
    fun `normalizeHolonId should throw if name part becomes empty after sanitization`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeHolonId("!@#$-20251112T183000Z")
        }
    }

    // --- Test Group: repairHolonId ---
    // Mechanically-fixable legacy ID shapes should repair cleanly. Irreparable shapes
    // (no timestamp at all, non-timestamp tails) must return null so the caller can
    // surface a real validation error to the user rather than silently invent data.

    @Test
    fun `repairHolonId passes through an already-canonical ID unchanged`() {
        assertEquals("foo-20250731T092500Z", repairHolonId("foo-20250731T092500Z"))
    }

    @Test
    fun `repairHolonId appends missing trailing Z`() {
        assertEquals("foo-20250905T220000Z", repairHolonId("foo-20250905T220000"))
    }

    @Test
    fun `repairHolonId pads missing seconds when Z is present`() {
        assertEquals("dream-record-20250731T092500Z", repairHolonId("dream-record-20250731T0925Z"))
    }

    @Test
    fun `repairHolonId pads missing seconds when Z is absent`() {
        assertEquals("foo-20250731T092500Z", repairHolonId("foo-20250731T0925"))
    }

    @Test
    fun `repairHolonId strips a trailing file-extension suffix`() {
        assertEquals("dream_transcript-20250902T190000Z", repairHolonId("dream_transcript-20250902T190000Z.md"))
    }

    @Test
    fun `repairHolonId handles extension plus missing Z`() {
        assertEquals("foo-20250902T190000Z", repairHolonId("foo-20250902T190000.md"))
    }

    @Test
    fun `repairHolonId returns null when there is no timestamp tail`() {
        assertNull(repairHolonId("legacy-deep-archive-1"))
        assertNull(repairHolonId("archive-record-202507"))
        assertNull(repairHolonId("dream-protocol-1"))
    }

    @Test
    fun `repairHolonId returns null when there is no hyphen`() {
        assertNull(repairHolonId("noseparator"))
        assertNull(repairHolonId("20250731T092500Z"))
    }

    @Test
    fun `repairHolonId returns null when the id ends with a trailing hyphen`() {
        assertNull(repairHolonId("name-"))
    }

    // --- Test Group: createHolonFromString ---

    @Test
    fun `createHolonFromString should succeed and normalize a valid holon`() {
        // Arrange
        val rawContent = """
            {
                "header": {
                    "id": "My-Holon-1-20251112T190000Z",
                    "type": "Test", "name": "  My Holon 1  ",
                    "sub_holons": [{ "id": "Sub-Holon-2-20251112T190100Z", "type": "Sub", "summary": "s" }]
                },
                "payload": {}
            }
        """.trimIndent()
        val sourcePath = "My-Holon-1-20251112T190000Z.json"

        // Act
        val holon = createHolonFromString(rawContent, sourcePath, platform)

        // Assert
        assertEquals("my-holon-1-20251112T190000Z", holon.header.id)
        assertEquals("My Holon 1", holon.header.name) // name is trimmed but not lowercased
        assertEquals("sub-holon-2-20251112T190100Z", holon.header.subHolons.first().id)
        assertEquals(rawContent, holon.rawContent, "Original rawContent must be preserved.")
        assertEquals(sourcePath, holon.header.filePath, "File path must be enriched.")
    }

    @Test
    fun `createHolonFromString should throw for malformed JSON`() {
        val rawContent = """{ "header": { "id": "test-20251112T190000Z" },""" // Missing closing brace
        val sourcePath = "test-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Malformed JSON"))
    }

    @Test
    fun `createHolonFromString should throw for mismatched ID and filename`() {
        val rawContent = """{ "header": { "id": "actual-id-20251112T190000Z", "type": "T", "name": "N" }, "payload": {} }"""
        val sourcePath = "filename-id-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Mismatched ID"))
        assertTrue(exception.message!!.contains("actual-id-20251112T190000Z"))
        assertTrue(exception.message!!.contains("filename-id-20251112T190000Z"))
    }

    @Test
    fun `createHolonFromString should throw for invalid sub-holon ID format`() {
        val rawContent = """
            {
                "header": {
                    "id": "valid-parent-20251112T190000Z", "type": "T", "name": "N",
                    "sub_holons": [{ "id": "invalid-sub-holon", "type": "S", "summary": "" }]
                }, "payload": {}
            }
        """.trimIndent()
        val sourcePath = "valid-parent-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Invalid ID format"))
        assertTrue(exception.message!!.contains("invalid-sub-holon"))
    }

    // --- Test Group: createHolonFromString — automatic ID repair ---
    // Mechanically-fixable legacy IDs should load instead of quarantining the file.

    @Test
    fun `createHolonFromString repairs a header id missing seconds`() {
        // "dream-record-20250731T0925Z" is the legacy HHMM-without-seconds pattern
        // seen all over the import report. Both the filename and the header id are
        // the same legacy shape; both repair to the canonical form and match.
        val rawContent = """
            { "header": {"id": "dream-record-20250731T0925Z", "type": "T", "name": "N"}, "payload": {} }
        """.trimIndent()
        val sourcePath = "dream-record-20250731T0925Z.json"

        val holon = createHolonFromString(rawContent, sourcePath, platform)
        assertEquals("dream-record-20250731T092500Z", holon.header.id)
    }

    @Test
    fun `createHolonFromString tolerates filename missing Z when header has Z`() {
        // Matches the "dream-record-first-sunrise-20250905T220000" vs header ...Z case.
        val rawContent = """
            { "header": {"id": "foo-20250905T220000Z", "type": "T", "name": "N"}, "payload": {} }
        """.trimIndent()
        val sourcePath = "foo-20250905T220000.json"  // filename missing Z

        val holon = createHolonFromString(rawContent, sourcePath, platform)
        assertEquals("foo-20250905T220000Z", holon.header.id)
    }

    @Test
    fun `createHolonFromString repairs sub-holon id with trailing file-extension suffix`() {
        // Matches "dream_transcript-20250902T190000Z.md" seen as a rogue sub-holon id.
        val rawContent = """
            {
                "header": {
                    "id": "parent-20251112T190000Z", "type": "T", "name": "N",
                    "sub_holons": [{ "id": "bar-20250902T190000Z.md", "type": "T", "summary": "s" }]
                },
                "payload": {}
            }
        """.trimIndent()
        val sourcePath = "parent-20251112T190000Z.json"

        val holon = createHolonFromString(rawContent, sourcePath, platform)
        assertEquals(1, holon.header.subHolons.size)
        assertEquals("bar-20250902T190000Z", holon.header.subHolons.first().id)
    }

    @Test
    fun `createHolonFromString still throws for irreparable header id`() {
        // "legacy-deep-archive-1" has no timestamp component — repair returns null,
        // so normalize throws and the file is quarantined as before.
        val rawContent = """
            { "header": {"id": "legacy-deep-archive-1", "type": "T", "name": "N"}, "payload": {} }
        """.trimIndent()
        val sourcePath = "legacy-deep-archive-1.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Invalid ID format"))
    }

    @Test
    fun `createHolonFromString still throws for irreparable sub-holon id`() {
        // "dream-protocol-1" is the irreparable sub-holon id from the import report.
        val rawContent = """
            {
                "header": {
                    "id": "parent-20251112T190000Z", "type": "T", "name": "N",
                    "sub_holons": [{ "id": "dream-protocol-1", "type": "T", "summary": "s" }]
                },
                "payload": {}
            }
        """.trimIndent()
        val sourcePath = "parent-20251112T190000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Invalid ID format"))
        assertTrue(exception.message!!.contains("dream-protocol-1"))
    }

    @Test
    fun `createHolonFromString still throws on genuine filename-vs-header drift`() {
        // Both IDs are canonical but differ in timestamp — content drift, not a
        // mechanical issue. Must surface as Mismatched ID.
        val rawContent = """
            { "header": {"id": "session-record-20250810T102500Z", "type": "T", "name": "N"}, "payload": {} }
        """.trimIndent()
        val sourcePath = "session-record-20250810T100000Z.json"

        val exception = assertFailsWith<HolonValidationException> {
            createHolonFromString(rawContent, sourcePath, platform)
        }
        assertTrue(exception.message!!.contains("Mismatched ID"))
    }


    // --- Test Group: Serialization ---

    @Test
    fun `prepareHolonForWriting should serialize header and payload but exclude rawContent`() {
        // Arrange
        val richHeader = HolonHeader(
            id = "test-holon-20251112T190000Z", type = "Test", name = "Test Holon",
            filePath = "path/file.json", parentId = "parent-20251112T180000Z", depth = 1
        )
        val inMemoryHolon = Holon(
            header = richHeader,
            payload = buildJsonObject { put("key", "value") },
            rawContent = "This is raw content that should be discarded during serialization."
        )

        // Act
        val jsonString = prepareHolonForWriting(inMemoryHolon)
        val parsedJson = json.parseToJsonElement(jsonString).jsonObject

        // Assert
        assertFalse(parsedJson.containsKey("rawContent"), "The serialized output must not contain the 'rawContent' field.")
        val parsedHeader = parsedJson["header"]?.jsonObject
        assertNotNull(parsedHeader, "Serialized output must contain a header.")
        assertEquals("path/file.json", parsedHeader["filePath"]?.jsonPrimitive?.content)
        assertEquals("parent-20251112T180000Z", parsedHeader["parentId"]?.jsonPrimitive?.content)
        assertEquals(1, parsedHeader["depth"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `synchronizeRawContent should update rawContent to match structured data`() {
        // Arrange
        val desyncedHolon = Holon(
            header = HolonHeader(id = "hl1-20251112T190000Z", type = "T", name = "New Name"), // Name is "New Name"
            payload = buildJsonObject { put("key", "value") },
            rawContent = """{"header":{"name":"Old Name"},"payload":{}}""" // Raw content is stale
        )

        // Act
        val syncedHolon = synchronizeRawContent(desyncedHolon)
        val parsedRawContent = json.parseToJsonElement(syncedHolon.rawContent).jsonObject

        // Assert
        assertEquals("New Name", parsedRawContent["header"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(syncedHolon.rawContent, prepareHolonForWriting(desyncedHolon))
    }

    // --- Test Group: Round-trip idempotence & canonicalization ---
    // These tests pin the contract that `prepareHolonForWriting` emits a canonical form:
    // semantically-equivalent holons serialize to byte-identical output regardless of
    // payload key order or sub-holon/relationship list order. Without this, re-importing
    // the same files forever reports "Content differs from existing."

    @Test
    fun `prepareHolonForWriting is invariant to payload key order`() {
        val header = HolonHeader(id = "hl1-20251112T190000Z", type = "T", name = "N")
        val payloadA = buildJsonObject { put("alpha", "1"); put("beta", "2"); put("gamma", "3") }
        val payloadB = buildJsonObject { put("gamma", "3"); put("alpha", "1"); put("beta", "2") }

        val outA = prepareHolonForWriting(Holon(header = header, payload = payloadA))
        val outB = prepareHolonForWriting(Holon(header = header, payload = payloadB))

        assertEquals(outA, outB, "Differently-ordered payloads must serialize identically.")
    }

    @Test
    fun `prepareHolonForWriting is invariant to nested payload key order`() {
        val header = HolonHeader(id = "hl1-20251112T190000Z", type = "T", name = "N")
        val payloadA = buildJsonObject {
            put("outer", buildJsonObject { put("a", "1"); put("b", "2") })
        }
        val payloadB = buildJsonObject {
            put("outer", buildJsonObject { put("b", "2"); put("a", "1") })
        }

        assertEquals(prepareHolonForWriting(Holon(header, payloadA)), prepareHolonForWriting(Holon(header, payloadB)))
    }

    @Test
    fun `prepareHolonForWriting sorts sub_holons by id`() {
        val header = HolonHeader(
            id = "hl1-20251112T190000Z", type = "T", name = "N",
            subHolons = listOf(
                SubHolonRef("zzz-20251112T190000Z", "T", "s"),
                SubHolonRef("aaa-20251112T190000Z", "T", "s"),
                SubHolonRef("mmm-20251112T190000Z", "T", "s"),
            )
        )
        val out = prepareHolonForWriting(Holon(header = header, payload = buildJsonObject {}))
        val serializedHeader = json.parseToJsonElement(out).jsonObject["header"]?.jsonObject
        val serializedIds = serializedHeader?.get("sub_holons")?.let { it as JsonArray }
            ?.map { (it as JsonObject)["id"]?.jsonPrimitive?.content }

        assertEquals(
            listOf("aaa-20251112T190000Z", "mmm-20251112T190000Z", "zzz-20251112T190000Z"),
            serializedIds
        )
    }

    @Test
    fun `prepareHolonForWriting round-trips bitwise-stably through createHolonFromString`() {
        // The canonical contract: write → read → write must produce identical bytes.
        // This is what makes re-import converge on "Content is identical".
        val originalRaw = """
            {
                "header": {
                    "id": "test-holon-20251112T190000Z",
                    "type": "Test", "name": "Round Trip",
                    "sub_holons": [
                        {"id": "zzz-20251112T190000Z", "type": "T", "summary": "s"},
                        {"id": "aaa-20251112T190000Z", "type": "T", "summary": "s"}
                    ]
                },
                "payload": {"zebra": 1, "apple": 2, "nested": {"y": "y", "x": "x"}}
            }
        """.trimIndent()
        val sourcePath = "test-holon-20251112T190000Z.json"

        val holon1 = createHolonFromString(originalRaw, sourcePath, platform)
        val written1 = prepareHolonForWriting(holon1)

        val holon2 = createHolonFromString(written1, sourcePath, platform)
        val written2 = prepareHolonForWriting(holon2)

        assertEquals(written1, written2, "A holon must round-trip bitwise-stably.")
    }

    // --- Test Group: Re-import convergence ---
    // End-to-end test against runImportAnalysis: a file whose on-disk key order differs
    // from the existing holon's serialized form must still be marked "Ignore (identical)".
    // This is the top-level user-visible bug — re-imports should converge.

    @Test
    fun `runImportAnalysis marks re-imported file Ignore even when payload key order differs`() {
        val sourcePath = "hl1-20251112T190000Z.json"
        // Existing holon (in state) with payload keys in alphabetical order
        val existingRaw = """
            {
                "header": {"id": "hl1-20251112T190000Z", "type": "T", "name": "H"},
                "payload": {"alpha": "1", "beta": "2"}
            }
        """.trimIndent()
        // Incoming file has the same payload but keys in a different order
        val incomingRaw = """
            {
                "header": {"id": "hl1-20251112T190000Z", "type": "T", "name": "H"},
                "payload": {"beta": "2", "alpha": "1"}
            }
        """.trimIndent()

        val existing = createHolonFromString(existingRaw, sourcePath, platform)
        val kgState = KnowledgeGraphState(holons = mapOf(existing.header.id to existing))

        val result = runImportAnalysis(
            fileContents = mapOf(sourcePath to incomingRaw),
            kgState = kgState,
            userOverrides = emptyMap(),
            isRecursive = true,
            platformDependencies = platform
        )
        val selected = json.decodeFromJsonElement<Map<String, ImportAction>>(result["selectedActions"]!!)

        val action = selected[sourcePath]
        assertTrue(action is Ignore, "Re-imported file with reordered payload keys should be Ignore, got: $action")
    }

    @Test
    fun `runImportAnalysis marks re-imported file Ignore even when sub_holons are listed in different order`() {
        val sourcePath = "parent-20251112T190000Z.json"
        val existingRaw = """
            {
                "header": {
                    "id": "parent-20251112T190000Z", "type": "T", "name": "P",
                    "sub_holons": [
                        {"id": "aaa-20251112T190000Z", "type": "T", "summary": "s"},
                        {"id": "zzz-20251112T190000Z", "type": "T", "summary": "s"}
                    ]
                },
                "payload": {}
            }
        """.trimIndent()
        val incomingRaw = """
            {
                "header": {
                    "id": "parent-20251112T190000Z", "type": "T", "name": "P",
                    "sub_holons": [
                        {"id": "zzz-20251112T190000Z", "type": "T", "summary": "s"},
                        {"id": "aaa-20251112T190000Z", "type": "T", "summary": "s"}
                    ]
                },
                "payload": {}
            }
        """.trimIndent()

        val existing = createHolonFromString(existingRaw, sourcePath, platform)
        val kgState = KnowledgeGraphState(holons = mapOf(existing.header.id to existing))

        val result = runImportAnalysis(
            fileContents = mapOf(sourcePath to incomingRaw),
            kgState = kgState,
            userOverrides = emptyMap(),
            isRecursive = true,
            platformDependencies = platform
        )
        val selected = json.decodeFromJsonElement<Map<String, ImportAction>>(result["selectedActions"]!!)

        assertTrue(selected[sourcePath] is Ignore, "Re-imported file with reordered sub_holons should be Ignore.")
    }

    // --- Test Group: Orphan resolution uses existing graph ---
    // runImportAnalysis must treat a parent that's already in the graph as a valid
    // integration target, not flag the child as "Orphaned." This previously produced
    // a flood of false orphans when users re-imported a subset of files without also
    // re-selecting the parent folder.

    @Test
    fun `runImportAnalysis integrates a new child whose parent exists only in kgState`() {
        val parentId = "parent-20251112T190000Z"
        val childId = "child-20251112T190100Z"
        val childPath = "$childId.json"

        // Parent lives in the graph (e.g. from a prior successful import), and the
        // parent's subHolons list already references this child id.
        val parentRaw = """
            {
                "header": {
                    "id": "$parentId", "type": "T", "name": "P",
                    "sub_holons": [{"id": "$childId", "type": "T", "summary": "s"}]
                },
                "payload": {}
            }
        """.trimIndent()
        val childRaw = """
            { "header": {"id": "$childId", "type": "T", "name": "C"}, "payload": {} }
        """.trimIndent()

        val parentHolon = createHolonFromString(parentRaw, "$parentId.json", platform)
        val kgState = KnowledgeGraphState(holons = mapOf(parentId to parentHolon))

        val result = runImportAnalysis(
            fileContents = mapOf(childPath to childRaw),
            kgState = kgState,
            userOverrides = emptyMap(),
            isRecursive = true,
            platformDependencies = platform
        )
        val selected = json.decodeFromJsonElement<Map<String, ImportAction>>(result["selectedActions"]!!)

        val action = selected[childPath]
        assertTrue(action is Integrate, "Child should Integrate under kgState parent, got: $action")
        assertEquals(parentId, (action as Integrate).parentHolonId)
    }

    @Test
    fun `runImportAnalysis keeps child as Integrate when parent is Ignore (identical content)`() {
        // When a parent is re-imported with identical content (auto-Ignore) and a new
        // child is imported in the same batch, the cascade-demotion loop must NOT
        // quarantine the child. The parent already exists in kgState, so the child
        // integration is valid.
        val parentId = "parent-20251112T190000Z"
        val childId = "child-20251112T190100Z"
        val parentPath = "$parentId.json"
        val childPath = "$childId.json"

        val parentRaw = """
            {
                "header": {
                    "id": "$parentId", "type": "T", "name": "P",
                    "sub_holons": [{"id": "$childId", "type": "T", "summary": "s"}]
                },
                "payload": {}
            }
        """.trimIndent()
        val childRaw = """
            { "header": {"id": "$childId", "type": "T", "name": "C"}, "payload": {} }
        """.trimIndent()

        val parentHolon = createHolonFromString(parentRaw, parentPath, platform)
        val kgState = KnowledgeGraphState(holons = mapOf(parentId to parentHolon))

        val result = runImportAnalysis(
            fileContents = mapOf(parentPath to parentRaw, childPath to childRaw),
            kgState = kgState,
            userOverrides = emptyMap(),
            isRecursive = true,
            platformDependencies = platform
        )
        val selected = json.decodeFromJsonElement<Map<String, ImportAction>>(result["selectedActions"]!!)

        assertTrue(selected[parentPath] is Ignore, "Parent with identical content should be Ignore.")
        assertTrue(
            selected[childPath] is Integrate,
            "Child should remain Integrate when parent is auto-Ignore'd; got: ${selected[childPath]}"
        )
    }

    @Test
    fun `runImportAnalysis keeps child as Integrate when parent in import is Quarantined but exists in kgState`() {
        // The import file for the parent is malformed (mismatched ID causes Quarantine),
        // but the parent still exists in kgState from a prior import. Children should
        // integrate under the kgState parent rather than cascade-quarantine.
        val parentId = "parent-20251112T190000Z"
        val childId = "child-20251112T190100Z"
        val parentPath = "$parentId.json"
        val childPath = "$childId.json"

        val parentValidRaw = """
            {
                "header": {
                    "id": "$parentId", "type": "T", "name": "P",
                    "sub_holons": [{"id": "$childId", "type": "T", "summary": "s"}]
                },
                "payload": {}
            }
        """.trimIndent()
        // Malformed: header id does not match filename → HolonValidationException → Quarantine
        val parentMalformedRaw = """
            { "header": {"id": "different-id-20251112T190000Z", "type": "T", "name": "P"}, "payload": {} }
        """.trimIndent()
        val childRaw = """
            { "header": {"id": "$childId", "type": "T", "name": "C"}, "payload": {} }
        """.trimIndent()

        val parentHolon = createHolonFromString(parentValidRaw, parentPath, platform)
        val kgState = KnowledgeGraphState(holons = mapOf(parentId to parentHolon))

        val result = runImportAnalysis(
            fileContents = mapOf(parentPath to parentMalformedRaw, childPath to childRaw),
            kgState = kgState,
            userOverrides = emptyMap(),
            isRecursive = true,
            platformDependencies = platform
        )
        val selected = json.decodeFromJsonElement<Map<String, ImportAction>>(result["selectedActions"]!!)

        assertTrue(selected[parentPath] is Quarantine, "Parent with mismatched ID should Quarantine.")
        assertTrue(
            selected[childPath] is Integrate,
            "Child should Integrate under kgState parent despite parent file Quarantine; got: ${selected[childPath]}"
        )
    }

    @Test
    fun `runImportAnalysis still quarantines child when parent is neither in kgState nor importable`() {
        // Regression guard for the original cascade behavior: if the parent truly
        // cannot be resolved anywhere, the child should remain Quarantined.
        val parentId = "missing-parent-20251112T190000Z"
        val childId = "child-20251112T190100Z"
        val childPath = "$childId.json"

        // Child points to parent via its header's subHolons reference — but only
        // another in-import holon declares this child as its sub_holon. Here no one
        // does, so the child is plainly orphaned.
        val childRaw = """
            { "header": {"id": "$childId", "type": "T", "name": "C"}, "payload": {} }
        """.trimIndent()

        val kgState = KnowledgeGraphState()

        val result = runImportAnalysis(
            fileContents = mapOf(childPath to childRaw),
            kgState = kgState,
            userOverrides = emptyMap(),
            isRecursive = true,
            platformDependencies = platform
        )
        val selected = json.decodeFromJsonElement<Map<String, ImportAction>>(result["selectedActions"]!!)

        val action = selected[childPath]
        assertTrue(action is Quarantine, "Truly orphaned child should remain Quarantine; got: $action")
        assertTrue(action.reason.contains("Orphaned"), "Reason should mention Orphaned; got: ${action.reason}")
    }
}