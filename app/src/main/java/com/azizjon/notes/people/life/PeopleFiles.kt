package com.azizjon.notes.people.life

import com.azizjon.notes.people.data.AiFeedbackEntity
import com.azizjon.notes.people.data.InteractionEntity
import com.azizjon.notes.people.data.NetworkSnapshot
import com.azizjon.notes.people.data.PeopleDataException
import com.azizjon.notes.people.data.PeopleJson
import com.azizjon.notes.people.data.PeopleJson.mapObjects
import com.azizjon.notes.people.data.PeoplePayload
import com.azizjon.notes.people.data.PersonEntity
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/**
 * People's records as files in the notes repo (life): one JSON file per person in `people/`, named
 * after them, and the reported answers in `people/reports.json` (the user's decision of 2026-11-28,
 * in place of Network App's encrypted backup). The phone writes them from its own records a few
 * seconds after each change, so they are a copy: an edit made in a file is lost at the next save.
 * They are read back only into a phone whose People is empty.
 *
 * Each record is one line, so git keeps a small change for each save and the files stay readable.
 * Contact values stay on the phone and are never written here.
 */
object PeopleFiles {
    const val FOLDER = "people"
    const val DRAFTS = "$FOLDER/drafts"
    const val REPORTS = "$FOLDER/reports.json"

    private const val PERSON_FORMAT = "notes-people-person"
    private const val REPORTS_FORMAT = "notes-people-reports"
    private const val VERSION = 1

    /** Long enough to tell people apart by their file's name; the id keeps every name unique. */
    private const val MAX_NAME = 40

    /** Every file the folder should hold for [payload], by path: the people's, then the reports. */
    fun files(payload: PeoplePayload): Map<String, String> {
        val snapshot = payload.snapshot
        val out = linkedMapOf<String, String>()
        snapshot.people.sortedBy { it.id }.forEach { person -> out[pathOf(person)] = personFile(person, snapshot) }
        out[REPORTS] = reportsFile(payload.feedback)
        return out
    }

    fun pathOf(person: PersonEntity): String = "$FOLDER/${fileName(person)}"

    /** "dana-whitfield-12.json": the name in lower case with anything but letters and digits as one dash, then the id. */
    fun fileName(person: PersonEntity): String {
        val name = Normalizer.normalize(person.name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_NAME)
            .trimEnd('-')
        return if (name.isEmpty()) "person-${person.id}.json" else "$name-${person.id}.json"
    }

    /** A person's own file (not the reports), directly in the folder. */
    fun isPersonFile(path: String): Boolean =
        path.startsWith("$FOLDER/") && path.indexOf('/', FOLDER.length + 1) < 0 &&
            path.endsWith(".json") && path != REPORTS

    /** The id at the end of a person's file name ("dana-whitfield-12.json" is 12), or null without one. */
    fun idOf(path: String): Long? =
        path.substringAfterLast('/').removeSuffix(".json").substringAfterLast('-').toLongOrNull()

    private fun personFile(person: PersonEntity, snapshot: NetworkSnapshot): String {
        val id = person.id
        val sections = listOf(
            "affiliations" to snapshot.affiliations.filter { it.personId == id }.sortedBy { it.id }.map(PeopleJson::affiliation),
            // Newest first, as a reader wants them.
            "interactions" to snapshot.interactions.filter { it.personId == id }
                .sortedWith(compareByDescending<InteractionEntity> { it.occurredAt }.thenByDescending { it.id })
                .map(PeopleJson::interaction),
            "needs" to snapshot.needs.filter { it.personId == id }.sortedBy { it.id }.map(PeopleJson::need),
            "capabilities" to snapshot.capabilities.filter { it.personId == id }.sortedBy { it.id }.map(PeopleJson::capability),
            "facts" to snapshot.facts.filter { it.personId == id }.sortedBy { it.id }.map(PeopleJson::fact),
        )
        return buildString {
            append("{\"format\":\"$PERSON_FORMAT\",\"version\":$VERSION,\"schemaVersion\":${PeopleJson.SCHEMA_VERSION},\n")
            append("\"person\":").append(PeopleJson.person(person, withContact = false).toString())
            sections.forEach { (name, items) -> append(",\n\"").append(name).append("\":").append(lines(items)) }
            append("\n}\n")
        }
    }

    private fun reportsFile(feedback: List<AiFeedbackEntity>): String = buildString {
        append("{\"format\":\"$REPORTS_FORMAT\",\"version\":$VERSION,\n\"reports\":")
        append(lines(feedback.sortedBy { it.id }.map(PeopleJson::feedback)))
        append("\n}\n")
    }

    private fun lines(items: List<JSONObject>): String =
        if (items.isEmpty()) "[]" else items.joinToString(",\n", prefix = "[\n", postfix = "\n]") { it.toString() }

    /**
     * The folder's files ([files] by path, people's and the reports) read back into one payload,
     * checked as a whole. Contact values are not in them, so the people come back without one.
     */
    fun read(files: Map<String, String>): PeoplePayload {
        val people = mutableListOf<PersonEntity>()
        val parts = NetworkSnapshot()
        var snapshot = parts
        var feedback = emptyList<AiFeedbackEntity>()
        files.forEach { (path, text) ->
            val json = runCatching { JSONObject(text) }.getOrElse { throw PeopleDataException("$path is not readable.") }
            when {
                path == REPORTS -> {
                    require(json, REPORTS_FORMAT, path)
                    feedback = json.getJSONArray("reports").mapObjects(PeopleJson::readFeedback)
                }
                isPersonFile(path) -> {
                    require(json, PERSON_FORMAT, path)
                    people += PeopleJson.readPerson(json.getJSONObject("person"))
                    snapshot = snapshot.copy(
                        affiliations = snapshot.affiliations + json.getJSONArray("affiliations").mapObjects(PeopleJson::readAffiliation),
                        interactions = snapshot.interactions + json.getJSONArray("interactions").mapObjects(PeopleJson::readInteraction),
                        needs = snapshot.needs + json.getJSONArray("needs").mapObjects(PeopleJson::readNeed),
                        capabilities = snapshot.capabilities + json.getJSONArray("capabilities").mapObjects(PeopleJson::readCapability),
                        facts = snapshot.facts + json.getJSONArray("facts").mapObjects(PeopleJson::readFact),
                    )
                }
            }
        }
        return PeoplePayload(snapshot.copy(people = people.sortedBy { it.id }), feedback).also(PeopleJson::validate)
    }

    private fun require(json: JSONObject, format: String, path: String) {
        if (json.optString("format") != format || json.optInt("version") != VERSION) {
            throw PeopleDataException("$path was written by a Notes that this one does not know.")
        }
    }
}
