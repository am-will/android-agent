package dev.androidagent.avatar

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class PetAsset(
    val id: String,
    val displayName: String,
    val description: String,
    val spritesheetFile: File,
    val mtimeMs: Long,
    val sizeBytes: Long
)

object AvatarLibrary {
    private const val TAG = "AvatarLibrary"
    private const val AVATARS_DIR = "avatars"
    private const val INDEX_FILE = "index.json"
    private const val SPRITESHEET_FILE = "spritesheet.webp"

    private val bootScanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AvatarLibrary-boot-scan").apply { isDaemon = true }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun listCached(context: Context): List<PetAsset> {
        val avatarsDir = File(context.filesDir, AVATARS_DIR)
        val indexFile = File(avatarsDir, INDEX_FILE)
        if (!indexFile.exists()) {
            return emptyList()
        }
        return try {
            val parsed = JSONArray(indexFile.readText())
            (0 until parsed.length()).mapNotNull { i ->
                val obj = parsed.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val file = File(File(avatarsDir, id), SPRITESHEET_FILE)
                if (!file.exists()) return@mapNotNull null
                PetAsset(
                    id = id,
                    displayName = obj.optString("displayName").ifBlank { id },
                    description = obj.optString("description"),
                    spritesheetFile = file,
                    mtimeMs = obj.optLong("mtimeMs", 0L),
                    sizeBytes = obj.optLong("sizeBytes", file.length())
                )
            }
        } catch (error: Throwable) {
            Log.w(TAG, "failed to parse cached avatar index: ${error.message}")
            emptyList()
        }
    }

    fun findCached(context: Context, id: String): PetAsset? {
        return listCached(context).firstOrNull { it.id == id }
    }

    /**
     * Re-fetches the pet catalog from the PC bridge and synchronizes the
     * local cache. Returns a [Result] so callers can render an error inline
     * when the user opened the avatar menu and the host can't be reached.
     */
    fun refreshFromHost(context: Context, hostUrl: String): Result<List<PetAsset>> {
        val httpBase = deriveHttpBase(hostUrl) ?: return Result.failure(
            IOException("Bridge URL is not set. Configure it under Connection & Config.")
        )
        return try {
            val summaries = fetchPetSummaries(httpBase)
            val avatarsDir = File(context.filesDir, AVATARS_DIR).apply { mkdirs() }
            val existing = listCached(context).associateBy { it.id }
            val result = mutableListOf<PetAsset>()
            for (summary in summaries) {
                val petDir = File(avatarsDir, summary.id).apply { mkdirs() }
                val target = File(petDir, SPRITESHEET_FILE)
                val cached = existing[summary.id]
                val needsDownload = cached == null ||
                    cached.mtimeMs != summary.mtimeMs ||
                    cached.sizeBytes != summary.sizeBytes ||
                    !target.exists() ||
                    target.length() != summary.sizeBytes
                if (needsDownload) {
                    downloadSpritesheet(httpBase, summary.id, target)
                }
                result.add(
                    PetAsset(
                        id = summary.id,
                        displayName = summary.displayName,
                        description = summary.description,
                        spritesheetFile = target,
                        mtimeMs = summary.mtimeMs,
                        sizeBytes = summary.sizeBytes
                    )
                )
            }
            pruneRemovedPets(avatarsDir, summaries.map { it.id }.toSet())
            writeIndex(avatarsDir, result)
            Result.success(result)
        } catch (error: IOException) {
            Log.w(TAG, "avatar refresh failed: ${error.message}")
            Result.failure(error)
        } catch (error: Throwable) {
            Log.w(TAG, "unexpected avatar refresh failure: ${error.message}")
            Result.failure(IOException(error.message ?: "Avatar refresh failed", error))
        }
    }

    /**
     * Fire-and-forget background refresh used at boot. Failures are only
     * logged so the user never sees a connection error when the bridge is
     * offline.
     */
    fun scanOnBoot(context: Context, hostUrl: String) {
        bootScanExecutor.execute {
            val result = refreshFromHost(context.applicationContext, hostUrl)
            result.onFailure { Log.i(TAG, "boot scan deferred: ${it.message}") }
        }
    }

    internal fun deriveHttpBase(hostUrl: String): String? {
        val trimmed = hostUrl.trim()
        if (trimmed.isEmpty()) return null
        val withHttpScheme = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        val pathStart = withHttpScheme.indexOf('/', withHttpScheme.indexOf("://") + 3)
        return if (pathStart < 0) withHttpScheme.trimEnd('/') else withHttpScheme.substring(0, pathStart).trimEnd('/')
    }

    private data class PetSummary(
        val id: String,
        val displayName: String,
        val description: String,
        val sizeBytes: Long,
        val mtimeMs: Long
    )

    private fun fetchPetSummaries(httpBase: String): List<PetSummary> {
        val request = Request.Builder().url("$httpBase/api/pets").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET /api/pets responded ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val payload = try {
                JSONObject(body)
            } catch (error: Throwable) {
                throw IOException("Bridge returned invalid pet catalog: ${error.message}")
            }
            val array = payload.optJSONArray("pets") ?: return emptyList()
            val summaries = mutableListOf<PetSummary>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                summaries.add(
                    PetSummary(
                        id = id,
                        displayName = obj.optString("displayName").ifBlank { id },
                        description = obj.optString("description"),
                        sizeBytes = obj.optLong("spritesheetSizeBytes", 0L),
                        mtimeMs = obj.optLong("spritesheetMtimeMs", 0L)
                    )
                )
            }
            return summaries
        }
    }

    private fun downloadSpritesheet(httpBase: String, petId: String, target: File) {
        val request = Request.Builder().url("$httpBase/api/pets/$petId/spritesheet").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET /api/pets/$petId/spritesheet responded ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty spritesheet body for $petId")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IOException("Failed to swap spritesheet into place for $petId")
                }
            }
        }
    }

    private fun pruneRemovedPets(avatarsDir: File, keep: Set<String>) {
        val children = avatarsDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && child.name !in keep) {
                child.deleteRecursively()
            }
        }
    }

    private fun writeIndex(avatarsDir: File, assets: List<PetAsset>) {
        val array = JSONArray()
        for (asset in assets) {
            array.put(
                JSONObject()
                    .put("id", asset.id)
                    .put("displayName", asset.displayName)
                    .put("description", asset.description)
                    .put("mtimeMs", asset.mtimeMs)
                    .put("sizeBytes", asset.sizeBytes)
            )
        }
        val indexFile = File(avatarsDir, INDEX_FILE)
        val tmp = File(avatarsDir, "$INDEX_FILE.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }
}
