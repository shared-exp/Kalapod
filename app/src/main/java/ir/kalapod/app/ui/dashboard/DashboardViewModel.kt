package ir.kalapod.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.data.repository.DashboardData
import ir.kalapod.app.data.repository.DashboardRepository
import ir.kalapod.app.data.settings.AppSettings
import ir.kalapod.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Ready(val data: DashboardData, val settings: AppSettings, val date: LocalDate) : DashboardUiState
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: DashboardRepository,
    settingsRepository: SettingsRepository,
    private val forecasts: ir.kalapod.app.data.repository.ForecastRepository,
    products: ir.kalapod.app.data.local.dao.ProductDao,
    backupPrefs: ir.kalapod.app.backup.BackupPrefs,
) : ViewModel() {
    /** Nothing recorded yet → offer to bring back a backup (typical right after a reinstall). */
    val emptyShop: StateFlow<Boolean> = products.observeActive().map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The shop has data but daily backup is off → remind (all data lives only on this phone). */
    val backupOff: StateFlow<Boolean> = combine(products.observeActive(), backupPrefs.settings) { p, b -> p.isNotEmpty() && !b.autoEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Products expected to run out soon (from the demand forecast), refreshed when the screen opens. */
    val runningOut = kotlinx.coroutines.flow.MutableStateFlow<List<ir.kalapod.app.domain.forecast.ProductForecast>>(emptyList())

    fun refreshForecast() {
        viewModelScope.launch {
            runningOut.value = forecasts.all().filter { it.shouldOrder }
        }
    }

    private val today = LocalDate.now()

    val state: StateFlow<DashboardUiState> =
        combine(repository.dashboard(today), settingsRepository.settings) { data, settings ->
            DashboardUiState.Ready(data, settings, today) as DashboardUiState
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState.Loading)
}
