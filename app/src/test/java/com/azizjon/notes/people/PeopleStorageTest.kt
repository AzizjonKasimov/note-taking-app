package com.azizjon.notes.people

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * People's records and settings must never go into Android's own backup: Network App turned that
 * backup off, and Notes keeps it on for its settings and folder markers. So both rule files must
 * leave out every file [PeopleStorage] lists.
 */
class PeopleStorageTest {
    /** Unit tests run in the module's folder. */
    private val xmlFolder = File("src/main/res/xml")

    @Test
    fun androidBackupLeavesOutEveryPeopleFile() {
        val sections = listOf(
            "backup_rules.xml" to "full-backup-content",
            "data_extraction_rules.xml" to "cloud-backup",
            "data_extraction_rules.xml" to "device-transfer",
        )
        for ((file, section) in sections) {
            val excluded = excludes(File(xmlFolder, file), section)
            PeopleStorage.databaseFiles.forEach { name ->
                assertTrue("$file <$section> must leave out the database file $name", ("database" to name) in excluded)
            }
            PeopleStorage.preferenceFiles.forEach { name ->
                assertTrue("$file <$section> must leave out the preferences $name", ("sharedpref" to name) in excluded)
            }
        }
    }

    @Test
    fun everyPeopleFileIsNamedForPeople() {
        // Notes deletes Network App's old preference names at every start; a "people" name never clashes.
        (PeopleStorage.databaseFiles + PeopleStorage.preferenceFiles).forEach { name ->
            assertTrue("$name must start with people", name.startsWith("people"))
        }
    }

    private fun excludes(file: File, section: String): Set<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.getElementsByTagName(section).item(0) as Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()
    }
}
