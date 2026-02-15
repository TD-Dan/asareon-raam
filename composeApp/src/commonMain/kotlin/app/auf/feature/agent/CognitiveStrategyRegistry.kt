package app.auf.feature.agent

import app.auf.feature.agent.strategies.SovereignStrategy  // Allowed: this is inter-feature import
import app.auf.feature.agent.strategies.VanillaStrategy // Allowed: this is inter-feature import

/**
 * The Lookup Table for Cognitive Architectures.
 */
object CognitiveStrategyRegistry {

    private val strategies = mapOf(
        VanillaStrategy.id to VanillaStrategy,
        SovereignStrategy.id to SovereignStrategy
    )

    fun get(id: String): CognitiveStrategy {
        return strategies[id] ?: VanillaStrategy
    }

    fun getAll(): List<CognitiveStrategy> = strategies.values.toList()
}