package ir.kalapod.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.data.settings.AppSettings
import ir.kalapod.app.data.settings.SettingsRepository
import ir.kalapod.app.ui.theme.ThemeMode
import ir.kalapod.app.voice.OfflineModelManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    val offlineModel: OfflineModelManager,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setShopName(n: String) = viewModelScope.launch { repo.setShopName(n) }
    fun setCurrency(c: Currency) = viewModelScope.launch { repo.setCurrency(c) }
    fun setTheme(m: ThemeMode) = viewModelScope.launch { repo.setThemeMode(m) }
    fun setOnlineVoice(allowed: Boolean) = viewModelScope.launch { repo.setOnlineVoiceConsent(allowed) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s: AppSettings = vm.settings.collectAsStateWithLifecycle().value ?: return
    var name by remember { mutableStateOf(s.shopName) }
    LaunchedEffect(name) { if (name != s.shopName) vm.setShopName(name) }
    var modelInstalled by remember { mutableStateOf(vm.offlineModel.isInstalled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("نام فروشگاه") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("واحد پول نمایش", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Currency.entries.forEach { c -> FilterChip(s.currency == c, onClick = { vm.setCurrency(c) }, label = { Text(c.label) }) }
            }

            Text("ظاهر", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "مطابق گوشی", ThemeMode.LIGHT to "روشن", ThemeMode.DARK to "تیره").forEach { (m, l) ->
                    FilterChip(s.themeMode == m, onClick = { vm.setTheme(m) }, label = { Text(l) })
                }
            }

            Text("دستیار صوتی", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.onlineVoiceConsent == true, onClick = { vm.setOnlineVoice(true) }, label = { Text("آنلاین (دقیق‌تر)") })
                FilterChip(s.onlineVoiceConsent == false, onClick = { vm.setOnlineVoice(false) }, label = { Text("فقط آفلاین") })
            }
            Text(
                if (modelInstalled) "بسته آفلاین نصب است." else "بسته آفلاین هنوز نصب نشده؛ اولین بار که با صدا ثبت کنید پیشنهاد می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (modelInstalled) {
                OutlinedButton(onClick = { vm.offlineModel.uninstall(); modelInstalled = false }) { Text("حذف بسته آفلاین (آزادسازی ۱۴۰ مگابایت)") }
            }
        }
    }
}
