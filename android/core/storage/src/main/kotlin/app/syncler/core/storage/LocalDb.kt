package app.syncler.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Placeholder entity so Room is happy. V1 stores everything via
 * EncryptedSharedPreferences (PairedSenderStore, PendingUpdatesStore,
 * EncryptedUserState). Real schema lands when M1.5/V1.5 wires structured
 * local data — e.g. cached inbox messages, plugin install state.
 */
@Entity(tableName = "kv_placeholder")
data class KvPlaceholder(
    @PrimaryKey val id: Long,
    val payload: String,
)

@Database(entities = [KvPlaceholder::class], version = 1, exportSchema = true)
abstract class LocalDb : RoomDatabase()

@Module
@InstallIn(SingletonComponent::class)
object LocalDbModule {
    private const val DB_NAME = "syncler.db"
    private const val DB_PASSPHRASE_KEY = "local_db_passphrase_v1"

    @Provides
    @Singleton
    fun provideLocalDb(@ApplicationContext context: Context, securePrefs: SecurePrefs): LocalDb {
        val passphrase = securePrefs.getString(DB_PASSPHRASE_KEY)?.base64ToBytes()
            ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                securePrefs.putString(DB_PASSPHRASE_KEY, it.toBase64())
            }
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, LocalDb::class.java, DB_NAME)
            .openHelperFactory(factory)
            .build()
    }
}
