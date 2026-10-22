package com.pocketforge.ai.data.catalogue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalogue of models the application is allowed to work with.
 *
 * The catalogue ships in assets/model_catalogue.json with verified size + SHA-256 +
 * revision for each entry. Downloaded files are validated against these values; the app
 * never silently substitutes a different file, repo, revision or quantization.
 */
data class CatalogueSource(
    val kind: String,
    val repository: String,
    val revision: String,
    val file: String,
    val url: String,
)

data class CatalogueModel(
    val id: String,
    val name: String,
    val family: String,
    val precision: String,
    val parameterCount: String,
    val sizeBytes: Long,
    val sha256: String,
    val trainedContext: Long,
    val role: String,
    val source: CatalogueSource,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
    val description: String,
    val notes: String,
) {
    val isTestModel: Boolean get() = role == "test"
}

object ModelCatalog {
    fun parse(json: String): List<CatalogueModel> {
        val root = JSONObject(json)
        val models = root.getJSONArray("models")
        return (0 until models.length()).map { models.getJSONObject(it).toModel() }
    }

    private fun JSONObject.toModel(): CatalogueModel {
        val src = getJSONObject("source")
        return CatalogueModel(
            id = getString("id"),
            name = getString("name"),
            family = getString("family"),
            precision = getString("precision"),
            parameterCount = getString("parameterCount"),
            sizeBytes = getLong("sizeBytes"),
            sha256 = getString("sha256"),
            trainedContext = getLong("trainedContext"),
            role = getString("role"),
            source = CatalogueSource(
                kind = src.getString("kind"),
                repository = src.getString("repository"),
                revision = src.getString("revision"),
                file = src.getString("file"),
                url = src.getString("url"),
            ),
            license = optStringSafe("license"),
            licenseUrl = optStringSafe("licenseUrl"),
            attribution = optStringSafe("attribution"),
            description = optStringSafe("description"),
            notes = optStringSafe("notes"),
        )
    }

    private fun JSONObject.optStringSafe(key: String): String = try { getString(key) } catch (_: Exception) { optString(key, "") }

    /** Parse a JSONArray-derived list of models (test helper reuse). */
    fun parseArray(models: JSONArray): List<CatalogueModel> =
        (0 until models.length()).map { models.getJSONObject(it).toModel() }
}

class CatalogRepository(context: Context) {
    private val cache: List<CatalogueModel> by lazy {
        context.assets.open("model_catalogue.json").bufferedReader().use { it.readText() }
            .let { ModelCatalog.parse(it) }
    }

    val models: List<CatalogueModel> get() = cache

    fun byId(id: String): CatalogueModel? = models.firstOrNull { it.id == id }
}
