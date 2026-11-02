package com.nexus.agent.domain.tool

import com.nexus.agent.data.connectors.GoogleScopePolicy
import com.nexus.agent.data.remote.GmailSendApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Authenticated, read-only Google API adapter. Model input cannot supply arbitrary URLs:
 * each tool constructs its own fixed Google host, resource path, bounds and parameters.
 */
class GoogleWorkspaceApiClient(
    private val tokenFor: suspend (List<String>) -> String?,
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun get(url: HttpUrl, scopes: List<String>): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (url.isHttps.not() || url.host !in ALLOWED_HOSTS) {
            return@withContext Result.failure(SecurityException("google_host_not_allowed"))
        }
        val token = tokenFor(scopes)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure(SecurityException("google_permission_missing"))
        try {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("google_http_${response.code}"))
                }
                val text = response.body?.string().orEmpty()
                if (text.length > 180_000) {
                    Result.failure(IllegalStateException("google_response_too_large"))
                } else Result.success(JSONObject(text))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFile(mime: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        val token = tokenFor(listOf(GoogleScopePolicy.DRIVE_FILE))
            ?: return@withContext Result.failure(SecurityException("drive_file_permission_missing"))
        try {
            val body = JSONObject().put("name", name).put("mimeType", mime).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val url = "https://www.googleapis.com/drive/v3/files?fields=id,name,webViewLink".toHttpUrl()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token").post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("drive_http_${response.code}"))
                }
                val id = JSONObject(response.body?.string().orEmpty()).optString("id")
                if (id.isBlank()) Result.failure(IllegalStateException("drive_missing_file_id"))
                else Result.success(id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private val ALLOWED_HOSTS = setOf(
            "www.googleapis.com", "people.googleapis.com", "tasks.googleapis.com"
        )
    }
}

private fun JsonObject.stringArg(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

/** Drive full-text file listing uses read-only Drive access; no file contents are modified. */
class GoogleDriveSearchTool(private val api: GoogleWorkspaceApiClient) : Tool {
    override val name = "google_drive_search"
    override val description = "Find Google Drive file names and links (read-only)."
    override val requiredScopes = listOf(GoogleScopePolicy.DRIVE_READ)
    override suspend fun execute(input: JsonObject): ToolResult {
        val query = input.stringArg("query").take(120)
        if (query.isBlank()) return ToolResult.Failure("empty_query")
        val intent = DriveSearchIntentParser.parse(query)

        // Bounded, escaped, read-only searches (at most MAX_QUERIES). A filename cannot inject
        // query syntax: each literal is escaped and wrapped in a fixed `name contains` clause.
        val found = LinkedHashMap<String, DriveFile>()
        var anySuccess = false
        for (term in intent.queries.take(MAX_QUERIES)) {
            val escaped = term.replace("\\", "\\\\").replace("'", "\\'")
            val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "trashed = false and name contains '$escaped'")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,webViewLink)")
                .addQueryParameter("pageSize", "20").build()
            val result = api.get(url, requiredScopes)
            if (result.isSuccess) {
                anySuccess = true
                val files = result.getOrNull()?.optJSONArray("files")
                for (i in 0 until (files?.length() ?: 0)) {
                    val file = files?.optJSONObject(i) ?: continue
                    val id = file.optString("id").take(100)
                    if (id.isBlank()) continue
                    found.putIfAbsent(id, DriveFile(
                        id = id,
                        name = file.optString("name").take(150),
                        mimeType = file.optString("mimeType").take(100),
                        url = file.optString("webViewLink").takeIf { it.startsWith("https://") }
                    ))
                }
            }
        }
        if (!anySuccess) return ToolResult.Failure("drive_search_failed")

        // For an identity request, drop code/asset files entirely (the reported `.js`/`.map`
        // false positives); for other requests keep everything, ranked.
        val ranked = DriveFileRanker.rank(found.values.toList(), intent)
            .let { list ->
                if (intent.wantsIdentityDocument) {
                    list.filterNot { DriveFileRanker.isCodeLike(it.name, it.mimeType) }
                } else list
            }
            .take(MAX_RESULTS)
        if (ranked.isEmpty()) {
            return ToolResult.Success(
                if (intent.wantsIdentityDocument) IDENTITY_NOT_FOUND else "No matching Drive files"
            )
        }

        if (intent.wantsIdentityDocument) {
            // Never assert ownership: only surface files whose name carries an identity term,
            // and always attach a "review it yourself" disclaimer.
            val strong = ranked.filter { file ->
                intent.identityTerms.any { file.name.lowercase().contains(it) }
            }
            if (strong.isEmpty()) {
                return ToolResult.Success(
                    IDENTITY_UNCLEAR + "\n" + ranked.joinToString("\n") { "${it.name} [${it.id}]" },
                    ranked.map(::toSource)
                )
            }
            return ToolResult.Success(
                OWNERSHIP_DISCLAIMER + "\n" + strong.joinToString("\n") { "${it.name} [${it.id}]" },
                strong.map(::toSource)
            )
        }

        return ToolResult.Success(
            ranked.joinToString("\n") { "${it.name} [${it.id}]" },
            ranked.map(::toSource)
        )
    }

    private fun toSource(file: DriveFile) = ToolSource(
        title = file.name,
        project = "Google Drive",
        file = file.id,
        url = file.url
    )

    private companion object {
        const val MAX_QUERIES = 3
        const val MAX_RESULTS = 10
        const val OWNERSHIP_DISCLAIMER =
            "لم أستطع التحقق من أن هذه البطاقة/الهوية تخصّك؛ هذه ملفات مرشّحة للمراجعة (وليست تأكيدًا للملكية)."
        const val IDENTITY_UNCLEAR =
            "لم أجد ملف هوية واضحًا يطابق طلبك، ولم أخمّن ملفًا لتجنّب نسبة بطاقة شخص آخر إليك. هذه أقرب النتائج للمراجعة:"
        const val IDENTITY_NOT_FOUND =
            "لم أجد أي ملف بطاقة هوية مطابق. حدّد اسم الملف أو نوعه (PDF/صورة) لأبحث بدقة، ولن أفترض ملفًا لك."
    }
}

