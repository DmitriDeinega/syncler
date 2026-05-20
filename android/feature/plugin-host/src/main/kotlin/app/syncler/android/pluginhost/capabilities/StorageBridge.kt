package app.syncler.android.pluginhost.capabilities

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.syncler.android.pluginhost.AuditLogger
import app.syncler.android.pluginhost.PluginInstance
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.storage.SecurePrefs
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class StorageBridge(
    context: Context,
    private val auditLogger: AuditLogger,
) {
    private val db: PluginStorageDb by lazy { PluginStorageDb.open(context.applicationContext) }

    suspend fun get(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val key = args["key"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        val scope = scopeFrom(args)
        val value = db.dao().get(plugin.manifest.id, scope, key)
        JsonBridgeCodec.toJson(mapOf("value" to value))
    }

    suspend fun set(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val key = args["key"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        val value = args["value"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        db.dao().upsert(PluginStorageRow(plugin.manifest.id, scopeFrom(args), key, value))
        "{}"
    }

    suspend fun delete(plugin: PluginInstance, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = JsonBridgeCodec.objectFrom(argsJson)
        val key = args["key"] as? String ?: return@withContext JsonBridgeCodec.error("invalid_args")
        db.dao().delete(plugin.manifest.id, scopeFrom(args), key)
        "{}"
    }

    private fun scopeFrom(args: Map<String, Any?>): String {
        val opts = args["opts"] as? Map<*, *>
        return opts?.get("scope") as? String ?: "device"
    }
}

@Entity(tableName = "plugin_storage", primaryKeys = ["pluginId", "scope", "key"])
data class PluginStorageRow(
    val pluginId: String,
    val scope: String,
    val key: String,
    val value: String,
)

@Dao
interface PluginStorageDao {
    @Query("SELECT value FROM plugin_storage WHERE pluginId = :pluginId AND scope = :scope AND `key` = :key")
    suspend fun get(pluginId: String, scope: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PluginStorageRow)

    @Query("DELETE FROM plugin_storage WHERE pluginId = :pluginId AND scope = :scope AND `key` = :key")
    suspend fun delete(pluginId: String, scope: String, key: String)
}

@Database(entities = [PluginStorageRow::class], version = 1, exportSchema = true)
abstract class PluginStorageDb : RoomDatabase() {
    abstract fun dao(): PluginStorageDao

    companion object {
        private const val DB_NAME = "pluginhost_storage.db"
        private const val PASSPHRASE_KEY = "pluginhost_storage_passphrase_v1"

        fun open(context: Context): PluginStorageDb {
            val securePrefs = SecurePrefs(context)
            val passphrase = securePrefs.getString(PASSPHRASE_KEY)?.base64ToBytes()
                ?: ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    securePrefs.putString(PASSPHRASE_KEY, it.toBase64())
                }
            return Room.databaseBuilder(context, PluginStorageDb::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
