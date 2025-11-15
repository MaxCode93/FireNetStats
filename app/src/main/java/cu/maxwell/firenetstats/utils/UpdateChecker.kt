package cu.maxwell.firenetstats.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UpdateChecker(private val context: Context) {
    private val updateManager = UpdateManager(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    interface UpdateCheckListener {
        fun onUpdateAvailable(updateState: UpdateState)
        fun onNoUpdate()
        fun onCheckError(error: String)
    }

    fun checkForUpdates(listener: UpdateCheckListener? = null, forceCheck: Boolean = false) {
        scope.launch {
            try {
                val updateState = updateManager.checkForUpdates(forceCheck)
                
                when {
                    updateState.error != null -> {
                        listener?.onCheckError(updateState.error!!)
                    }
                    updateState.available -> {
                        listener?.onUpdateAvailable(updateState)
                    }
                    else -> {
                        listener?.onNoUpdate()
                    }
                }
            } catch (e: Exception) {
                listener?.onCheckError(e.message ?: "Error desconocido")
            }
        }
    }

    fun isUpdateAvailable(): Boolean = updateManager.isUpdateAvailable()

    fun getUpdateInfo(): UpdateState = updateManager.getUpdateInfo()
}
