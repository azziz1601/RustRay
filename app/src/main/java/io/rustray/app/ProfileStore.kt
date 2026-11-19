package io.rustray.app

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("rustray.profiles", Context.MODE_PRIVATE)

    fun load(): MutableList<Profile> =
        Profile.decodeList(prefs.getString(KEY_LIST, null)).toMutableList()

    fun save(profiles: List<Profile>) {
        prefs.edit().putString(KEY_LIST, Profile.encodeList(profiles)).apply()
    }

    fun selectedId(): String? = prefs.getString(KEY_SELECTED, null)

    fun select(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, id)
        }.apply()
    }

    fun find(id: String?): Profile? =
        id?.let { wanted -> load().firstOrNull { it.id == wanted } }

    fun selected(): Profile? {
        val profiles = load()
        val id = selectedId()
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun upsert(profile: Profile) {
        val profiles = load()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        save(profiles)
    }

    fun delete(id: String) {
        val profiles = load()
        profiles.removeAll { it.id == id }
        save(profiles)
        if (selectedId() == id) select(profiles.firstOrNull()?.id)
    }

    companion object {
        private const val KEY_LIST = "profiles"
        private const val KEY_SELECTED = "selected"
    }
}
