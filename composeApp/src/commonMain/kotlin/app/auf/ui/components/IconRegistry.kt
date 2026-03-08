package app.auf.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Registry of named Material icons for identity display.
 *
 * Usage:
 *   `IconRegistry.resolve("bolt")` → `Icons.Default.Bolt`
 *   `IconRegistry.agentIcons` → curated set for agent picker
 *   `IconRegistry.allIcons` → full map for advanced selection
 */
object IconRegistry {

    /** All available named icons. Key is the persistent string stored in Identity.displayIcon. */
    val allIcons: Map<String, ImageVector> = mapOf(
        // Animals / Nature
        "pets" to Icons.Default.Pets,
        "bug_report" to Icons.Default.BugReport,
        "eco" to Icons.Default.Eco,
        "forest" to Icons.Default.Forest,
        "park" to Icons.Default.Park,

        // Tech / Computing
        "bolt" to Icons.Default.Bolt,
        "code" to Icons.Default.Code,
        "terminal" to Icons.Default.Terminal,
        "memory" to Icons.Default.Memory,
        "smart_toy" to Icons.Default.SmartToy,
        "psychology" to Icons.Default.Psychology,
        "hub" to Icons.Default.Hub,
        "dns" to Icons.Default.Dns,
        "storage" to Icons.Default.Storage,
        "cloud" to Icons.Default.Cloud,

        // Science / Academic
        "science" to Icons.Default.Science,
        "biotech" to Icons.Default.Biotech,
        "school" to Icons.Default.School,
        "auto_stories" to Icons.Default.AutoStories,
        "lightbulb" to Icons.Default.Lightbulb,

        // Communication / People
        "person" to Icons.Default.Person,
        "group" to Icons.Default.Group,
        "forum" to Icons.Default.Forum,
        "campaign" to Icons.Default.Campaign,
        "record_voice_over" to Icons.Default.RecordVoiceOver,

        // Objects / Symbols
        "star" to Icons.Default.Star,
        "shield" to Icons.Default.Shield,
        "rocket_launch" to Icons.Default.RocketLaunch,
        "construction" to Icons.Default.Construction,
        "handyman" to Icons.Default.Handyman,
        "brush" to Icons.Default.Brush,
        "palette" to Icons.Default.Palette,
        "diamond" to Icons.Default.Diamond,
        "workspace_premium" to Icons.Default.WorkspacePremium,
        "military_tech" to Icons.Default.MilitaryTech,
        "explore" to Icons.Default.Explore,
        "flag" to Icons.Default.Flag,
        "anchor" to Icons.Default.Anchor
    )

    /** Curated subset for agent icon picker — the most relevant/distinctive icons. */
    val agentIcons: List<String> = listOf(
        "bolt", "smart_toy", "psychology", "code", "terminal", "memory",
        "science", "biotech", "lightbulb", "pets", "bug_report", "eco",
        "rocket_launch", "shield", "star", "diamond", "explore",
        "construction", "brush", "hub", "school", "record_voice_over",
        "campaign", "cloud", "anchor", "forest"
    )

    /** Resolves a key to a Material [ImageVector]. Returns null if unknown. */
    fun resolve(key: String?): ImageVector? = key?.let { allIcons[it] }

    /** Default icon for agents when no custom icon is set. */
    val defaultAgentIcon: ImageVector = Icons.Default.Bolt
    val defaultAgentIconKey: String = "bolt"

    /** Default icon for users. */
    val defaultUserIcon: ImageVector = Icons.Default.Person
    val defaultUserIconKey: String = "person"

    /** Default icon for system/feature identities. */
    val defaultSystemIcon: ImageVector = Icons.Default.Terminal
    val defaultSystemIconKey: String = "terminal"
}