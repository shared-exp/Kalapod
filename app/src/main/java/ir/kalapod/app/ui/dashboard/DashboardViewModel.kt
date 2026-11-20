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
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {
    private val today = LocalDate.now()

    val state: StateFlow<DashboardUiState> =
        combine(repository.dashboard(today), settingsRepository.settings) { data, settings ->
            DashboardUiState.Ready(data, settings, today) as DashboardUiState
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState.Loading)
}
