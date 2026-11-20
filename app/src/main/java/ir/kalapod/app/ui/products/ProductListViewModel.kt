package ir.kalapod.app.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.data.local.entity.CategoryEntity
import ir.kalapod.app.data.local.entity.ProductEntity
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ProductFilter(val query: String = "", val categoryId: Long? = null, val lowOnly: Boolean = false)

data class ProductListState(
    val filter: ProductFilter = ProductFilter(),
    val products: List<ProductEntity>? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val currency: Currency = Currency.TOMAN,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class ProductListViewModel @Inject constructor(
    repo: ProductRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(ProductFilter())

    private val products = filter.debounce(150).flatMapLatest { f -> repo.observe(f.query, f.categoryId, f.lowOnly) }

    val state: StateFlow<ProductListState> = combine(
        filter, products, repo.observeCategories(), settings.settings.map { it.currency },
    ) { f, p, c, cur -> ProductListState(f, p, c, cur) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductListState())

    fun setQuery(q: String) { filter.value = filter.value.copy(query = q) }
    fun setCategory(id: Long?) { filter.value = filter.value.copy(categoryId = id) }
    fun toggleLowOnly() { filter.value = filter.value.copy(lowOnly = !filter.value.lowOnly) }
}