/** Single Gmail email send. ToolOrchestrator requires a one-shot user approval receipt. */
class GmailSendTool(private val api: GmailSendApiClient) : Tool {
    override val name = "gmail_send"
    override val description =
        "Send ONE email to an explicit recipient. Requires the user's approval of recipient, subject and body."
    override val riskLevel = RiskLevel.COMMIT
    override val requiredScopes = listOf(GoogleScopePolicy.GMAIL_SEND)
    override val parameterSchema = ToolSchema.parameters(listOf(
        ToolSchema.Parameter("to", "Single recipient email address"),
        ToolSchema.Parameter("subject", "Email subject"),
        ToolSchema.Parameter("body", "Plain-text email body")
    ))
    override suspend fun execute(input: JsonObject): ToolResult {
        val to = input.stringArg("to")
        val subject = input.stringArg("subject")
        val body = input.stringArg("body")
        return api.send(to, subject, body).fold(
            onSuccess = { ToolResult.Success("Gmail accepted one message; sent message id: $it") },
            onFailure = { ToolResult.Failure("gmail_send_failed") }
        )
    }
}

/** Drive files created for this app. NEVER deletes or modifies existing content. */
class GoogleDriveCreateTool(private val api: GoogleWorkspaceApiClient) : Tool {
    override val name = "google_drive_create"
    override val description = "Create a new empty Google Doc, Sheet or Slides file (requires approval)."
    override val riskLevel = RiskLevel.COMMIT
    override val requiredScopes = listOf(GoogleScopePolicy.DRIVE_FILE)
    override val parameterSchema = ToolSchema.parameters(listOf(
        ToolSchema.Parameter("name", "New file name"),
        ToolSchema.Parameter("type", "Must be exactly doc, sheet or slides")
    ))
    override suspend fun execute(input: JsonObject): ToolResult {
        val name = input.stringArg("name")
        if (name.isBlank() || name.length > 150 || name.any { it.isISOControl() }) {
            return ToolResult.Failure("invalid_file_name")
        }
        val mime = when (input.stringArg("type")) {
            "doc" -> "application/vnd.google-apps.document"
            "sheet" -> "application/vnd.google-apps.spreadsheet"
            "slides" -> "application/vnd.google-apps.presentation"
            else -> return ToolResult.Failure("invalid_file_type")
        }
        return api.createFile(mime, name).fold(
            onSuccess = { ToolResult.Success("Created new Google file with ID: $it") },
            onFailure = { ToolResult.Failure("drive_create_failed") }
        )
    }
}

