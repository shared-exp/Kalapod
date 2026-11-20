package ir.kalapod.app.data.repository

import androidx.room.withTransaction
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.dao.PartyBalance
import ir.kalapod.app.data.local.dao.PartyEntry
import ir.kalapod.app.data.local.entity.CustomerEntity
import ir.kalapod.app.data.local.entity.PaymentDirection
import ir.kalapod.app.data.local.entity.PaymentEntity
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.SupplierEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

enum class PartyKind { CUSTOMER, SUPPLIER }

/** Customers and suppliers: who they are, what they owe / are owed, and settling up. */
@Singleton
class PartyRepository @Inject constructor(private val db: KalapodDatabase) {
    private val balances get() = db.balanceDao()

    fun observeBalances(kind: PartyKind): Flow<List<PartyBalance>> =
        if (kind == PartyKind.CUSTOMER) balances.observeCustomerBalances() else balances.observeSupplierBalances()

    fun observeHistory(kind: PartyKind, id: Long): Flow<List<PartyEntry>> =
        if (kind == PartyKind.CUSTOMER) balances.observeCustomerHistory(id) else balances.observeSupplierHistory(id)

    /** Returns (name, phone) or null. */
    suspend fun get(kind: PartyKind, id: Long): Pair<String, String?>? =
        if (kind == PartyKind.CUSTOMER) balances.customer(id)?.let { it.name to it.phone }
        else balances.supplier(id)?.let { it.name to it.phone }

    /** Creates or updates; returns the id or an error message. */
    suspend fun save(kind: PartyKind, id: Long, name: String, phone: String?): Result<Long> {
        val n = name.trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) return Result.failure(IllegalArgumentException("نام را وارد کنید."))
        val ph = phone?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val dao = db.partyDao()
        return Result.success(
            when (kind) {
                PartyKind.CUSTOMER -> if (id == 0L) dao.insertCustomer(CustomerEntity(name = n, phone = ph, createdAt = now))
                else { dao.updateCustomer(balances.customer(id)!!.copy(name = n, phone = ph)); id }
                PartyKind.SUPPLIER -> if (id == 0L) dao.insertSupplier(SupplierEntity(name = n, phone = ph, createdAt = now))
                else { dao.updateSupplier(balances.supplier(id)!!.copy(name = n, phone = ph)); id }
            },
        )
    }

    /**
     * Money received from a customer (settling نسیه) or paid to a supplier. [clientUuid] makes a
     * double tap harmless. Returns an error message or null.
     */
    suspend fun settle(kind: PartyKind, partyId: Long, amount: Long, method: PaymentMethod, note: String?, clientUuid: String): String? {
        if (amount <= 0) return "مبلغ را وارد کنید."
        if (method == PaymentMethod.CREDIT) return "روش پرداخت نامعتبر است."
        return db.withTransaction {
            if (balances.paymentByUuid(clientUuid) != null) return@withTransaction null
            db.paymentDao().insert(
                PaymentEntity(
                    clientUuid = clientUuid,
                    customerId = partyId.takeIf { kind == PartyKind.CUSTOMER },
                    supplierId = partyId.takeIf { kind == PartyKind.SUPPLIER },
                    direction = if (kind == PartyKind.CUSTOMER) PaymentDirection.IN else PaymentDirection.OUT,
                    method = method, amount = amount, note = note?.trim()?.takeIf { it.isNotEmpty() },
                    occurredAt = System.currentTimeMillis(),
                ),
            )
            null
        }
    }

    /** Cancels a mistaken standalone payment with a mirror row (no deletes). */
    suspend fun reversePayment(paymentId: Long, reason: String): String? {
        if (reason.isBlank()) return "دلیل ابطال را بنویسید."
        return db.withTransaction {
            val p = balances.payment(paymentId) ?: return@withTransaction "پرداخت پیدا نشد."
            if (p.transactionId != null) return@withTransaction "این پرداخت جزو فاکتور است؛ فاکتور را باطل کنید."
            if (p.reversesPaymentId != null || balances.reversalCount(paymentId) > 0) return@withTransaction "قبلاً باطل شده است."
            db.paymentDao().insert(
                p.copy(
                    id = 0, clientUuid = "${p.clientUuid}-rev", reversesPaymentId = p.id,
                    direction = if (p.direction == PaymentDirection.IN) PaymentDirection.OUT else PaymentDirection.IN,
                    note = reason.trim(), occurredAt = System.currentTimeMillis(),
                ),
            )
            null
        }
    }
}
