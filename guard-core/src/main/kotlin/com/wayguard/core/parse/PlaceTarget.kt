package com.wayguard.core.parse

/**
 * The place a direction text points to when it names a destination instead of a
 * compass direction: `往大溪方向` → `大溪`, `中山路上往桃園車站方向` → `桃園車站`,
 * `往高鐵方向(快、慢車道)` → `高鐵`, `大金往烈嶼(區間測速)` → `烈嶼`.
 */
object PlaceTarget {

    private val PARENS = Regex("\\(.*?\\)")

    /** The destination after the last `往`, or null when the text names none. */
    fun of(direct: String): String? {
        val s = direct.replace('（', '(').replace('）', ')')
            .filterNot { it.isWhitespace() }
            .replace(PARENS, "")
        val i = s.lastIndexOf('往')
        if (i < 0) return null
        return s.substring(i + 1).removeSuffix("方向").ifBlank { null }
    }

    /** Spelling-insensitive key: `台` and `臺` are the same place. */
    fun key(name: String): String = name.trim().replace('台', '臺')
}
