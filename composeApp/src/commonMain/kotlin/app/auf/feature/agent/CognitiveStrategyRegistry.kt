package app.auf.feature.agent

import app.auf.core.IdentityHandle
import app.auf.feature.agent.strategies.VanillaStrategy // Allowed: inter-feature import

/**
 * The Lookup Table for Cognitive Architectures.
 *
 * [PHASE 2] Converted from a hardcoded map to a registration-driven map keyed
 * by [IdentityHandle]. Strategies register themselves at feature init time via
 * [register]. The registry also provides [migrateStrategyId] to transparently
 * upgrade legacy string IDs (e.g. `"vanilla_v1"`) found in persisted agent.json
 * files to their canonical [IdentityHandle] equivalents.
 */
object CognitiveStrategyRegistry {

    private val strategies = mutableMapOf<IdentityHandle, CognitiveStrategy>()

    // ---- Legacy ID → IdentityHandle migration map ----
    // Entries are populated automatically when a strategy registers itself.
    // Maps the old `id: String` values to the new handle for backward compat.
    private val legacyIdMap = mutableMapOf<String, IdentityHandle>()

    /**
     * Registers a strategy, making it available for lookup and UI selection.
     * Should be called during [AgentRuntimeFeature.init] before any agents boot.
     *
     * @param legacyId Optional — the old string ID (e.g. `"vanilla_v1"`) that
     *   may still appear in persisted agent.json files. When provided, enables
     *   [migrateStrategyId] to transparently upgrade old references.
     */
    fun register(strategy: CognitiveStrategy, legacyId: String? = null) {
        strategies[strategy.identityHandle] = strategy
        legacyId?.let { legacyIdMap[it] = strategy.identityHandle }
    }

    /**
     * Resolves a strategy by its identity handle.
     * Falls back to [VanillaStrategy] if the handle is not registered.
     */
    fun get(handle: IdentityHandle): CognitiveStrategy =
        strategies[handle] ?: VanillaStrategy

    /**
     * Returns the default strategy (Vanilla).
     */
    fun getDefault(): CognitiveStrategy = VanillaStrategy

    /**
     * Returns all registered strategies for UI enumeration.
     */
    fun getAll(): List<CognitiveStrategy> = strategies.values.toList()

    /**
     * Migrates a raw strategy ID string — which may be either a legacy ID
     * (e.g. `"vanilla_v1"`) or an already-migrated handle (e.g.
     * `"agent.strategy.vanilla"`) — to a canonical [IdentityHandle].
     *
     * Used when deserializing persisted agent.json files that may predate Phase 2.
     */
    fun migrateStrategyId(raw: String): IdentityHandle {
        // 1. Already a registered handle?
        val asHandle = IdentityHandle(raw)
        if (asHandle in strategies) return asHandle

        // 2. Legacy ID?
        legacyIdMap[raw]?.let { return it }

        // 3. Unknown — wrap as-is (will fall back to Vanilla on lookup)
        return asHandle
    }
}