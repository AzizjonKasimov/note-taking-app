package com.azizjon.notes.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The models the assistant may run on, cheapest first, with their API prices per million tokens (in
 * / out) as a guide to how fast each uses up a Claude plan. Never Fable: the user's choice, and the
 * gateway refuses any other model from this app too.
 */
enum class AssistantModel(val id: String, val label: String, val price: String, val note: String) {
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "\$0.10 / \$0.50", "Fastest and cheapest"),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "\$2 / \$10", "Quick, a little less careful"),
    OPUS("claude-opus-5-5", "Opus 5.5", "\$4 / \$20", "The most careful"),
}

/** How deeply the model thinks, from the fastest and cheapest to the deepest. It cannot be turned off. */
enum class AssistantEffort(val id: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "Extra high"),
    MAX("max", "Max"),
}

data class AssistantChoice(val model: AssistantModel, val effort: AssistantEffort) {
    val label: String get() = "${model.label} · ${effort.label}"

    companion object {
        /** The user's pick for a start (2026-11-26). */
        val DEFAULT = AssistantChoice(AssistantModel.OPUS, AssistantEffort.MEDIUM)
    }
}

/** The model and thinking level last picked in the box, kept between uses. */
class AssistantChoiceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<AssistantChoice> = _choice.asStateFlow()

    fun set(choice: AssistantChoice) {
        prefs.edit().putString(KEY_MODEL, choice.model.id).putString(KEY_EFFORT, choice.effort.id).apply()
        _choice.value = choice
    }

    private fun read(): AssistantChoice = AssistantChoice(
        model = AssistantModel.entries.firstOrNull { it.id == prefs.getString(KEY_MODEL, null) } ?: AssistantChoice.DEFAULT.model,
        effort = AssistantEffort.entries.firstOrNull { it.id == prefs.getString(KEY_EFFORT, null) } ?: AssistantChoice.DEFAULT.effort,
    )

    private companion object {
        const val PREFS = "assistant"
        const val KEY_MODEL = "model"
        const val KEY_EFFORT = "effort"
    }
}
