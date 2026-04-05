package asareon.raam.util

/**
Converts Any type toString and abbreviates to given max length or returns original if already short enough.
 */
fun abbreviate(source: Any, max: Int) : String {
    val raw = source.toString()
    return if (raw.length > max) "${raw.take(max)}..."
    else raw
}