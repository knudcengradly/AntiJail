package com.antijail.overlay.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.antijail.overlay.model.ActionProfile
import com.antijail.overlay.model.FishSettings
import com.antijail.overlay.model.MiningSettings
import com.antijail.overlay.model.SetupLayout
import com.antijail.overlay.model.SetupProgress
import com.antijail.overlay.model.UiPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "antijail_overlay_store")

class ProfileStore(private val context: Context) {
    private val profileKey = stringPreferencesKey("action_profile_json")
    private val miningKey = stringPreferencesKey("mining_settings_json")
    private val fishKey = stringPreferencesKey("fish_settings_json")
    private val setupLayoutKey = stringPreferencesKey("setup_layout_json")
    private val setupProgressKey = stringPreferencesKey("setup_progress_json")
    private val uiPreferencesKey = stringPreferencesKey("ui_preferences_json")
    private val json = Json { ignoreUnknownKeys = true }

    val profileFlow: Flow<ActionProfile> = context.dataStore.data.map { pref ->
        pref[profileKey]?.let {
            runCatching { json.decodeFromString<ActionProfile>(it) }.getOrNull()
        } ?: ActionProfile()
    }

    val miningSettingsFlow: Flow<MiningSettings> = context.dataStore.data.map { pref ->
        pref[miningKey]?.let { runCatching { json.decodeFromString<MiningSettings>(it) }.getOrNull() }
            ?: MiningSettings()
    }

    val fishSettingsFlow: Flow<FishSettings> = context.dataStore.data.map { pref ->
        pref[fishKey]?.let { runCatching { json.decodeFromString<FishSettings>(it) }.getOrNull() }
            ?: FishSettings()
    }

    val setupLayoutFlow: Flow<SetupLayout> = context.dataStore.data.map { pref ->
        pref[setupLayoutKey]?.let { runCatching { json.decodeFromString<SetupLayout>(it) }.getOrNull() }
            ?: SetupLayout()
    }

    val setupProgressFlow: Flow<SetupProgress> = context.dataStore.data.map { pref ->
        pref[setupProgressKey]?.let { runCatching { json.decodeFromString<SetupProgress>(it) }.getOrNull() }
            ?: SetupProgress.inferFromLayout(
                layout = pref[setupLayoutKey]?.let {
                    runCatching { json.decodeFromString<SetupLayout>(it) }.getOrNull()
                } ?: SetupLayout(),
                hasSavedLayout = pref[setupLayoutKey] != null
            )
    }

    suspend fun saveProfile(profile: ActionProfile) {
        context.dataStore.edit { pref ->
            pref[profileKey] = json.encodeToString(profile)
        }
    }

    suspend fun saveMiningSettings(settings: MiningSettings) {
        context.dataStore.edit { pref -> pref[miningKey] = json.encodeToString(settings) }
    }

    suspend fun saveFishSettings(settings: FishSettings) {
        context.dataStore.edit { pref -> pref[fishKey] = json.encodeToString(settings) }
    }

    suspend fun saveSetupLayout(layout: SetupLayout) {
        context.dataStore.edit { pref -> pref[setupLayoutKey] = json.encodeToString(layout) }
    }

    suspend fun saveSetupProgress(progress: SetupProgress) {
        context.dataStore.edit { pref -> pref[setupProgressKey] = json.encodeToString(progress) }
    }

    val uiPreferencesFlow: Flow<UiPreferences> = context.dataStore.data.map { pref ->
        pref[uiPreferencesKey]?.let { runCatching { json.decodeFromString<UiPreferences>(it) }.getOrNull() }
            ?: UiPreferences()
    }

    suspend fun saveUiPreferences(preferences: UiPreferences) {
        context.dataStore.edit { pref -> pref[uiPreferencesKey] = json.encodeToString(preferences) }
    }
}
