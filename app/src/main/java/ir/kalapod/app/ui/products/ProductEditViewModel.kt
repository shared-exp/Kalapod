package ir.kalapod.app.ui.products

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.kalapod.app.core.format.Currency
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.data.local.entity.CategoryEntity
import ir.kalapod.app.data.local.entity.InventoryMovementEntity
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.repository.ProductDraft
import ir.kalapod.app.data.repository.ProductRepository
import ir.kalapod.app.data.repository.SaveResult
import ir.kalapod.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProductForm(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val name: String = "",
    val categoryId: Long? = null,
    val unit: ProductUnit = ProductUnit.PIECE,
    val salePrice: String = "",
    val purchasePrice: String = "",
    val minStock: String = "",
    val leadTimeDays: String = "1",
    val openingStock: String = "",
    val barcodes: List<String> = emptyList(),
    val newBarcode: String = "",
    val isArchived: Boolean = false,
    val currency: Currency = Currency.TOMAN,
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class ProductEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ProductRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val productId: Long = savedState.get<Long>("id") ?: 0L
    private val initialBarcode: String? = savedState.get<String>("barcode")?.takeIf { it.isNotBlank() }

    private val _form = MutableStateFlow(ProductForm())
    val form: StateFlow<ProductForm> = _form.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val cur = settings.settings.first().currency
            val p = if (productId != 0L) repo.get(productId) else null
            _form.value = if (p == null) {
                ProductForm(loaded = true, currency = cur, barcodes = listOfNotNull(initialBarcode))
            } else {
                ProductForm(
                    loaded = true, isNew = false, name = p.name, categoryId = p.categoryId, unit = p.unit,
                    salePrice = InputParser.moneyForEdit(p.salePrice, cur),
                    purchasePrice = InputParser.moneyForEdit(p.lastPurchasePrice, cur),
                    minStock = if (p.minStockMilli == 0L) "" else InputParser.quantityForEdit(p.minStockMilli),
                    leadTimeDays = p.leadTimeDays.toString(),
                    barcodes = repo.barcodesOf(p.id), isArchived = p.isArchived, currency = cur,
                )
            }
        }
    }

    fun edit(transform: (ProductForm) -> ProductForm) = _form.update { transform(it).copy(error = null) }

    fun addBarcode() = _form.update {
        val code = it.newBarcode.trim()
        if (code.isEmpty() || code in it.barcodes) it.copy(newBarcode = "") else it.copy(barcodes = it.barcodes + code, newBarcode = "")
    }

    fun removeBarcode(code: String) = _form.update { it.copy(barcodes = it.barcodes - code) }

    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = runCatching { repo.addCategory(name) }.getOrNull()
            if (id != null) edit { it.copy(categoryId = id) } else _form.update { it.copy(error = "این دسته‌بندی وجود دارد.") }
        }
    }

    fun save() {
        val f = _form.value
        if (f.saving) return
        val sale = InputParser.money(f.salePrice, f.currency) ?: return fail("قیمت فروش را درست وارد کنید.")
        val buy = if (f.purchasePrice.isBlank()) 0L else InputParser.money(f.purchasePrice, f.currency)
            ?: return fail("قیمت خرید نامعتبر است.")
        val min = if (f.minStock.isBlank()) 0L else InputParser.quantity(f.minStock) ?: return fail("حداقل موجودی نامعتبر است.")
        val lead = f.leadTimeDays.ifBlank { "1" }.let { InputParser.quantity(it)?.div(1000)?.toInt() }
            ?: return fail("زمان تأمین نامعتبر است.")
        val opening = if (f.openingStock.isBlank()) 0L else InputParser.quantity(f.openingStock)
            ?: return fail("موجودی اولیه نامعتبر است.")
        if (opening > 0 && buy == 0L) return fail("برای ثبت موجودی اولیه، قیمت خرید را وارد کنید.")
        val codes = (f.barcodes + f.newBarcode).map { it.trim() }.filter { it.isNotEmpty() }

        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val r = repo.save(
                ProductDraft(
                    id = productId, name = f.name, categoryId = f.categoryId, unit = f.unit,
                    salePrice = sale, purchasePrice = buy, minStockMilli = min, leadTimeDays = lead.coerceAtLeast(0),
                    barcodes = codes, openingStockMilli = if (f.isNew) opening else 0,
                ),
            )
            _form.update {
                when (r) {
                    is SaveResult.Saved -> it.copy(saving = false, done = true)
                    is SaveResult.Invalid -> it.copy(saving = false, error = r.message)
                }
            }
        }
    }

    fun setArchived(archived: Boolean) {
        if (productId == 0L) return
        viewModelScope.launch {
            repo.setArchived(productId, archived)
            _form.update { it.copy(isArchived = archived, done = archived) }
        }
    }

    /** Ledger history, newest first (empty for a new product). */
    val movements: StateFlow<List<InventoryMovementEntity>> =
        (if (productId == 0L) kotlinx.coroutines.flow.flowOf(emptyList()) else repo.observeMovements(productId))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Current stock for the adjust dialog. */
    suspend fun currentStock(): Long = repo.get(productId)?.stockMilli ?: 0L

    /** Returns an error message, or null on success. */
    suspend fun adjustStock(countText: String, reason: String): String? {
        val counted = InputParser.quantity(countText) ?: return "مقدار شمارش‌شده نامعتبر است."
        return repo.adjustStock(productId, counted, reason)
    }

    private fun fail(msg: String) = _form.update { it.copy(error = msg) }
}
