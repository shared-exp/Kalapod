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

/** Whether fractional quantities make sense (kg 2.5 yes, pieces no). */
val ProductUnit.allowsFraction: Boolean
    get() = this == ProductUnit.KILOGRAM || this == ProductUnit.LITER || this == ProductUnit.METER
