package app.auf.util

import app.auf.core.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Provides a single, globally-accessible, and correctly configured instance of the JSON
 * parser for the entire application's CORE data types. Feature-specific models
 * should be handled by a local parser within the feature itself.
 */
object JsonProvider {
    val appJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        serializersModule = SerializersModule {
            polymorphic(ContentBlock::class) {
                subclass(TextBlock::class)
                subclass(CodeBlock::class)
            }
        }
    }
}