package io.rustray.app

import android.content.Context
import java.io.File

class ProfileStore(context: Context) {
    private val appCtx = context.applicationContext
    private val profilesFile = File(appCtx.filesDir, "profiles.json")
    private val selectedFile = File(appCtx.filesDir, "selected_profile.txt")

    init {
        migrateLegacyPrefsIfNeeded()
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (profilesFile.exists()) return
        runCatching {
            val prefs = appCtx.getSharedPreferences("rustray.profiles", Context.MODE_PRIVATE)
            val legacyList = prefs.getString(KEY_LIST, null)
            val legacySelected = prefs.getString(KEY_SELECTED, null)
            if (!legacyList.isNullOrEmpty()) {
                writeAtomic(profilesFile, legacyList)
            }
            if (!legacySelected.isNullOrEmpty() && !selectedFile.exists()) {
                writeAtomic(selectedFile, legacySelected)
            }
        }
    }

    fun load(): MutableList<Profile> {
        val raw = runCatching {
            if (profilesFile.exists()) profilesFile.readText() else null
        }.getOrNull()
        return Profile.decodeList(raw).toMutableList()
    }

    fun save(profiles: List<Profile>) {
        writeAtomic(profilesFile, Profile.encodeList(profiles))
    }

    fun selectedId(): String? = runCatching {
        if (selectedFile.exists()) selectedFile.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    fun select(id: String?) {
        if (id.isNullOrBlank()) {
            runCatching { selectedFile.delete() }
        } else {
            writeAtomic(selectedFile, id)
        }
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

    private fun writeAtomic(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val KEY_LIST = "profiles"
        private const val KEY_SELECTED = "selected"
    }
}