/** Bounded, read-only upcoming Calendar event listing (primary calendar only). */
class GoogleCalendarUpcomingTool(private val api: GoogleWorkspaceApiClient) : Tool {
    override val name = "google_calendar_upcoming"
    override val description = "List the next 10 upcoming Google Calendar events."
    override val requiredScopes = listOf(GoogleScopePolicy.CALENDAR_READ)
    override val parameterSchema = ToolSchema.parameters(emptyList())
    override suspend fun execute(input: JsonObject): ToolResult {
        val now = java.time.Instant.now().toString()
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
            .toHttpUrl().newBuilder()
            .addQueryParameter("timeMin", now)
            .addQueryParameter("singleEvents", "true")
            .addQueryParameter("orderBy", "startTime")
            .addQueryParameter("maxResults", "10").build()
        return api.get(url, requiredScopes).fold(
            onSuccess = { data ->
                val items = data.optJSONArray("items")
                val rows = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                    items?.optJSONObject(i)
                }.map { event ->
                    "${event.optJSONObject("start")?.optString("dateTime")
                        ?: event.optJSONObject("start")?.optString("date")}: ${event.optString("summary").take(180)}"
                }
                ToolResult.Success(rows.joinToString("\n").ifBlank { "No upcoming events" })
            },
            onFailure = { ToolResult.Failure("calendar_read_failed") }
        )
    }
}

/** Bounded, read-only Google Tasks task-list discovery. */
class GoogleTasksListsTool(private val api: GoogleWorkspaceApiClient) : Tool {
    override val name = "google_tasks_lists"
    override val description = "Read Google Tasks list names; does not create, delete or modify tasks."
    override val requiredScopes = listOf(GoogleScopePolicy.TASKS_READ)
    override val parameterSchema = ToolSchema.parameters(emptyList())
    override suspend fun execute(input: JsonObject): ToolResult {
        val url = "https://tasks.googleapis.com/tasks/v1/users/@me/lists"
            .toHttpUrl().newBuilder().addQueryParameter("maxResults", "20").build()
        return api.get(url, requiredScopes).fold(
            onSuccess = { data ->
                val items = data.optJSONArray("items")
                val rows = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                    items?.optJSONObject(i)?.optString("title")?.take(180)
                }
                ToolResult.Success(rows.joinToString("\n").ifBlank { "No task lists" })
            },
            onFailure = { ToolResult.Failure("tasks_read_failed") }
        )
    }
}

/** Bounded contact listing; never sends contact information externally except to the model. */
class GoogleContactsListTool(private val api: GoogleWorkspaceApiClient) : Tool {
    override val name = "google_contacts_list"
    override val description = "Read up to 20 Google Contacts names and email addresses."
    override val requiredScopes = listOf(GoogleScopePolicy.CONTACTS_READ)
    override val parameterSchema = ToolSchema.parameters(emptyList())
    override suspend fun execute(input: JsonObject): ToolResult {
        val url = "https://people.googleapis.com/v1/people/me/connections"
            .toHttpUrl().newBuilder()
            .addQueryParameter("personFields", "names,emailAddresses")
            .addQueryParameter("pageSize", "20").build()
        return api.get(url, requiredScopes).fold(
            onSuccess = { data ->
                val items = data.optJSONArray("connections")
                val rows = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                    items?.optJSONObject(i)
                }.map { person ->
                    val name = person.optJSONArray("names")?.optJSONObject(0)?.optString("displayName")
                    val email = person.optJSONArray("emailAddresses")?.optJSONObject(0)?.optString("value")
                    "${name.orEmpty().take(100)}: ${email.orEmpty().take(160)}"
                }
                ToolResult.Success(rows.joinToString("\n").ifBlank { "No contacts" })
            },
            onFailure = { ToolResult.Failure("contacts_read_failed") }
        )
    }
}
