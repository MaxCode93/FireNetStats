package cu.maxwell.firenetstats.utils

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class UpdateRepository {
    private val httpClient = OkHttpClient()
    private val githubApiUrl = "https://api.github.com/repos/MaxCode93/fireNetStats/releases/latest"

    suspend fun fetchLatestRelease(): UpdateInfo? {
        return try {
            val request = Request.Builder()
                .url(githubApiUrl)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                parseReleaseResponse(body)
            } else {
                null
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun parseReleaseResponse(jsonResponse: String): UpdateInfo? {
        return try {
            val jsonObject = JsonParser.parseString(jsonResponse).asJsonObject
            
            val tagName = jsonObject.get("tag_name")?.asString ?: "0.0"
            
            // Extraer solo números del tag y eliminar caracteres especiales
            // Ejemplo: "beta-1.2" → "121", "v1.2.1" → "121"
            val cleanVersionCode = extractVersionCode(tagName)
            val versionName = tagName.removePrefix("v").removePrefix("beta-")
            
            val body = jsonObject.get("body")?.asString ?: ""
            val changelog = parseChangelog(body)
            
            val publishedAt = jsonObject.get("published_at")?.asString ?: ""
            
            UpdateInfo(
                versionName = versionName,
                versionCode = cleanVersionCode,
                changelog = changelog,
                apkListsUrl = "https://www.apklis.cu/application/cu.maxwell.firenetstats",
                publishedAt = publishedAt
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extrae el versionCode del tag con formato de 3 dígitos
     * Ejemplos:
     * "beta-1.2" → "120" (1.2.0)
     * "v1.2.1" → "121"
     * "v1.3" → "130" (1.3.0)
     * "v2.0" → "200" (2.0.0)
     */
    private fun extractVersionCode(tag: String): Int {
        // Remover prefijos comunes (v, beta-, etc)
        var clean = tag.removePrefix("v").removePrefix("beta").removePrefix("-")
        
        // Remover puntos y otros caracteres
        clean = clean.replace(".", "")
        clean = clean.replace(Regex("[^0-9]"), "")
        
        // Si no hay dígitos, retornar 0
        if (clean.isEmpty()) {
            return 0
        }
        
        // Normalizar a exactamente 3 dígitos
        // "12" → "120", "121" → "121", "2" → "200"
        return try {
            when {
                clean.length >= 3 -> clean.substring(0, 3).toInt()  // Tomar primeros 3
                clean.length == 2 -> (clean + "0").toInt()          // "12" → "120"
                clean.length == 1 -> (clean + "00").toInt()         // "1" → "100"
                else -> 0
            }
        } catch (e: NumberFormatException) {
            0
        }
    }

    private fun parseChangelog(body: String): String {
        // Si el changelog es muy largo, tomar solo los primeros 500 caracteres
        return if (body.length > 500) {
            body.substring(0, 500) + "..."
        } else {
            body.trim()
        }
    }
}
