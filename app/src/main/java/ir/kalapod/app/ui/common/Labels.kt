package ir.kalapod.app.ui.common

import ir.kalapod.app.data.local.entity.ProductUnit

val ProductUnit.label: String
    get() = when (this) {
        ProductUnit.PIECE -> "عدد"
        ProductUnit.KILOGRAM -> "کیلوگرم"
        ProductUnit.GRAM -> "گرم"
        ProductUnit.PACK -> "بسته"
        ProductUnit.CARTON -> "کارتن"
        ProductUnit.LITER -> "لیتر"
        ProductUnit.METER -> "متر"
    }

val ir.kalapod.app.data.local.entity.TransactionType.label: String
    get() = when (this) {
        ir.kalapod.app.data.local.entity.TransactionType.PURCHASE -> "خرید"
        ir.kalapod.app.data.local.entity.TransactionType.SALE -> "فروش"
        ir.kalapod.app.data.local.entity.TransactionType.PURCHASE_RETURN -> "مرجوعی خرید"
        ir.kalapod.app.data.local.entity.TransactionType.SALE_RETURN -> "مرجوعی فروش"
    }

val ir.kalapod.app.data.local.entity.PaymentMethod.label: String
    get() = when (this) {
        ir.kalapod.app.data.local.entity.PaymentMethod.CASH -> "نقد"
        ir.kalapod.app.data.local.entity.PaymentMethod.CARD -> "کارت"
        ir.kalapod.app.data.local.entity.PaymentMethod.CREDIT -> "نسیه"
    }

val ir.kalapod.app.data.local.entity.MovementReason.label: String
    get() = when (this) {
        ir.kalapod.app.data.local.entity.MovementReason.PURCHASE -> "خرید"
        ir.kalapod.app.data.local.entity.MovementReason.SALE -> "فروش"
        ir.kalapod.app.data.local.entity.MovementReason.PURCHASE_RETURN -> "مرجوعی خرید"
        ir.kalapod.app.data.local.entity.MovementReason.SALE_RETURN -> "مرجوعی فروش"
        ir.kalapod.app.data.local.entity.MovementReason.ADJUSTMENT -> "اصلاح موجودی"
        ir.kalapod.app.data.local.entity.MovementReason.REVERSAL -> "ابطال سند"
    }

/** Jalali date + HH:mm for an epoch-millis timestamp, in Persian digits. */
fun formatDateTime(epochMillis: Long): String {
    val dt = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
    val d = ir.kalapod.app.core.jalali.JalaliDate.of(dt.toLocalDate())
    return ir.kalapod.app.core.format.PersianFormat.date(d) + " " +
        ir.kalapod.app.core.format.PersianFormat.digits("%02d:%02d".format(dt.hour, dt.minute))
}

/** Whether fractional quantities make sense (kg 2.5 yes, pieces no). */
val ProductUnit.allowsFraction: Boolean
    get() = this == ProductUnit.KILOGRAM || this == ProductUnit.LITER || this == ProductUnit.METER
