package app.auf.core

import kotlinx.serialization.Serializable

/**
 * A universal, serializable data class representing a unique identity (user or agent)
 * within the application.
 */
@Serializable
data class Identity(
    val id: String,
    val name: String
)