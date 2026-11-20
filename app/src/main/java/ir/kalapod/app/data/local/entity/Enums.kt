package ir.kalapod.app.data.local.entity

/** Measurement unit of a product. Quantities are stored in milli-units (1000 = 1 unit). */
enum class ProductUnit { PIECE, KILOGRAM, GRAM, PACK, CARTON, LITER, METER }

enum class TransactionType { PURCHASE, SALE, PURCHASE_RETURN, SALE_RETURN }

/** POSTED = affects stock and balances. REVERSED = cancelled by a later reversal document. */
enum class TransactionStatus { POSTED, REVERSED }

enum class PaymentMethod { CASH, CARD, CREDIT }

/** IN = money received by the shop, OUT = money paid by the shop. */
enum class PaymentDirection { IN, OUT }

enum class MovementReason { PURCHASE, SALE, PURCHASE_RETURN, SALE_RETURN, ADJUSTMENT, REVERSAL }

enum class StockAlertType { LOW_STOCK, OUT_OF_STOCK }
