package com.tensorix.antigravityplayer.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiProvider { GEMINI, OPENAI, CLAUDE, GROQ }

/**
 * BYOK credential storage.
 *
 * Security properties:
 *  - Key material lives in Android Keystore; values are AES-256-GCM encrypted
 *    at rest via [EncryptedSharedPreferences].
 *  - Legacy plaintext values ("antigravity_ai_keys") are migrated exactly
 *    once, verified, and then erased from disk.
 *  - If Keystore-backed storage cannot be created or decrypted, the manager
 *    degrades to MEMORY-ONLY mode (keys usable for the session, never written
 *    to disk) rather than falling back to plaintext persistence.
 *
 * Keys are never logged, never exposed through StateFlow, and this file's
 * preferences are excluded from backups (see res/xml/backup_rules.xml).
 */
class AiKeyManager(context: Context) {

    companion object {
        private const val TAG = "AiKeyManager"
        private const val SECURE_PREFS_FILE = "antigravity_ai_keys_secure"
        private const val LEGACY_PREFS_FILE = "antigravity_ai_keys"

        // Provider model IDs kept to stable, documented releases. Runtime
        // failures surface structured errors to the user (see MusicAiAgent).
        val AVAILABLE_MODELS: Map<AiProvider, List<String>> = mapOf(
            AiProvider.GEMINI to listOf(
                "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-pro"
            ),
            AiProvider.OPENAI to listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
            AiProvider.CLAUDE to listOf(
                "claude-3-7-sonnet-latest", "claude-3-5-haiku-latest", "claude-3-5-sonnet-latest"
            ),
            AiProvider.GROQ to listOf(
                "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "llama-3.1-70b-versatile"
            )
        )

        private const val DEFAULT_MODEL = "gemini-2.0-flash"
    }

    val availableModels: Map<AiProvider, List<String>>
        get() = AVAILABLE_MODELS

    /** True when storage could not be secured; UI should warn accordingly. */
    var isMemoryOnlyMode: Boolean = false
        private set

    private val securePrefs: SharedPreferences?
    // Guarded by synchronized(memoryKeys); reference itself is immutable.
    private val memoryKeys: MutableMap<String, String> = HashMap()

    init {
        val appContext = context.applicationContext
        securePrefs = createSecurePrefs(appContext)
        if (securePrefs != null) {
            migrateLegacyPlaintext(appContext, securePrefs)
        } else {
            isMemoryOnlyMode = true
            Log.w(TAG, "Encrypted storage unavailable; running in MEMORY-ONLY key mode.")
        }
    }

