package com.azizjon.notes.people.checkin

import android.content.pm.PackageManager
import android.os.Build
import com.azizjon.notes.BuildConfig
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The check-in's answers from Network App, handed over once on the phone when the user moves to
 * Notes (2.5). Network App never put them in its backup, by its privacy rule (they are the phone's
 * own bookkeeping), so without this every contact the user answered "Never" would be asked about
 * again.
 *
 * Network App 0.21 sends them app to app and never off the phone: an explicit intent to
 * [CheckinHandoverActivity] with a content address of a JSON file, started for a result so that
 * Notes knows who sent it. Notes takes them only from Network App signed with its own key, and
 * Network App sends them only to Notes signed with Notes' key.
 */
object CheckinHandover {
    const val ACTION = "com.azizjon.notes.action.TAKE_NETWORK_APP_CHECKINS"
    const val FORMAT = "network-app-checkins"
    const val VERSION = 1
    const val EXTRA_COUNT = "count"
    const val EXTRA_ERROR = "error"

    const val NETWORK_APP_PACKAGE = "com.azizjon.network"

    /** Network App's release key (its `release-signing-cert.sha256`), and in test builds this PC's debug key. */
    val networkAppCerts: Set<String> = buildSet {
        add("34ef3571c61eabf4cfa859fb627a2de200140f79a10fbe6bd8151c254d067888")
        if (BuildConfig.DEBUG) add(DEBUG_CERT)
    }

    /** The debug key that signs test builds of both apps on the owner's PC. */
    private const val DEBUG_CERT = "c608c689f985fa6338695938f6ad7103253ec7d62cee4676015393187e15dade"

    /** More rows than any phone's contacts and chats, so a broken file cannot fill the database. */
    private const val MAX_ROWS = 20_000

    private val STATUSES = setOf(
        CheckinEntity.STATUS_OPEN,
        CheckinEntity.STATUS_DONE,
        CheckinEntity.STATUS_LATER,
        CheckinEntity.STATUS_NEVER,
    )

    /** The file Network App writes. */
    fun encode(rows: List<CheckinEntity>): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put(
            "rows",
            JSONArray(
                rows.map { row ->
                    JSONObject()
                        .put("ref", row.ref)
                        .put("source", row.source)
                        .put("name", row.name)
                        .put("firstSeenAt", row.firstSeenAt)
                        .put("lastSeenAt", row.lastSeenAt)
                        .put("status", row.status)
                        .put("statusAt", row.statusAt)
                },
            ),
        )
        .toString()

    /** Reads the file Network App wrote, or throws [IllegalArgumentException] with a reason. */
    fun decode(text: String): List<CheckinEntity> {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("The hand-over file could not be read.") }
        require(root.optString("format") == FORMAT) { "This is not a check-in hand-over from Network App." }
        require(root.optInt("version") == VERSION) { "This hand-over comes from a Network App that Notes does not know." }
        val rows = root.optJSONArray("rows") ?: throw IllegalArgumentException("The hand-over holds no answers.")
        require(rows.length() <= MAX_ROWS) { "The hand-over holds too many rows." }
        return (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: throw IllegalArgumentException("Row $index is not readable.")
            val ref = row.optString("ref")
            val status = row.optString("status")
            require(ref.isNotBlank() && ref.length <= 500) { "Row $index has no usable reference." }
            require(status in STATUSES) { "Row $index has an unknown answer." }
            CheckinEntity(
                ref = ref,
                source = row.optString("source"),
                name = row.optString("name").take(500),
                firstSeenAt = row.optLong("firstSeenAt"),
                lastSeenAt = row.optLong("lastSeenAt"),
                status = status,
                statusAt = row.optLong("statusAt"),
            )
        }
    }

    /** Whether [packageName] is installed and signed with one of [certs] (SHA-256, hex). */
    fun signedBy(packageManager: PackageManager, packageName: String, certs: Set<String>): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            certs.any { cert -> packageManager.hasSigningCertificate(packageName, hexBytes(cert), PackageManager.CERT_INPUT_SHA256) }
        } else {
            @Suppress("DEPRECATION")
            val signatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            signatures.any { sha256Hex(it.toByteArray()) in certs }
        }
    }.getOrDefault(false)

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
