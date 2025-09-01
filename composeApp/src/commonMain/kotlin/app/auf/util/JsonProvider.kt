package app.auf.util

import app.auf.core.*
import app.auf.model.Action
import app.auf.model.CreateFile
import app.auf.model.CreateHolon
import app.auf.model.UpdateHolonContent
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
            polymorphic(Action::class) {
                subclass(CreateHolon::class)
                subclass(UpdateHolonContent::class)
                subclass(CreateFile::class)
            }
            polymorphic(ContentBlock::class) {
                subclass(TextBlock::class)
                subclass(CodeBlock::class)
            }
        }
    }
}