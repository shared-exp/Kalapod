package ir.kalapod.app.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import ir.kalapod.app.core.format.InputParser
import ir.kalapod.app.core.format.PersianFormat
import ir.kalapod.app.data.local.KalapodDatabase
import ir.kalapod.app.data.local.entity.MovementReason
import ir.kalapod.app.data.repository.InventoryLedger
import ir.kalapod.app.domain.finance.Money
import ir.kalapod.app.domain.finance.StockPosition
import ir.kalapod.app.domain.voice.PersianText
import ir.kalapod.app.domain.voice.ProductMatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a merge would do / did, in plain numbers for the confirmation and result dialogs. */
data class MergeSummary(
    val documentsToAdd: Int,
    val documentsAlreadyHere: Int,
    val productsMatched: Int,
    val productsNew: Int,
    val partiesMatched: Int,
    val partiesNew: Int,
    val invoicesRenumbered: Int,
    /** Products whose stock entered after reinstall is treated as a count, not extra stock. */
    val openingCounts: Int,
)

/**
 * Adds an older backup to the data on this phone without deleting anything — for the shop that
 * reinstalled the app, kept selling, and later found its backup.
 *
 *  - Products are matched by barcode, then by name; customers/suppliers by name (+ phone).
 *  - Documents keep their original dates; a document already present (same id) is skipped, so
 *    adding the same backup twice changes nothing.
 *  - Invoice numbers: the backup's (older) invoices keep theirs; a newer invoice with the same number
 *    gets the next free number and «شماره قبلی: …» in its note.
 *  - Stock entered as «موجودی اولیه» after the reinstall is the same physical goods, so it becomes a
 *    stock count at that date (no double stock).
 *  - Cost of goods for every merged product is recomputed in date order (moving weighted average).
 */
