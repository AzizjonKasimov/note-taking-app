package com.azizjon.notes.people.data

import org.json.JSONArray
import org.json.JSONObject

class PeopleDataException(message: String) : Exception(message)

/**
 * Everything People keeps that leaves the phone's database: the records, and reported assistant
 * answers beside them. Reports never go inside a [NetworkSnapshot], which the screens and the
 * assistant are built from.
 */
data class PeoplePayload(
    val snapshot: NetworkSnapshot,
    val feedback: List<AiFeedbackEntity> = emptyList(),
)

/**
 * People's records as JSON, one record to an object, with the names Network App's backup used, so
 * that its hand-over, the files in the notes repo ([com.azizjon.notes.people.life.PeopleFiles]),
 * and the PC lookup all read the same shapes. Contact values travel only in the hand-over between
 * the two apps on the phone; the files in the notes repo leave them out.
 */
object PeopleJson {
    /** Network App's schema when it moved into Notes (0.21), and so the files' too. */
    const val SCHEMA_VERSION = 10

    /** A sane ceiling, so a damaged file cannot flood the reports table. */
    private const val MAX_FEEDBACK_ROWS = 5_000

    fun person(p: PersonEntity, withContact: Boolean): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name)
        .put("location", p.location)
        .apply { if (withContact) put("contact", p.contact) }
        .put("relationship", p.relationship)
        .put("tags", p.tags).put("notes", p.notes).put("isSelf", p.isSelf).put("archived", p.archived)
        .put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)
        .put("aliases", p.aliases).put("formerAliases", p.formerAliases)

    fun interaction(i: InteractionEntity): JSONObject = JSONObject()
        .put("id", i.id).put("personId", i.personId).put("note", i.note)
        .put("occurredAt", i.occurredAt).put("createdAt", i.createdAt).put("origin", i.origin)

    fun need(n: NeedEntity): JSONObject = JSONObject()
        .put("id", n.id).put("personId", n.personId).put("text", n.text).put("status", n.status)
        .put("lastConfirmedAt", n.lastConfirmedAt).put("createdAt", n.createdAt)
        .put("sourceInteractionId", n.sourceInteractionId ?: JSONObject.NULL)
        .put("helpedAt", n.helpedAt ?: JSONObject.NULL)

    fun capability(c: CapabilityEntity): JSONObject = JSONObject()
        .put("id", c.id).put("personId", c.personId).put("text", c.text)
        .put("lastConfirmedAt", c.lastConfirmedAt).put("createdAt", c.createdAt)
        .put("active", c.active)
        .put("sourceInteractionId", c.sourceInteractionId ?: JSONObject.NULL)

    fun affiliation(a: AffiliationEntity): JSONObject = JSONObject()
        .put("id", a.id).put("personId", a.personId)
        .put("organization", a.organization).put("role", a.role).put("current", a.current)
        .put("kind", a.kind)
        .put("lastConfirmedAt", a.lastConfirmedAt).put("createdAt", a.createdAt)
        .put("sourceInteractionId", a.sourceInteractionId ?: JSONObject.NULL)

    fun fact(f: FactEntity): JSONObject = JSONObject()
        .put("id", f.id).put("personId", f.personId).put("text", f.text)
        .put("lastConfirmedAt", f.lastConfirmedAt).put("createdAt", f.createdAt)
        .put("sourceInteractionId", f.sourceInteractionId ?: JSONObject.NULL)

    fun feedback(r: AiFeedbackEntity): JSONObject = JSONObject()
        .put("id", r.id).put("stage", r.stage).put("label", r.label).put("note", r.note)
        .put("userMessage", r.userMessage).put("assistantMessage", r.assistantMessage)
        .put("assistantDetail", r.assistantDetail).put("appVersion", r.appVersion)
        .put("createdAt", r.createdAt)

    fun readPerson(item: JSONObject): PersonEntity = PersonEntity(
        id = item.getLong("id"),
        name = item.getString("name"),
        location = item.optString("location"),
        // Present only in the hand-over from Network App: the notes repo's files leave it out.
        contact = item.optString("contact"),
        relationship = item.optString("relationship"),
        tags = item.optString("tags"),
        notes = item.optString("notes"),
        isSelf = item.optBoolean("isSelf"),
        archived = item.optBoolean("archived"),
        createdAt = item.getLong("createdAt"),
        updatedAt = item.getLong("updatedAt"),
        aliases = item.optString("aliases"),
        formerAliases = item.optString("formerAliases"),
    )

    fun readInteraction(item: JSONObject) = InteractionEntity(
        id = item.getLong("id"), personId = item.getLong("personId"),
        note = item.getString("note"), occurredAt = item.getLong("occurredAt"),
        createdAt = item.getLong("createdAt"),
        origin = item.optString("origin", InteractionEntity.ORIGIN_MANUAL),
    )

    fun readNeed(item: JSONObject) = NeedEntity(
        id = item.getLong("id"), personId = item.getLong("personId"),
        text = item.getString("text"), status = item.optString("status", NeedEntity.STATUS_ACTIVE),
        lastConfirmedAt = item.getLong("lastConfirmedAt"), createdAt = item.getLong("createdAt"),
        sourceInteractionId = item.optionalLong("sourceInteractionId"),
        helpedAt = item.optionalLong("helpedAt"),
    )

    fun readCapability(item: JSONObject) = CapabilityEntity(
        id = item.getLong("id"), personId = item.getLong("personId"),
        text = item.getString("text"), lastConfirmedAt = item.getLong("lastConfirmedAt"),
        createdAt = item.getLong("createdAt"),
        active = item.optBoolean("active", true),
        sourceInteractionId = item.optionalLong("sourceInteractionId"),
    )

    fun readAffiliation(item: JSONObject) = AffiliationEntity(
        id = item.getLong("id"),
        personId = item.getLong("personId"),
        organization = item.optString("organization"),
        role = item.optString("role"),
        current = item.optBoolean("current", true),
        lastConfirmedAt = item.getLong("lastConfirmedAt"),
        createdAt = item.getLong("createdAt"),
        sourceInteractionId = item.optionalLong("sourceInteractionId"),
        kind = item.optString("kind", AffiliationEntity.KIND_WORK)
            .takeIf { it == AffiliationEntity.KIND_EDUCATION }
            ?: AffiliationEntity.KIND_WORK,
    )

    fun readFact(item: JSONObject) = FactEntity(
        id = item.getLong("id"),
        personId = item.getLong("personId"),
        text = item.getString("text"),
        lastConfirmedAt = item.getLong("lastConfirmedAt"),
        createdAt = item.getLong("createdAt"),
        sourceInteractionId = item.optionalLong("sourceInteractionId"),
    )

    fun readFeedback(item: JSONObject) = AiFeedbackEntity(
        id = item.getLong("id"),
        stage = item.optString("stage", AiFeedbackEntity.Stage.MESSAGE),
        label = item.getString("label"),
        note = item.optString("note"),
        userMessage = item.optString("userMessage"),
        assistantMessage = item.optString("assistantMessage"),
        assistantDetail = item.optString("assistantDetail"),
        appVersion = item.optString("appVersion"),
        createdAt = item.getLong("createdAt"),
    )

    /**
     * The hand-over's records from Network App 0.21: its backup's content before encryption, which
     * carries contact values. Checked as a whole before anything is written.
     */
    fun payloadFromJson(json: JSONObject): PeoplePayload {
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) {
            throw PeopleDataException("These records come from a Network App that Notes does not know.")
        }
        val snapshot = NetworkSnapshot(
            people = json.getJSONArray("people").mapObjects(::readPerson),
            interactions = json.getJSONArray("interactions").mapObjects(::readInteraction),
            needs = json.getJSONArray("needs").mapObjects(::readNeed),
            capabilities = json.getJSONArray("capabilities").mapObjects(::readCapability),
            affiliations = json.optJSONArray("affiliations").orEmpty().mapObjects(::readAffiliation),
            facts = json.optJSONArray("facts").orEmpty().mapObjects(::readFact),
        )
        val feedback = json.optJSONArray("feedback").orEmpty().mapObjects(::readFeedback)
        return PeoplePayload(snapshot, feedback).also(::validate)
    }

    /** Refuses records whose ids repeat or whose links point at nothing, before any is written. */
    fun validate(payload: PeoplePayload) {
        val snapshot = payload.snapshot
        val personIds = snapshot.people.map { it.id }.toSet()
        val interactionIds = snapshot.interactions.map { it.id }.toSet()
        if (personIds.size != snapshot.people.size || snapshot.people.any { it.id <= 0 || it.name.isBlank() }) {
            throw PeopleDataException("The records hold invalid people.")
        }
        if (interactionIds.size != snapshot.interactions.size ||
            snapshot.interactions.any {
                it.id <= 0 || it.personId !in personIds || it.note.isBlank() ||
                    it.origin !in InteractionEntity.ORIGINS
            } ||
            snapshot.needs.map { it.id }.toSet().size != snapshot.needs.size ||
            snapshot.needs.any {
                it.id <= 0 || it.personId !in personIds || it.text.isBlank() ||
                    it.status !in setOf(NeedEntity.STATUS_ACTIVE, NeedEntity.STATUS_CLOSED) ||
                    (it.sourceInteractionId != null && it.sourceInteractionId !in interactionIds)
            } ||
            snapshot.capabilities.map { it.id }.toSet().size != snapshot.capabilities.size ||
            snapshot.capabilities.any {
                it.id <= 0 || it.personId !in personIds || it.text.isBlank() ||
                    (it.sourceInteractionId != null && it.sourceInteractionId !in interactionIds)
            } ||
            snapshot.affiliations.map { it.id }.toSet().size != snapshot.affiliations.size ||
            snapshot.affiliations.any {
                it.id <= 0 || it.personId !in personIds ||
                    (it.organization.isBlank() && it.role.isBlank()) ||
                    (it.sourceInteractionId != null && it.sourceInteractionId !in interactionIds)
            } ||
            snapshot.facts.map { it.id }.toSet().size != snapshot.facts.size ||
            snapshot.facts.any {
                it.id <= 0 || it.personId !in personIds || it.text.isBlank() ||
                    (it.sourceInteractionId != null && it.sourceInteractionId !in interactionIds)
            }
        ) {
            throw PeopleDataException("The records hold invalid linked records.")
        }
        val feedback = payload.feedback
        if (feedback.size > MAX_FEEDBACK_ROWS ||
            feedback.map { it.id }.toSet().size != feedback.size ||
            feedback.any { it.id <= 0 || it.label.isBlank() }
        ) {
            throw PeopleDataException("The records hold invalid reports.")
        }
    }

    private fun JSONObject.optionalLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        buildList { for (index in 0 until length()) add(transform(getJSONObject(index))) }
}
