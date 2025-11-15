package cu.maxwell.firenetstats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cu.maxwell.firenetstats.utils.NetworkUtils
import java.util.Timer
import java.util.TimerTask

class NetworkStatsService : Service() {
    private var timer: Timer? = null
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Crear notificación para servicio en primer plano
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Iniciar monitoreo de red
        startNetworkMonitoring()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FireNetStats Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitorea estadísticas de red en segundo plano"
                enableLights(false)
                lightColor = Color.BLUE
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
	private fun startNetworkMonitoring() {
		timer = Timer()
		timer?.scheduleAtFixedRate(object : TimerTask() {
			override fun run() {
				val networkStats = NetworkUtils.getNetworkStats(this@NetworkStatsService)
				
				// Ejemplo de broadcast para actualizar la UI principal
				val intent = Intent("cu.maxwell.firenetstats.NETWORK_STATS_UPDATE")
				intent.putExtra("download_speed", networkStats.downloadSpeed)
				intent.putExtra("upload_speed", networkStats.uploadSpeed)
				intent.putExtra("download_unit", networkStats.downloadUnit)
				intent.putExtra("upload_unit", networkStats.uploadUnit)
				sendBroadcast(intent)
			}
		}, 0, 1000)
	}
    
    companion object {
        private const val CHANNEL_ID = "FireNetStatsServiceChannel"
        private const val NOTIFICATION_ID = 2
    }
}
