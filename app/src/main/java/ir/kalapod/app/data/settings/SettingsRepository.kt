package ir.kalapod.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val currency: Currency = Currency.TOMAN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val shopName: String = "",
    /** null = never asked; true/false = user's answer about sending audio to the online recognizer. */
    val onlineVoiceConsent: Boolean? = null,
)

/** User preferences (UI-level). Financial data never lives here. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val currencyKey = stringPreferencesKey("currency")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val shopNameKey = stringPreferencesKey("shop_name")

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            currency = p[currencyKey]?.let { runCatching { Currency.valueOf(it) }.getOrNull() } ?: Currency.TOMAN,
            themeMode = p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            shopName = p[shopNameKey].orEmpty(),
            onlineVoiceConsent = p[voiceConsentKey]?.toBooleanStrictOrNull(),
        )
    }

    private val voiceConsentKey = stringPreferencesKey("online_voice_consent")
    suspend fun setOnlineVoiceConsent(allowed: Boolean) = context.dataStore.edit { it[voiceConsentKey] = allowed.toString() }

    suspend fun setCurrency(c: Currency) = context.dataStore.edit { it[currencyKey] = c.name }
    suspend fun setThemeMode(m: ThemeMode) = context.dataStore.edit { it[themeKey] = m.name }
    suspend fun setShopName(name: String) = context.dataStore.edit { it[shopNameKey] = name.trim() }
}
