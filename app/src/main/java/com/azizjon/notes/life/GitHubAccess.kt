package com.azizjon.notes.life

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The two GitHub API calls the app makes when it connects: who the token is, and which repos it reaches. */
object GitHubAccess {

    data class Account(val login: String, val name: String, val noReplyEmail: String)

    data class RepoRef(val fullName: String, val cloneUrl: String)

    class TokenRejected : IOException("GitHub did not accept this token.")

    fun account(token: String): Account {
        val json = JSONObject(get("https://api.github.com/user", token))
        val login = json.getString("login")
        val name = json.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: login
        return Account(login, name, "${json.getLong("id")}+$login@users.noreply.github.com")
    }

    /** The repos the token can open. A fine-grained token made for one repo lists just that one. */
    fun repos(token: String): List<RepoRef> {
        val array = JSONArray(get("https://api.github.com/user/repos?per_page=100&sort=updated", token))
        return (0 until array.length()).map { i ->
            val repo = array.getJSONObject(i)
            RepoRef(repo.getString("full_name"), repo.getString("clone_url"))
        }
    }

    /** "owner/name" or a GitHub URL -> the HTTPS clone URL; anything else is used as given. */
    fun cloneUrl(repo: String): String {
        val trimmed = repo.trim().removeSuffix("/")
        return when {
            Regex("""^[\w.-]+/[\w.-]+$""").matches(trimmed) -> "https://github.com/${trimmed.removeSuffix(".git")}.git"
            trimmed.startsWith("https://github.com/") && !trimmed.endsWith(".git") -> "$trimmed.git"
            else -> trimmed
        }
    }

    /** "https://github.com/owner/name.git" -> "owner/name". */
    fun repoName(url: String): String =
        url.removeSuffix("/").removeSuffix(".git").split('/').takeLast(2).joinToString("/")

    private fun get(url: String, token: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Notes-Android")
            val code = connection.responseCode
            if (code == 401 || code == 403) throw TokenRejected()
            if (code !in 200..299) throw IOException("GitHub answered $code for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
