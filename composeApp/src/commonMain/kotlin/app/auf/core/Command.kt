package app.auf.core

/**
 * Annotation to mark an [AppAction] subclass as invokable via the Command Line Language (CCL).
 *
 * Actions marked with this annotation will be automatically discovered and registered
 * by the [CommandInterpreter], provided they conform to simple constructor patterns
 * (no arguments, or a single String/Long argument, or a single String/Long argument + sessionId).
 *
 * @param name The normalized command name (e.g., "showtoast", "clearsession"). This will be
 *             converted to lowercase, with underscores removed, by the interpreter.
 * @param description A brief description of the command's purpose for potential help text.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Command(val name: String, val description: String = "")