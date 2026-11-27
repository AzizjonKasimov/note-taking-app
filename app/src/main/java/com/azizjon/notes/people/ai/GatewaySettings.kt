package com.azizjon.notes.people.ai

import com.azizjon.notes.people.PeopleStorage
import android.content.Context

data class GatewaySettingsState(
    val tokenSaved: Boolean = false,
    /** The user accepted that the people assistant reads whatever records a request needs. */
    val assistantConsent: Boolean = false,
)

/**
 * The people assistant's settings. Its gateway key is the app's own AI key, which the user enters
 * in Settings → Assistant and [token] reads; only the one-time consent is kept here.
 *
 * The key is entered by the user rather than shipped in the APK: releases are public, and a bundled
 * key could be pulled out of one and used to spend the owner's Claude subscription.
 */
class GatewaySettings(context: Context, private val token: () -> String?) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val state: GatewaySettingsState
        get() = GatewaySettingsState(
            tokenSaved = !token().isNullOrBlank(),
            assistantConsent = preferences.getBoolean(KEY_ASSISTANT_CONSENT, false),
        )

    fun setAssistantConsent(accepted: Boolean) {
        preferences.edit().putBoolean(KEY_ASSISTANT_CONSENT, accepted).apply()
    }

    companion object {
        const val PREFERENCES_NAME = PeopleStorage.ASSISTANT_PREFS

        /** Network App's consent covered web lookups too; this one never did, so it has a name of its own. */
        private const val KEY_ASSISTANT_CONSENT = "assistant_consent"
    }
}
