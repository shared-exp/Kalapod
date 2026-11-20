package ir.kalapod.app.data.local

import androidx.room.TypeConverter
import ir.kalapod.app.data.local.entity.MovementReason
import ir.kalapod.app.data.local.entity.PaymentDirection
import ir.kalapod.app.data.local.entity.PaymentMethod
import ir.kalapod.app.data.local.entity.ProductUnit
import ir.kalapod.app.data.local.entity.StockAlertType
import ir.kalapod.app.data.local.entity.TransactionStatus
import ir.kalapod.app.data.local.entity.TransactionType

/** Enums are stored by name so reordering enum constants never corrupts data. */
class Converters {
    @TypeConverter fun unitToString(v: ProductUnit) = v.name
    @TypeConverter fun stringToUnit(v: String) = ProductUnit.valueOf(v)
    @TypeConverter fun txTypeToString(v: TransactionType) = v.name
    @TypeConverter fun stringToTxType(v: String) = TransactionType.valueOf(v)
    @TypeConverter fun txStatusToString(v: TransactionStatus) = v.name
    @TypeConverter fun stringToTxStatus(v: String) = TransactionStatus.valueOf(v)
    @TypeConverter fun methodToString(v: PaymentMethod) = v.name
    @TypeConverter fun stringToMethod(v: String) = PaymentMethod.valueOf(v)
    @TypeConverter fun directionToString(v: PaymentDirection) = v.name
    @TypeConverter fun stringToDirection(v: String) = PaymentDirection.valueOf(v)
    @TypeConverter fun reasonToString(v: MovementReason) = v.name
    @TypeConverter fun stringToReason(v: String) = MovementReason.valueOf(v)
    @TypeConverter fun alertToString(v: StockAlertType) = v.name
    @TypeConverter fun stringToAlert(v: String) = StockAlertType.valueOf(v)
}
