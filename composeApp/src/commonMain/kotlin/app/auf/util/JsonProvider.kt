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
 * Provides a single, correctly configured instance of the JSON parser for the entire application.
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
            polymorphic(ImportAction::class) {
                subclass(Update::class)
                subclass(Integrate::class)
                subclass(AssignParent::class)
                subclass(Quarantine::class)
                subclass(Ignore::class)
                subclass(CreateRoot::class)
            }
        }
    }
}