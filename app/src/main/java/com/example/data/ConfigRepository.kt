package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bridge.AwgProfile
import com.example.bridge.DefaultDohProviders
import com.example.bridge.DohProvider
import com.example.bridge.RoutingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.codmDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "codm_stealth_vault"
)

data class PersistedSettings(
    val profiles: List<AwgProfile> = emptyList(),
    val activeProfileId: String? = null,
    val routingMode: RoutingMode = RoutingMode.DOH_TURBO,
    val selectedDohUrl: String = DefaultDohProviders.presets.first().url,
    val customDohProviders: List<DohProvider> = emptyList(),
    val codmSplitTunnelEnabled: Boolean = true,
    val shizukuProModeEnabled: Boolean = false,
    val dndAutoEnabled: Boolean = true,
    val targetFps: Int = 90
)

class ConfigRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val PROFILES_JSON = stringPreferencesKey("awg_profiles_json")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val SELECTED_DOH_URL = stringPreferencesKey("selected_doh_url")
        val CUSTOM_DOH_JSON = stringPreferencesKey("custom_doh_json")
        val SPLIT_TUNNEL = booleanPreferencesKey("codm_split_tunnel")
        val SHIZUKU_PRO = booleanPreferencesKey("shizuku_pro_mode")
        val DND_AUTO = booleanPreferencesKey("dnd_auto_enabled")
        val TARGET_FPS = intPreferencesKey("target_fps")
    }

    val settingsFlow: Flow<PersistedSettings> = context.codmDataStore.data.map { prefs ->
        val profiles = prefs[Keys.PROFILES_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<AwgProfile>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

        val customDohs = prefs[Keys.CUSTOM_DOH_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<DohProvider>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

        val mode = prefs[Keys.ROUTING_MODE]?.let { raw ->
            runCatching { RoutingMode.valueOf(raw) }.getOrDefault(RoutingMode.DOH_TURBO)
        } ?: RoutingMode.DOH_TURBO

        PersistedSettings(
            profiles = profiles,
            activeProfileId = prefs[Keys.ACTIVE_PROFILE_ID],
            routingMode = mode,
            selectedDohUrl = prefs[Keys.SELECTED_DOH_URL]
                ?: DefaultDohProviders.presets.first().url,
            customDohProviders = customDohs,
            codmSplitTunnelEnabled = prefs[Keys.SPLIT_TUNNEL] ?: true,
            shizukuProModeEnabled = prefs[Keys.SHIZUKU_PRO] ?: false,
            dndAutoEnabled = prefs[Keys.DND_AUTO] ?: true,
            targetFps = prefs[Keys.TARGET_FPS] ?: 90
        )
    }

    suspend fun saveProfile(profile: AwgProfile, setActive: Boolean = true) {
        context.codmDataStore.edit { prefs ->
            val current = prefs[Keys.PROFILES_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<AwgProfile>>(raw) }.getOrDefault(emptyList())
            }.orEmpty().toMutableList()

            val existingIdx = current.indexOfFirst { it.id == profile.id }
            if (existingIdx >= 0) {
                current[existingIdx] = profile
            } else {
                current.add(0, profile)
            }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(current)
            if (setActive) {
                prefs[Keys.ACTIVE_PROFILE_ID] = profile.id
            }
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.codmDataStore.edit { prefs ->
            val current = prefs[Keys.PROFILES_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<AwgProfile>>(raw) }.getOrDefault(emptyList())
            }.orEmpty().filterNot { it.id == profileId }
            prefs[Keys.PROFILES_JSON] = json.encodeToString(current)
            if (prefs[Keys.ACTIVE_PROFILE_ID] == profileId) {
                val nextId = current.firstOrNull()?.id
                if (nextId != null) prefs[Keys.ACTIVE_PROFILE_ID] = nextId
                else prefs.remove(Keys.ACTIVE_PROFILE_ID)
            }
        }
    }

    suspend fun setActiveProfile(profileId: String) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_PROFILE_ID] = profileId
        }
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.ROUTING_MODE] = mode.name
        }
    }

    suspend fun setSelectedDohUrl(url: String) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.SELECTED_DOH_URL] = url.trim()
        }
    }

    suspend fun addCustomDohProvider(name: String, url: String) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://")) return
        val item = DohProvider(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Custom DoH" },
            url = cleanUrl,
            badge = "CUSTOM",
            isCustom = true
        )
        context.codmDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_DOH_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<DohProvider>>(raw) }.getOrDefault(emptyList())
            }.orEmpty().filterNot { it.url == cleanUrl }.toMutableList()
            current.add(0, item)
            prefs[Keys.CUSTOM_DOH_JSON] = json.encodeToString(current)
            prefs[Keys.SELECTED_DOH_URL] = cleanUrl
        }
    }

    suspend fun removeCustomDohProvider(id: String) {
        context.codmDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_DOH_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<DohProvider>>(raw) }.getOrDefault(emptyList())
            }.orEmpty().filterNot { it.id == id }
            prefs[Keys.CUSTOM_DOH_JSON] = json.encodeToString(current)
        }
    }

    suspend fun setCodmSplitTunnel(enabled: Boolean) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.SPLIT_TUNNEL] = enabled
        }
    }

    suspend fun setShizukuProMode(enabled: Boolean) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.SHIZUKU_PRO] = enabled
        }
    }

    suspend fun setDndAuto(enabled: Boolean) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.DND_AUTO] = enabled
        }
    }

    suspend fun setTargetFps(fps: Int) {
        context.codmDataStore.edit { prefs ->
            prefs[Keys.TARGET_FPS] = fps.coerceIn(60, 120)
        }
    }

    suspend fun readConfFromUri(uri: Uri): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }?.removeSuffix(".conf")?.removeSuffix(".awg") ?: "Imported Profile"

            val content = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Cannot open selected .conf file")
            displayName to content
        }
    }
}
