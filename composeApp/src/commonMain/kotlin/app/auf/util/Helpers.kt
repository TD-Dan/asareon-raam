package app.auf.util

fun abbreviate(source: Any, max: Int) : String {
    val raw = source.toString()
    return if (raw.length > max) "'${raw.take(max)}...'"
    else "'$raw'"
}