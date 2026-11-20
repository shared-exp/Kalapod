package ir.kalapod.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.kalapod.app.data.local.KalapodDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): KalapodDatabase =
        Room.databaseBuilder(ctx, KalapodDatabase::class.java, KalapodDatabase.NAME).build()

    @Provides fun categoryDao(db: KalapodDatabase) = db.categoryDao()
    @Provides fun productDao(db: KalapodDatabase) = db.productDao()
    @Provides fun barcodeDao(db: KalapodDatabase) = db.barcodeDao()
    @Provides fun inventoryDao(db: KalapodDatabase) = db.inventoryDao()
    @Provides fun transactionDao(db: KalapodDatabase) = db.transactionDao()
    @Provides fun invoiceDao(db: KalapodDatabase) = db.invoiceDao()
    @Provides fun partyDao(db: KalapodDatabase) = db.partyDao()
    @Provides fun paymentDao(db: KalapodDatabase) = db.paymentDao()
    @Provides fun stockAlertDao(db: KalapodDatabase) = db.stockAlertDao()
    @Provides fun settingDao(db: KalapodDatabase) = db.settingDao()
    @Provides fun balanceDao(db: KalapodDatabase) = db.balanceDao()
}
