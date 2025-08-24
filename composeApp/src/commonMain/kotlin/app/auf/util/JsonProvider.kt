package app.auf.util

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.AssignParent
import app.auf.core.ContentBlock
import app.auf.core.FileContentBlock
import app.auf.core.Ignore
import app.auf.core.ImportAction
import app.auf.core.Integrate
import app.auf.core.ParseErrorBlock
import app.auf.core.Quarantine
import app.auf.core.SentinelBlock
import app.auf.core.TextBlock
import app.auf.core.Update
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
 *
 * ---
 * ## Mandate
 * This object acts as the "Single Source of Truth" for serialization. Its sole responsibility
 * is to provide a singleton `Json` instance with a `SerializersModule` that is aware of
 * ALL polymorphic hierarchies used in the app. This resolves the "two parsers" bug where
 * different modules might use differently configured instances.
 *
 * ---
 * ## Dependencies
 * - `Action` (Sealed Interface from ActionModels.kt) and its subclasses.
 * - `ContentBlock` (Sealed Interface from AppState.kt) and its subclasses.
 * - `ImportAction` (Sealed Interface from AppState.kt) and its subclasses.
 *
 * @version 1.4
 * @since 2025-08-14
 */
object JsonProvider {
    val appJson = Json {
        prettyPrint = true
        // Prevents crashes when the API adds new fields we don't know about.
        ignoreUnknownKeys = true
        // Allows parsing to succeed even if some fields in a data class are missing from the JSON.
        // This is a key part of the graceful failure strategy.
        isLenient = true
        // Ensures that when we write files, all fields are present, which is good for consistency.
        encodeDefaults = true
        serializersModule = SerializersModule {
            // Configure the Action polymorphic hierarchy
            polymorphic(Action::class) {
                subclass(CreateHolon::class)
                subclass(UpdateHolonContent::class)
                subclass(CreateFile::class)
            }
            // Configure the ContentBlock polymorphic hierarchy
            polymorphic(ContentBlock::class) {
                subclass(TextBlock::class)
                subclass(ActionBlock::class)
                subclass(FileContentBlock::class)
                subclass(AppRequestBlock::class)
                subclass(AnchorBlock::class)
                subclass(ParseErrorBlock::class)
                subclass(SentinelBlock::class)
            }
            polymorphic(ImportAction::class) {
                subclass(Update::class)
                subclass(Integrate::class)
                subclass(AssignParent::class)
                subclass(Quarantine::class)
                subclass(Ignore::class)
            }
        }
    }
}