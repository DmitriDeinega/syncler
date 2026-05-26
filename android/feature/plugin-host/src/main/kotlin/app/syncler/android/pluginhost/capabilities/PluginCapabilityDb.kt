package app.syncler.android.pluginhost.capabilities

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.syncler.core.crypto.base64ToBytes
import app.syncler.core.crypto.toBase64
import app.syncler.core.storage.SecurePrefs
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Phase 12 (V2 #10) plugin capability state — SQLCipher-backed,
 * two tables:
 *
 *  - `plugin_capability_grants` records that a user has consented
 *    to a (plugin_row_id, capability) pair. Inserted after the
 *    first successful invocation for UI-consent capabilities
 *    (`camera`, `gallery`, `file`); inserted after OS permission
 *    grant for service-call capabilities (`location.coarse`,
 *    `location.fine`). Settings-sheet revoke deletes the row.
 *  - `plugin_capability_audit` records every invocation
 *    (success or failure). Per-invocation per codex 137; the
 *    audit table is the forensic source of truth. Coordinates,
 *    filenames, URIs, and handle strings are NEVER recorded.
 *
 * Schema reference: `docs/plugin-capability-expansion.md`
 * "Grant model" + "Audit log entry per invocation". Encryption
 * mirrors `PluginStorageDb` — SQLCipher passphrase from
 * `SecurePrefs`, lazy first-run generation.
 */
@Entity(tableName = "plugin_capability_grants", primaryKeys = ["pluginRowId", "capability"])
data class PluginCapabilityGrantRow(
    val pluginRowId: String,
    val capability: String,
    val grantedAtMs: Long,
    // last_invoked_at_ms updated on each successful invocation so
    // the settings UI can show "last used X ago" per grant.
    val lastInvokedAtMs: Long? = null,
)

@Entity(tableName = "plugin_capability_audit")
data class PluginCapabilityAuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val pluginRowId: String,
    val capability: String,
    val sandboxToken: Int,
    val callId: String,
    val atMs: Long,
    val durationMs: Long,
    /** "success" or one of the SDK error taxonomy codes. */
    val outcome: String,
    /** "activity_result" or "service_call". */
    val surface: String,
    /** Staged size for camera/gallery/file; null otherwise. */
    val sizeBytes: Long? = null,
    /** Selected count for gallery; null otherwise. */
    val itemCount: Int? = null,
    /** "coarse" or "fine" for location; null otherwise. */
    val precision: String? = null,
    /** Provider-stated MIME for picker capabilities; null otherwise. */
    val mimeClaimed: String? = null,
    /** Magic-byte sniff result for picker capabilities; null otherwise. */
    val mimeDetected: String? = null,
)

@Dao
interface PluginCapabilityGrantDao {
    @Query(
        "SELECT * FROM plugin_capability_grants " +
            "WHERE pluginRowId = :pluginRowId AND capability = :capability"
    )
    suspend fun get(pluginRowId: String, capability: String): PluginCapabilityGrantRow?

    @Query("SELECT * FROM plugin_capability_grants WHERE pluginRowId = :pluginRowId")
    suspend fun forPlugin(pluginRowId: String): List<PluginCapabilityGrantRow>

    /**
     * V2 closeout triad 142 codex #1: Settings UI lists ALL
     * stored grants regardless of whether the plugin is
     * currently loaded. The legacy `forPlugin(...)` only
     * surfaced loaded plugins' rows.
     */
    @Query("SELECT * FROM plugin_capability_grants ORDER BY grantedAtMs DESC")
    suspend fun all(): List<PluginCapabilityGrantRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PluginCapabilityGrantRow)

    @Query(
        "UPDATE plugin_capability_grants SET lastInvokedAtMs = :atMs " +
            "WHERE pluginRowId = :pluginRowId AND capability = :capability"
    )
    suspend fun markInvoked(pluginRowId: String, capability: String, atMs: Long)

    @Query(
        "DELETE FROM plugin_capability_grants " +
            "WHERE pluginRowId = :pluginRowId AND capability = :capability"
    )
    suspend fun delete(pluginRowId: String, capability: String)

    @Query("DELETE FROM plugin_capability_grants WHERE pluginRowId = :pluginRowId")
    suspend fun deleteAllForPlugin(pluginRowId: String)
}

@Dao
interface PluginCapabilityAuditDao {
    @Insert
    suspend fun insert(row: PluginCapabilityAuditRow): Long

    @Query(
        "SELECT * FROM plugin_capability_audit " +
            "WHERE pluginRowId = :pluginRowId " +
            "ORDER BY atMs DESC LIMIT :limit"
    )
    suspend fun recentForPlugin(pluginRowId: String, limit: Int): List<PluginCapabilityAuditRow>

    /**
     * Settings UI: show "last successful invocation" per
     * (plugin, capability) pair. Returns null if no row exists.
     */
    @Query(
        "SELECT atMs FROM plugin_capability_audit " +
            "WHERE pluginRowId = :pluginRowId AND capability = :capability " +
            "AND outcome = 'success' " +
            "ORDER BY atMs DESC LIMIT 1"
    )
    suspend fun lastSuccessAt(pluginRowId: String, capability: String): Long?

    /**
     * Retention pruning: delete rows older than [olderThanMs].
     * Runs from PluginRegistry's existing periodic cleanup pass.
     */
    @Query("DELETE FROM plugin_capability_audit WHERE atMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long): Int
}

@Database(
    entities = [PluginCapabilityGrantRow::class, PluginCapabilityAuditRow::class],
    version = 1,
    exportSchema = true,
)
abstract class PluginCapabilityDb : RoomDatabase() {
    abstract fun grants(): PluginCapabilityGrantDao
    abstract fun audit(): PluginCapabilityAuditDao

    companion object {
        private const val DB_NAME = "pluginhost_capability.db"
        private const val PASSPHRASE_KEY = "pluginhost_capability_passphrase_v1"

        fun open(context: Context): PluginCapabilityDb {
            val securePrefs = SecurePrefs(context)
            val passphrase = securePrefs.getString(PASSPHRASE_KEY)?.base64ToBytes()
                ?: ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    securePrefs.putString(PASSPHRASE_KEY, it.toBase64())
                }
            return Room.databaseBuilder(context, PluginCapabilityDb::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
