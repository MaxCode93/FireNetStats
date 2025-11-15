package cu.maxwell.firenetstats.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

object NetworkUtils {
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastUpdateTime: Long = 0
    
    // Nuevas variables para seguimiento mensual
    private const val PREFS_NAME = "network_monthly_stats"
    private const val KEY_MONTH_START_RX = "month_start_rx"
    private const val KEY_MONTH_START_TX = "month_start_tx"
    private const val KEY_CURRENT_MONTH = "current_month"
    
    data class NetworkStats(
        val downloadSpeed: String,
        val uploadSpeed: String,
        val downloadSpeedRaw: Float,
        val uploadSpeedRaw: Float,
        val unit: String,
        val downloadUnit: String,
        val uploadUnit: String
    )
    
    // Función existente sin cambios
    fun getNetworkStats(context: Context): NetworkStats {
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()
        
        var downloadSpeedBps = 0.0
        var uploadSpeedBps = 0.0
        
        if (lastUpdateTime > 0) {
            val timeDifference = (currentTime - lastUpdateTime) / 1000.0
            
            if (timeDifference > 0) {
                downloadSpeedBps = (currentRxBytes - lastRxBytes) / timeDifference
                uploadSpeedBps = (currentTxBytes - lastTxBytes) / timeDifference
            }
        }
        
        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
        lastUpdateTime = currentTime
        
        val downloadSpeedRawKBps = downloadSpeedBps.toFloat() / 1024
        val uploadSpeedRawKBps = uploadSpeedBps.toFloat() / 1024
        
        val (downloadSpeedFormatted, downloadUnitFormatted) = formatSpeedWithUnit(downloadSpeedBps)
        val (uploadSpeedFormatted, uploadUnitFormatted) = formatSpeedWithUnit(uploadSpeedBps)
        
        val commonUnit = if (downloadUnitFormatted == "MB/s" || uploadUnitFormatted == "MB/s") "MB/s" else "KB/s"
        
        return NetworkStats(
            downloadSpeed = downloadSpeedFormatted,
            uploadSpeed = uploadSpeedFormatted,
            downloadSpeedRaw = downloadSpeedRawKBps,
            uploadSpeedRaw = uploadSpeedRawKBps,
            unit = commonUnit,
            downloadUnit = downloadUnitFormatted,
            uploadUnit = uploadUnitFormatted
        )
    }
    
    private fun formatSpeedWithUnit(speedBps: Double): Pair<String, String> {
        val df = DecimalFormat("#.##")
        val dfMB = DecimalFormat("#.#")
        
        return when {
            speedBps < 1024 -> {
                Pair(df.format(speedBps), "B/s")
            }
            speedBps < 1024 * 1024 -> {
                val speedKBps = speedBps / 1024
                Pair(speedKBps.toInt().toString(), "KB/s")
            }
            else -> {
                val speedMBps = speedBps / (1024 * 1024)
                Pair(dfMB.format(speedMBps), "MB/s")
            }
        }
    }
    
    // NUEVA FUNCIÓN: Obtener uso de datos del mes actual
    fun getMonthlyDataUsage(context: Context): String {
        checkAndResetMonthlyStats(context)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val monthStartRx = prefs.getLong(KEY_MONTH_START_RX, 0L)
        val monthStartTx = prefs.getLong(KEY_MONTH_START_TX, 0L)
        
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        
        // Si es la primera vez o hay error en los datos
        if (monthStartRx == 0L || monthStartTx == 0L || 
            currentRxBytes < monthStartRx || currentTxBytes < monthStartTx) {
            initializeMonthlyStats(context)
            return "0 B"
        }
        
        val monthlyRxBytes = currentRxBytes - monthStartRx
        val monthlyTxBytes = currentTxBytes - monthStartTx
        val totalMonthlyBytes = monthlyRxBytes + monthlyTxBytes
        
        return formatBytes(totalMonthlyBytes)
    }
    
    // Función existente sin cambios (para compatibilidad)
    fun getDataUsage(context: Context): String {
        val totalRxBytes = TrafficStats.getTotalRxBytes()
        val totalTxBytes = TrafficStats.getTotalTxBytes()
        val totalBytes = totalRxBytes + totalTxBytes
        return formatBytes(totalBytes)
    }
    
    // Verificar si cambió el mes y reiniciar estadísticas
    private fun checkAndResetMonthlyStats(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentMonth = getCurrentMonth()
        val savedMonth = prefs.getString(KEY_CURRENT_MONTH, "")
        
        if (currentMonth != savedMonth) {
            // Cambió el mes, reiniciar estadísticas
            initializeMonthlyStats(context)
        }
    }
    
    // Inicializar estadísticas del mes
    private fun initializeMonthlyStats(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentMonth = getCurrentMonth()
        
        prefs.edit()
            .putLong(KEY_MONTH_START_RX, currentRxBytes)
            .putLong(KEY_MONTH_START_TX, currentTxBytes)
            .putString(KEY_CURRENT_MONTH, currentMonth)
            .apply()
    }
    
    // Obtener mes actual en formato "YYYY-MM"
    private fun getCurrentMonth(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        return dateFormat.format(Date())
    }
    
    // Función para reiniciar manualmente las estadísticas mensuales
    fun resetMonthlyStats(context: Context) {
        initializeMonthlyStats(context)
    }
    
    // Función existente sin cambios
    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        val df = DecimalFormat("#.##")
        return when {
            gb >= 1 -> "${df.format(gb)} GB"
            mb >= 1 -> "${df.format(mb)} MB"
            kb >= 1 -> "${df.format(kb)} KB"
            else -> "${bytes} B"
        }
    }
    
    // Funciones existentes sin cambios
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    
    fun isMobileDataConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
    
    fun resetCounters() {
        lastRxBytes = 0
        lastTxBytes = 0
        lastUpdateTime = 0
    }
}
