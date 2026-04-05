package asareon.raam.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Registry of named Material icons for identity display.
 *
 * This is a curated subset of Material Icons — not the full 2000+ set.
 * Icons are chosen for distinctiveness at small sizes (24-48dp) and
 * relevance to agent/identity contexts.
 *
 * Usage:
 *   `IconRegistry.resolve("bolt")` → `Icons.Default.Bolt`
 *   `IconRegistry.agentIcons` → curated set for agent picker
 *   `IconRegistry.allIcons` → full map for advanced selection
 */
object IconRegistry {

    /** All available named icons. Key is the persistent string stored in Identity.displayIcon. */
    val allIcons: Map<String, ImageVector> = mapOf(
        // ── AI / Computing ───────────────────────────────────────────
        "bolt" to Icons.Default.Bolt,
        "smart_toy" to Icons.Default.SmartToy,
        "psychology" to Icons.Default.Psychology,
        "memory" to Icons.Default.Memory,
        "terminal" to Icons.Default.Terminal,
        "code" to Icons.Default.Code,
        "hub" to Icons.Default.Hub,
        "dns" to Icons.Default.Dns,
        "storage" to Icons.Default.Storage,
        "cloud" to Icons.Default.Cloud,
        "cloud_sync" to Icons.Default.CloudSync,
        "developer_board" to Icons.Default.DeveloperBoard,
        "data_object" to Icons.Default.DataObject,
        "api" to Icons.Default.Api,
        "settings_suggest" to Icons.Default.SettingsSuggest,
        "auto_fix_high" to Icons.Default.AutoFixHigh,
        "model_training" to Icons.Default.ModelTraining,
        "precision_manufacturing" to Icons.Default.PrecisionManufacturing,
        "fingerprint" to Icons.Default.Fingerprint,

        // ── Science / Academic ───────────────────────────────────────
        "science" to Icons.Default.Science,
        "biotech" to Icons.Default.Biotech,
        "school" to Icons.Default.School,
        "auto_stories" to Icons.Default.AutoStories,
        "lightbulb" to Icons.Default.Lightbulb,
        "calculate" to Icons.Default.Calculate,
        "architecture" to Icons.Default.Architecture,
        "analytics" to Icons.Default.Analytics,
        "insights" to Icons.Default.Insights,
        "troubleshoot" to Icons.Default.Troubleshoot,

        // ── Nature / Animals ─────────────────────────────────────────
        "pets" to Icons.Default.Pets,
        "bug_report" to Icons.Default.BugReport,
        "eco" to Icons.Default.Eco,
        "forest" to Icons.Default.Forest,
        "park" to Icons.Default.Park,
        "grass" to Icons.Default.Grass,
        "water_drop" to Icons.Default.WaterDrop,
        "air" to Icons.Default.Air,
        "terrain" to Icons.Default.Terrain,
        "sunny" to Icons.Default.WbSunny,

        // ── People / Communication ───────────────────────────────────
        "person" to Icons.Default.Person,
        "group" to Icons.Default.Group,
        "face" to Icons.Default.Face,
        "record_voice_over" to Icons.Default.RecordVoiceOver,
        "forum" to Icons.Default.Forum,
        "campaign" to Icons.Default.Campaign,
        "chat" to Icons.Default.Chat,
        "translate" to Icons.Default.Translate,
        "hearing" to Icons.Default.Hearing,
        "self_improvement" to Icons.Default.SelfImprovement,
        "diversity" to Icons.Default.Diversity3,

        // ── Objects / Tools ──────────────────────────────────────────
        "construction" to Icons.Default.Construction,
        "handyman" to Icons.Default.Handyman,
        "build" to Icons.Default.Build,
        "healing" to Icons.Default.Healing,
        "brush" to Icons.Default.Brush,
        "palette" to Icons.Default.Palette,
        "music_note" to Icons.Default.MusicNote,
        "sports_esports" to Icons.Default.SportsEsports,
        "local_fire" to Icons.Default.LocalFireDepartment,
        "restaurant" to Icons.Default.Restaurant,

        // ── Symbols / Abstract ───────────────────────────────────────
        "star" to Icons.Default.Star,
        "shield" to Icons.Default.Shield,
        "rocket_launch" to Icons.Default.RocketLaunch,
        "diamond" to Icons.Default.Diamond,
        "workspace_premium" to Icons.Default.WorkspacePremium,
        "military_tech" to Icons.Default.MilitaryTech,
        "explore" to Icons.Default.Explore,
        "flag" to Icons.Default.Flag,
        "anchor" to Icons.Default.Anchor,
        "public" to Icons.Default.Public,
        "language" to Icons.Default.Language,
        "visibility" to Icons.Default.Visibility,
        "verified" to Icons.Default.Verified,
        "token" to Icons.Default.Token,
        "auto_awesome" to Icons.Default.AutoAwesome,
        "flare" to Icons.Default.Flare,
        "whatshot" to Icons.Default.Whatshot,
        "grade" to Icons.Default.Grade,

        // ── Navigation / Status ──────────────────────────────────────
        "home" to Icons.Default.Home,
        "search" to Icons.Default.Search,
        "favorite" to Icons.Default.Favorite,
        "bookmark" to Icons.Default.Bookmark,
        "alarm" to Icons.Default.Alarm,
        "speed" to Icons.Default.Speed,
        "tune" to Icons.Default.Tune,
        "key" to Icons.Default.Key
    )

    /** Curated subset for agent icon picker — distinctive at small sizes. */
    val agentIcons: List<String> = listOf(
        // Row 1: AI / Computing
        "bolt", "smart_toy", "psychology", "memory", "terminal", "code", "hub", "auto_fix_high",
        // Row 2: Science
        "science", "biotech", "lightbulb", "architecture", "analytics", "insights", "model_training",
        // Row 3: Nature
        "pets", "bug_report", "eco", "forest", "water_drop", "sunny", "air",
        // Row 4: People
        "person", "face", "record_voice_over", "forum", "translate", "self_improvement", "diversity",
        // Row 5: Tools
        "construction", "brush", "palette", "healing", "sports_esports", "music_note", "local_fire",
        // Row 6: Symbols
        "rocket_launch", "shield", "star", "diamond", "explore", "anchor", "auto_awesome",
        "whatshot", "verified", "token", "flare", "public", "speed", "key",
        // Row 7: Status
        "favorite", "flag", "military_tech", "workspace_premium", "fingerprint"
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