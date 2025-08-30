package app.auf.util

import app.auf.core.ActionBlock
import app.auf.core.AnchorBlock
import app.auf.core.AppRequestBlock
import app.auf.core.AssignParent
import app.auf.core.ContentBlock
import app.auf.core.CreateRoot
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
 * @version 1.5
 * @since 2025-08-28
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
                subclass(CreateRoot::class) // <<< MODIFICATION: Added the new action type
            }
        }
    }
}