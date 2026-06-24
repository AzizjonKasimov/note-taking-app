package com.azizjon.notes.ui.theme

enum class AppThemeMode(val prefValue: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val PREF_KEY = "theme_mode"

        fun fromPref(value: String?): AppThemeMode =
            entries.firstOrNull { it.prefValue == value } ?: SYSTEM
    }
}
