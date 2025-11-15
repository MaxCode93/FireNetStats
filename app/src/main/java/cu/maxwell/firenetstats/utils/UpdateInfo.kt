package cu.maxwell.firenetstats.utils

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkListsUrl: String = "https://www.apklis.cu/application/cu.maxwell.firenetstats",
    val publishedAt: String = ""
)

data class UpdateState(
    val available: Boolean = false,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val changelog: String = "",
    val apkListsUrl: String = "https://www.apklis.cu/application/cu.maxwell.firenetstats",
    val error: String? = null
)
