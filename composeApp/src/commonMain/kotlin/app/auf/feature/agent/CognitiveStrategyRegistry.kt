package app.auf.feature.agent

import app.auf.feature.agent.strategies.SovereignStrategy
import app.auf.feature.agent.strategies.VanillaStrategy

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