    private fun createSecurePrefs(appContext: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Secure prefs creation failed (${e.javaClass.simpleName}); attempting reset.")
            // A corrupted keystore entry bricks the file permanently: remove and retry once.
            try {
                appContext.deleteSharedPreferences(SECURE_PREFS_FILE)
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    SECURE_PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Secure prefs unusable even after reset: ${e2.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * One-time migration of pre-existing plaintext keys. Values are copied,
     * read back for verification, and only then wiped from the legacy file.
     * Any failure leaves legacy data intact (fail-safe) and logs no secrets.
     */
    private fun migrateLegacyPlaintext(appContext: Context, target: SharedPreferences) {
        try {
            val legacy = appContext.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
            val providers = AiProvider.entries
            val migratedAny = providers.any { p ->
                !legacy.getString("key_${p.name}", null).isNullOrBlank()
            }
            if (!migratedAny && !target.getBoolean("legacy_migration_done", false)) {
                // Nothing to migrate; mark done so we don't re-scan forever.
                target.edit().putBoolean("legacy_migration_done", true).apply()
                return
            }

            var allVerified = true
            for (provider in providers) {
                val legacyKey = "key_${provider.name}"
                val plain = legacy.getString(legacyKey, null) ?: continue
                if (plain.isBlank()) continue

                target.edit().putString(legacyKey, plain.trim()).apply()
                if (target.getString(legacyKey, null)?.trim() != plain.trim()) {
                    allVerified = false
                    continue
                }
            }
            if (allVerified) {
                // Verified copy stored securely: destroy plaintext originals.
                legacy.edit().clear().apply()
                target.edit().putBoolean("legacy_migration_done", true).apply()
                Log.i(TAG, "Legacy plaintext API keys migrated to encrypted storage and erased.")
            } else {
                Log.w(TAG, "Migration verification failed; plaintext originals retained.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy key migration error: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // Provider / model selection (non-secret metadata)
    // ------------------------------------------------------------------

    private val _selectedProvider: MutableStateFlow<AiProvider> = MutableStateFlow(
        runCatching {
            val savedName = getString("selected_provider", AiProvider.GEMINI.name)
            AiProvider.valueOf(savedName)
        }.getOrDefault(AiProvider.GEMINI)
    )
    val selectedProvider: StateFlow<AiProvider> = _selectedProvider.asStateFlow()

    private val _selectedModel: MutableStateFlow<String> = MutableStateFlow(
        runCatching {
            val provider = _selectedProvider.value
            val defaultModel = AVAILABLE_MODELS[provider]?.firstOrNull() ?: DEFAULT_MODEL
            getString("model_${provider.name}", defaultModel) ?: defaultModel
        }.getOrDefault(DEFAULT_MODEL)
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // ------------------------------------------------------------------
    // Secret accessors
    // ------------------------------------------------------------------

    fun getApiKey(provider: AiProvider? = null): String {
        val targetProvider = provider ?: _selectedProvider.value
        val prefKey = "key_${targetProvider.name}"
        val prefs = securePrefs
        return if (prefs != null) {
            runCatching { prefs.getString(prefKey, "") ?: "" }.getOrDefault("")
        } else {
            synchronized(memoryKeys) { memoryKeys[prefKey] ?: "" }
        }
    }

    fun setApiKey(provider: AiProvider, key: String) {
        val trimmed = key.trim()
        val prefKey = "key_${provider.name}"
        val prefs = securePrefs
        if (prefs != null) {
            runCatching {
                prefs.edit().putString(prefKey, trimmed).apply()
            }.onFailure {
                Log.e(TAG, "Failed to persist key securely (${it.javaClass.simpleName})")
            }
        } else {
            synchronized(memoryKeys) { memoryKeys[prefKey] = trimmed }
        }
    }

    /** Returns a display-safe hint, e.g. "sk-…abcd". Never returns the full key. */
    fun getMaskedHint(provider: AiProvider? = null): String {
        val key = getApiKey(provider)
        if (key.isBlank()) return ""
        val tail = key.takeLast(4)
        return "••••$tail"
    }

    fun clearApiKey(provider: AiProvider) {
        val prefKey = "key_${provider.name}"
        securePrefs?.let { prefs -> runCatching { prefs.edit().remove(prefKey).apply() } }
        synchronized(memoryKeys) { memoryKeys.remove(prefKey) }
    }

    // ------------------------------------------------------------------
    // Metadata persistence helpers (never store raw keys here)
    // ------------------------------------------------------------------

    private fun getString(key: String, def: String?): String {
        val prefs = securePrefs
        if (prefs != null) {
            return runCatching { prefs.getString(key, def) ?: def ?: "" }.getOrDefault(def ?: "")
        }
        synchronized(memoryKeys) { return memoryKeys[key] ?: (def ?: "") }
    }

    fun setSelectedProvider(provider: AiProvider) {
        _selectedProvider.value = provider
        persistMetadata("selected_provider", provider.name)
        val newModel = getSelectedModelForProvider(provider)
        _selectedModel.value = newModel
    }

    fun getSelectedModelForProvider(provider: AiProvider? = null): String {
        val targetProvider = provider ?: _selectedProvider.value
        val defaultModel = AVAILABLE_MODELS[targetProvider]?.firstOrNull() ?: DEFAULT_MODEL
        return getString("model_${targetProvider.name}", defaultModel)
    }

    fun setSelectedModel(provider: AiProvider, model: String) {
        persistMetadata("model_${provider.name}", model)
        if (_selectedProvider.value == provider) {
            _selectedModel.value = model
        }
    }

    private fun persistMetadata(key: String, value: String) {
        val prefs = securePrefs
        if (prefs != null) {
            runCatching { prefs.edit().putString(key, value).apply() }
        } else {
            synchronized(memoryKeys) { memoryKeys[key] = value }
        }
    }
}