@Singleton
class BackupMerger @Inject constructor(
    private val db: KalapodDatabase,
    private val ledger: InventoryLedger,
) {
    private val w: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    suspend fun preview(staged: File): MergeSummary = db.withTransaction {
        val plan = plan(staged)
        // Nothing is written by planning; summary only.
        plan.summary
    }

    suspend fun merge(staged: File): MergeSummary = db.withTransaction {
        val plan = plan(staged)
        apply(plan, staged)
        plan.summary
    }

    // ------------------------------------------------------------------ planning

    private class Plan(
        val productMap: Map<Long, Long?>,          // backup product id → current id (null = insert new)
        val customerMap: Map<Long, Long?>,
        val supplierMap: Map<Long, Long?>,
        val categoryMap: Map<Long, Long?>,
        val existingTxByUuid: Map<String, Long>,
        val countMovementIds: Set<Long>,            // current «موجودی اولیه» rows to treat as counts
        val clashes: Map<String, Long>,             // invoice number → current tx id that must be renumbered
        val summary: MergeSummary,
    )

    private fun plan(staged: File): Plan = SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { bk ->
        // Categories by name
        val curCats = rows(w.query("SELECT id, name FROM categories")) { it.getLong(0) to key(it.getString(1)) }
        val categoryMap = rows(bk.rawQuery("SELECT id, name FROM categories", null)) { c ->
            c.getLong(0) to curCats.firstOrNull { it.second == key(c.getString(1)) }?.first
        }.toMap()

        // Products: barcode first, then name
        val curBarcodes = rows(w.query("SELECT code, productId FROM product_barcodes")) { it.getString(0) to it.getLong(1) }.toMap()
        val curNames = rows(w.query("SELECT id, name FROM products")) { ProductMatcher.fold(it.getString(1)) to it.getLong(0) }.toMap()
        val bkBarcodes = rows(bk.rawQuery("SELECT productId, code FROM product_barcodes", null)) { it.getLong(0) to it.getString(1) }
            .groupBy({ it.first }, { it.second })
        val productMap = rows(bk.rawQuery("SELECT id, name FROM products", null)) { c ->
            val id = c.getLong(0)
            val byBarcode = bkBarcodes[id].orEmpty().firstNotNullOfOrNull { curBarcodes[it] }
            id to (byBarcode ?: curNames[ProductMatcher.fold(c.getString(1))])
        }.toMap()

        fun parties(table: String): Map<Long, Long?> {
            val cur = rows(w.query("SELECT id, name, phone FROM $table")) { Triple(it.getLong(0), key(it.getString(1)), phone(it.getStringOrNull(2))) }
            return rows(bk.rawQuery("SELECT id, name, phone FROM $table", null)) { c ->
                val n = key(c.getString(1)); val ph = phone(c.getStringOrNull(2))
                c.getLong(0) to cur.firstOrNull { it.second == n && (ph == null || it.third == null || ph == it.third) }?.first
            }.toMap()
        }
        val customerMap = parties("customers")
        val supplierMap = parties("suppliers")

        val existingTxByUuid = rows(w.query("SELECT clientUuid, id FROM transactions")) { it.getString(0) to it.getLong(1) }.toMap()
        val bkUuids = rows(bk.rawQuery("SELECT clientUuid FROM transactions", null)) { it.getString(0) }
        val bkUuidSet = bkUuids.toSet()

        // Invoice numbers used by documents we're about to add vs. numbers already used here by other documents.
        val bkNumbers = rows(bk.rawQuery(
            "SELECT inv.number, t.clientUuid FROM invoices inv JOIN transactions t ON t.id = inv.transactionId", null,
        )) { it.getString(0) to it.getString(1) }.filter { it.second !in existingTxByUuid }.map { it.first }.toSet()
        val clashes = rows(w.query(
            "SELECT inv.number, t.id, t.clientUuid FROM invoices inv JOIN transactions t ON t.id = inv.transactionId",
        )) { Triple(it.getString(0), it.getLong(1), it.getString(2)) }
            .filter { it.first in bkNumbers && it.third !in bkUuidSet }
            .associate { it.first to it.second }

        val matched = productMap.values.filterNotNull().toSet()
        val countIds = if (matched.isEmpty()) emptySet() else rows(w.query(
            "SELECT id, productId FROM inventory_movements WHERE reason = 'ADJUSTMENT' AND transactionId IS NULL " +
                "AND (note = ? OR note LIKE ?)",
            arrayOf(OPENING, "$COUNT_PREFIX%"),
        )) { it.getLong(0) to it.getLong(1) }.filter { it.second in matched }.map { it.first }.toSet()

        val toAdd = bkUuids.count { it !in existingTxByUuid }
        Plan(
            productMap, customerMap, supplierMap, categoryMap, existingTxByUuid, countIds, clashes,
            MergeSummary(
                documentsToAdd = toAdd,
                documentsAlreadyHere = bkUuids.size - toAdd,
                productsMatched = productMap.values.count { it != null },
                productsNew = productMap.values.count { it == null },
                partiesMatched = customerMap.values.count { it != null } + supplierMap.values.count { it != null },
                partiesNew = customerMap.values.count { it == null } + supplierMap.values.count { it == null },
                invoicesRenumbered = clashes.size,
                openingCounts = countIds.size,
            ),
        )
    }

    // ------------------------------------------------------------------ applying

    private suspend fun apply(plan: Plan, staged: File) = SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { bk ->
        val now = System.currentTimeMillis()

        // 1) Renumber clashing newer invoices first, so the backup's numbers are free.
        if (plan.clashes.isNotEmpty()) {
            val allNumbers = (rows(w.query("SELECT number FROM invoices")) { it.getString(0) } +
                rows(bk.rawQuery("SELECT number FROM invoices", null)) { it.getString(0) }).toMutableSet()
            for ((number, txId) in plan.clashes) {
                val prefix = number.substringBeforeLast('-') + "-"
                val next = (allNumbers.filter { it.startsWith(prefix) }.mapNotNull { it.substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: 0) + 1
                val fresh = prefix + next.toString().padStart(6, '0')
                allNumbers += fresh
                w.execSQL("UPDATE invoices SET number = ? WHERE transactionId = ?", arrayOf(fresh, txId))
                w.execSQL(
                    "UPDATE transactions SET note = CASE WHEN note IS NULL OR note = '' THEN ? ELSE note || ' · ' || ? END WHERE id = ?",
                    arrayOf("شماره قبلی: $number", "شماره قبلی: $number", txId),
                )
            }
        }

        // 2) Reference data
        val categories = plan.categoryMap.toMutableMap()
        rows(bk.rawQuery("SELECT id, name FROM categories", null)) { it.getLong(0) to it.getString(1) }.forEach { (id, name) ->
            if (categories[id] == null) categories[id] = insert("categories", ContentValues().apply { put("name", name) })
        }
        val products = plan.productMap.toMutableMap()
        bk.rawQuery("SELECT * FROM products", null).use { c ->
            while (c.moveToNext()) {
                val id = c.long("id")
                if (products[id] != null) continue
                products[id] = insert("products", ContentValues().apply {
                    put("name", c.str("name")); put("unit", c.str("unit"))
                    c.longOrNull("categoryId")?.let { put("categoryId", categories[it]) }
                    put("imageUri", c.strOrNull("imageUri"))
                    put("salePrice", c.long("salePrice")); put("lastPurchasePrice", c.long("lastPurchasePrice"))
                    put("minStockMilli", c.long("minStockMilli")); put("stockMilli", 0L); put("stockValue", 0L)
                    put("leadTimeDays", c.long("leadTimeDays")); put("isArchived", c.long("isArchived"))
                    put("createdAt", c.long("createdAt")); put("updatedAt", now)
                })
            }
        }
        val curCodes = rows(w.query("SELECT code FROM product_barcodes")) { it.getString(0) }.toMutableSet()
        rows(bk.rawQuery("SELECT productId, code FROM product_barcodes", null)) { it.getLong(0) to it.getString(1) }.forEach { (pid, code) ->
            if (code !in curCodes) {
                insert("product_barcodes", ContentValues().apply { put("productId", products[pid]); put("code", code) })
                curCodes += code
            }
        }
        fun copyParties(table: String, map: Map<Long, Long?>): Map<Long, Long> {
            val out = map.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
            bk.rawQuery("SELECT id, name, phone, note, createdAt FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id in out) continue
                    out[id] = insert(table, ContentValues().apply {
                        put("name", c.getString(1)); put("phone", c.getStringOrNull(2)); put("note", c.getStringOrNull(3)); put("createdAt", c.getLong(4))
                    })
                }
            }
            return out
        }
        val customers = copyParties("customers", plan.customerMap)
        val suppliers = copyParties("suppliers", plan.supplierMap)

        // 3) Documents in original order (returns/reversals always come after what they point to).
        val txMap = HashMap<Long, Long>()      // backup tx id → current id (existing or new)
        val newTx = HashSet<Long>()            // backup tx ids inserted now
        bk.rawQuery("SELECT * FROM transactions ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val id = c.long("id"); val uuid = c.str("clientUuid")
                val existing = plan.existingTxByUuid[uuid]
                if (existing != null) { txMap[id] = existing; continue }
                txMap[id] = insert("transactions", ContentValues().apply {
                    put("clientUuid", uuid); put("type", c.str("type")); put("status", c.str("status"))
                    put("occurredAt", c.long("occurredAt"))
                    c.longOrNull("customerId")?.let { put("customerId", customers[it]) }
                    c.longOrNull("supplierId")?.let { put("supplierId", suppliers[it]) }
                    put("subtotal", c.long("subtotal")); put("discount", c.long("discount")); put("extraCost", c.long("extraCost"))
                    put("total", c.long("total"))
                    c.longOrNull("originalId")?.let { put("originalId", txMap[it]) }
                    c.longOrNull("reversesId")?.let { put("reversesId", txMap[it]) }
                    put("note", c.strOrNull("note")); put("createdAt", c.long("createdAt"))
                })
                newTx += id
            }
        }
        bk.rawQuery("SELECT * FROM transaction_items ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val tx = c.long("transactionId"); if (tx !in newTx) continue
                insert("transaction_items", ContentValues().apply {
                    put("transactionId", txMap[tx]); put("productId", products[c.long("productId")])
                    put("quantityMilli", c.long("quantityMilli")); put("unitPrice", c.long("unitPrice"))
                    put("lineTotal", c.long("lineTotal")); put("costTotal", c.long("costTotal"))
                })
            }
        }
        bk.rawQuery("SELECT * FROM inventory_movements ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val tx = c.longOrNull("transactionId")
                val pid = products[c.long("productId")]!!
                if (tx != null && tx !in newTx) continue
                if (tx == null && exists(
                        "SELECT 1 FROM inventory_movements WHERE transactionId IS NULL AND productId = ? AND occurredAt = ? AND quantityDeltaMilli = ?",
                        arrayOf(pid, c.long("occurredAt"), c.long("quantityDeltaMilli")),
                    )
                ) continue
                insert("inventory_movements", ContentValues().apply {
                    put("productId", pid); tx?.let { put("transactionId", txMap[it]) }
                    put("reason", c.str("reason")); put("quantityDeltaMilli", c.long("quantityDeltaMilli"))
                    put("valueDelta", c.long("valueDelta")); put("note", c.strOrNull("note")); put("occurredAt", c.long("occurredAt"))
                })
            }
        }
        bk.rawQuery("SELECT * FROM invoices", null).use { c ->
            while (c.moveToNext()) {
                val tx = c.long("transactionId"); if (tx !in newTx) continue
                insert("invoices", ContentValues().apply { put("transactionId", txMap[tx]); put("number", c.str("number")); put("issuedAt", c.long("issuedAt")) })
            }
        }
        val payMap = HashMap<Long, Long>()
        bk.rawQuery("SELECT * FROM payments ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val id = c.long("id"); val uuid = c.str("clientUuid")
                val existing = rows(w.query("SELECT id FROM payments WHERE clientUuid = ?", arrayOf(uuid))) { it.getLong(0) }.firstOrNull()
                if (existing != null) { payMap[id] = existing; continue }
                payMap[id] = insert("payments", ContentValues().apply {
                    put("clientUuid", uuid)
                    c.longOrNull("transactionId")?.let { put("transactionId", txMap[it]) }
                    c.longOrNull("customerId")?.let { put("customerId", customers[it]) }
                    c.longOrNull("supplierId")?.let { put("supplierId", suppliers[it]) }
                    put("direction", c.str("direction")); put("method", c.str("method")); put("amount", c.long("amount"))
                    c.longOrNull("reversesPaymentId")?.let { put("reversesPaymentId", payMap[it]) }
                    put("note", c.strOrNull("note")); put("occurredAt", c.long("occurredAt"))
                })
            }
        }
        if (tableExists(bk, "product_aliases")) {
            rows(bk.rawQuery("SELECT alias, productId, createdAt FROM product_aliases", null)) { Triple(it.getString(0), it.getLong(1), it.getLong(2)) }
                .forEach { (alias, pid, at) ->
                    if (!exists("SELECT 1 FROM product_aliases WHERE alias = ?", arrayOf(alias))) {
                        insert("product_aliases", ContentValues().apply { put("alias", alias); put("productId", products[pid]); put("createdAt", at) })
                    }
                }
        }

        // 4) Re-cost every merged product in date order, then refresh caches and alerts.
        for (pid in products.values.filterNotNull().toSet()) {
            recost(pid, plan.countMovementIds)
            ledger.rebuildCache(pid, now)
        }
    }

    /**
     * Replays a product's ledger in date order with the moving weighted average, so sales after the
     * reinstall carry the right cost now that older stock is known. Purchases and purchase returns keep
     * their known cost; «موجودی اولیه» rows in [counts] become "stock counted = N" adjustments.
     */
    private fun recost(productId: Long, counts: Set<Long>) {
        val fallback = rows(w.query("SELECT lastPurchasePrice FROM products WHERE id = ?", arrayOf(productId))) { it.getLong(0) }.firstOrNull() ?: 0L
        data class Mv(val id: Long, val tx: Long?, val reason: String, val qty: Long, val value: Long, val note: String?)
        val moves = rows(w.query(
            "SELECT id, transactionId, reason, quantityDeltaMilli, valueDelta, note FROM inventory_movements WHERE productId = ? ORDER BY occurredAt, id",
            arrayOf(productId),
        )) { Mv(it.getLong(0), it.longOrNull(1), it.getString(2), it.getLong(3), it.getLong(4), it.getStringOrNull(5)) }
        val countedOf = HashMap<Long, Long>()
        val txInfo = HashMap<Long, Pair<Long?, Long?>>() // tx → (originalId, reversesId)
        fun info(tx: Long) = txInfo.getOrPut(tx) {
            rows(w.query("SELECT originalId, reversesId FROM transactions WHERE id = ?", arrayOf(tx))) { it.longOrNull(0) to it.longOrNull(1) }.first()
        }
        var pos = StockPosition()
        val result = HashMap<Long, StockPosition.Movement>()
        val byTx = HashMap<Long, MutableList<StockPosition.Movement>>()   // recomputed, in order, per transaction
        val mirrored = HashMap<Long, Int>()                                // how many of a tx's moves were already reversed

        for (m in moves) {
            val mv: StockPosition.Movement = when {
                m.id in counts -> {
                    // The opening amount was a count of the shelf. Once rewritten, the counted amount lives in the note.
                    val counted = countedFromNote(m.note) ?: m.qty
                    countedOf[m.id] = counted
                    val delta = counted - pos.quantityMilli
                    if (delta == 0L) StockPosition.Movement(0, 0) else pos.adjust(delta, fallback).second
                }
                m.reason == MovementReason.PURCHASE.name || m.reason == MovementReason.PURCHASE_RETURN.name ->
                    StockPosition.Movement(m.qty, m.value)
                m.reason == MovementReason.SALE.name -> pos.issue(-m.qty, fallback).second
                m.reason == MovementReason.SALE_RETURN.name -> {
                    val orig = m.tx?.let { info(it).first }?.let { byTx[it] }.orEmpty()
                    val origQty = -orig.sumOf { it.quantityDeltaMilli }; val origCost = -orig.sumOf { it.valueDelta }
                    val cost = if (origQty > 0) Money.divRoundHalfUp(origCost * m.qty, origQty) else m.value
                    pos.receive(m.qty, cost.coerceAtLeast(0)).second
                }
                m.reason == MovementReason.REVERSAL.name -> {
                    val target = m.tx?.let { info(it).second }
                    val list = target?.let { byTx[it] }
                    val k = target?.let { mirrored[it] ?: 0 } ?: 0
                    val src = list?.getOrNull(k)
                    if (src != null) { mirrored[target] = k + 1; StockPosition.Movement(-src.quantityDeltaMilli, -src.valueDelta) }
                    else StockPosition.Movement(m.qty, m.value)
                }
                m.qty > 0 -> pos.adjust(m.qty, fallback).second
                m.qty < 0 -> pos.adjust(m.qty, fallback).second
                else -> StockPosition.Movement(0, 0)
            }
            pos = pos.apply(mv)
            result[m.id] = mv
            m.tx?.let { byTx.getOrPut(it) { mutableListOf() } += mv }
        }

        for (m in moves) {
            val mv = result.getValue(m.id)
            if (mv.quantityDeltaMilli != m.qty || mv.valueDelta != m.value) {
                w.execSQL("UPDATE inventory_movements SET quantityDeltaMilli = ?, valueDelta = ? WHERE id = ?", arrayOf(mv.quantityDeltaMilli, mv.valueDelta, m.id))
            }
            countedOf[m.id]?.let { counted ->
                w.execSQL("UPDATE inventory_movements SET note = ? WHERE id = ?", arrayOf(COUNT_PREFIX + PersianFormat.quantity(counted), m.id))
            }
        }
        // Line costs of sales and sale returns follow their movements (same order per transaction).
        for ((tx, mvs) in byTx) {
            val items = rows(w.query(
                "SELECT i.id FROM transaction_items i JOIN transactions t ON t.id = i.transactionId " +
                    "WHERE i.transactionId = ? AND i.productId = ? AND t.type IN ('SALE','SALE_RETURN') ORDER BY i.id",
                arrayOf(tx, productId),
            )) { it.getLong(0) }
            items.zip(mvs).forEach { (itemId, mv) ->
                w.execSQL("UPDATE transaction_items SET costTotal = ? WHERE id = ?", arrayOf(Math.abs(mv.valueDelta), itemId))
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun countedFromNote(note: String?): Long? =
        note?.takeIf { it.startsWith(COUNT_PREFIX) }?.removePrefix(COUNT_PREFIX)?.let { InputParser.quantity(it) }

    private fun insert(table: String, cv: ContentValues): Long = w.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
    private fun exists(sql: String, args: Array<Any?>): Boolean = w.query(sql, args).use { it.moveToFirst() }
    private fun tableExists(d: SQLiteDatabase, name: String) =
        d.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
    private fun <T> rows(c: Cursor, f: (Cursor) -> T): List<T> = c.use { val out = mutableListOf<T>(); while (it.moveToNext()) out += f(it); out }
    private fun key(name: String) = PersianText.normalize(name).replace(",", " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    private fun phone(p: String?) = p?.let { PersianText.normalize(it).filter(Char::isDigit) }?.takeIf { it.isNotEmpty() }

    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.longOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)
    private fun Cursor.str(col: String) = getString(getColumnIndexOrThrow(col))
    private fun Cursor.strOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.getStringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    companion object {
        /** Note written by ProductRepository on the opening-stock row of a new product. */
        const val OPENING = "موجودی اولیه"
        /** Note of an opening row turned into a shelf count by a merge; keeps the counted amount for later merges. */
        const val COUNT_PREFIX = "شمارش انبار (پس از نصب دوباره): "
    }
}
