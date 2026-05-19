package dev.androidagent.avatar

import android.content.Context

sealed class AvatarSelection {
    object Lobster : AvatarSelection()
    data class Pet(val id: String) : AvatarSelection()
}

object AvatarConfigStore {
    private const val PREFS = "avatar_config"
    private const val KEY_TYPE = "selection_type"
    private const val KEY_PET_ID = "selection_pet_id"

    private const val TYPE_LOBSTER = "lobster"
    private const val TYPE_PET = "pet"

    fun load(context: Context): AvatarSelection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_TYPE, TYPE_LOBSTER)) {
            TYPE_PET -> {
                val id = prefs.getString(KEY_PET_ID, null)?.takeIf { it.isNotBlank() }
                if (id != null) AvatarSelection.Pet(id) else AvatarSelection.Lobster
            }
            else -> AvatarSelection.Lobster
        }
    }

    fun save(context: Context, selection: AvatarSelection) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (selection) {
            is AvatarSelection.Lobster -> editor.putString(KEY_TYPE, TYPE_LOBSTER).remove(KEY_PET_ID)
            is AvatarSelection.Pet -> editor.putString(KEY_TYPE, TYPE_PET).putString(KEY_PET_ID, selection.id)
        }
        editor.apply()
    }
}
