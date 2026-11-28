package com.azizjon.notes.people.move

import android.content.pm.PackageManager
import android.os.Build
import com.azizjon.notes.BuildConfig
import com.azizjon.notes.people.checkin.CheckinEntity
import com.azizjon.notes.people.data.PeopleJson
import com.azizjon.notes.people.data.PeoplePayload
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Network App's move into Notes (2.5), done once on the phone. Network App 0.21's "Move to Notes"
 * saves a last backup of its own, then hands Notes everything in one file: its records with
 * contact values ([PeoplePayload], in its backup's shape before encryption) and the check-in's
 * answers, which never were in its backup. It goes app to app and never leaves the phone: an
 * explicit intent to [MoveFromNetworkAppActivity] with a content address, started for a result so
 * that Notes knows who sent it. Notes takes it only from Network App signed with Network App's key,
 * and Network App sends it only to Notes signed with Notes' key.
 */
object MoveFromNetworkApp {
    const val ACTION = "com.azizjon.notes.action.MOVE_FROM_NETWORK_APP"
    const val FORMAT = "network-app-move"
    const val VERSION = 1
    const val EXTRA_PEOPLE = "people"
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
    private const val MAX_CHECKIN_ROWS = 20_000

    private val STATUSES = setOf(
        CheckinEntity.STATUS_OPEN,
        CheckinEntity.STATUS_DONE,
        CheckinEntity.STATUS_LATER,
        CheckinEntity.STATUS_NEVER,
    )

    data class Move(val payload: PeoplePayload, val checkins: List<CheckinEntity>)

    /** What Network App writes (kept here so that the format has one test on this side). */
    fun encode(payload: JSONObject, checkins: List<CheckinEntity>): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("payload", payload)
        .put("checkins", JSONArray(checkins.map(::checkinJson)))
        .toString()

    /** Reads the file Network App wrote, or throws with a reason; nothing is written before it is all checked. */
    fun decode(text: String): Move {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("The move's file could not be read.") }
        require(root.optString("format") == FORMAT) { "This is not a move from Network App." }
        require(root.optInt("version") == VERSION) { "This move comes from a Network App that Notes does not know." }
        val payload = PeopleJson.payloadFromJson(
            root.optJSONObject("payload") ?: throw IllegalArgumentException("The move holds no records."),
        )
        val rows = root.optJSONArray("checkins") ?: JSONArray()
        require(rows.length() <= MAX_CHECKIN_ROWS) { "The move holds too many check-in rows." }
        val checkins = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: throw IllegalArgumentException("Check-in row $index is not readable.")
            val ref = row.optString("ref")
            val status = row.optString("status")
            require(ref.isNotBlank() && ref.length <= 500) { "Check-in row $index has no usable reference." }
            require(status in STATUSES) { "Check-in row $index has an unknown answer." }
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
        return Move(payload, checkins)
    }

    private fun checkinJson(row: CheckinEntity) = JSONObject()
        .put("ref", row.ref)
        .put("source", row.source)
        .put("name", row.name)
        .put("firstSeenAt", row.firstSeenAt)
        .put("lastSeenAt", row.lastSeenAt)
        .put("status", row.status)
        .put("statusAt", row.statusAt)

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
