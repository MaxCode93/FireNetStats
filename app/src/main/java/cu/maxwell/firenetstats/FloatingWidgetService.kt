package cu.maxwell.firenetstats

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cu.maxwell.firenetstats.databinding.WidgetFloatingBinding
import cu.maxwell.firenetstats.databinding.WidgetRemoveAreaBinding
import cu.maxwell.firenetstats.utils.NetworkUtils
import java.util.Timer
import java.util.TimerTask
import androidx.core.graphics.toColorInt

class FloatingWidgetService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var widgetBinding: WidgetFloatingBinding
    private lateinit var removeAreaBinding: WidgetRemoveAreaBinding
    private lateinit var widgetView: View
    private lateinit var removeAreaView: View
    private var timer: Timer? = null
    private lateinit var connectivityReceiver: BroadcastReceiver
    private var isWidgetVisible = true
	
	private var originalSize: String? = null
	private var originalTransparency: Int = 20
	private var originalDisplayInfo: String? = null
	private var originalTheme: String? = null
	private var originalWidgetColor: String? = null
	private var originalDownloadArrowColor: Int = 0
	private var originalDownloadTextColor: Int = 0
	private var originalUploadArrowColor: Int = 0
	private var originalUploadTextColor: Int = 0
	private var originalNetworkIconColor: Int = 0
	private var isInPreviewMode = false
	
	companion object {
		private const val CHANNEL_ID = "FireNetStatsServiceChannel"
		private const val NOTIFICATION_ID = 1
		var isRunning = false
		var currentX = -1
		var currentY = -1
		var isMainAppForeground = false
		var isSettingsOpen = false
		
		fun isServiceRunning(context: Context): Boolean {
			val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
			return manager.getRunningServices(Integer.MAX_VALUE)
				.any { it.service.className == FloatingWidgetService::class.java.name }
		}
	}
    
	private val isSmallScreen: Boolean by lazy {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        screenWidth < 720 // Consideramos pantalla pequeña si es menor a 720px
    }
	
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isRemoveAreaVisible = false
    private var lastTapTime = 0L
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

	override fun onCreate() {
		super.onCreate()
		
		if (isRunning) {
			stopSelf()
			return
		}
		isRunning = true
		
		createNotificationChannel()
		val notification = createNotification()
		startForeground(NOTIFICATION_ID, notification)
		
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		
		widgetBinding = WidgetFloatingBinding.inflate(LayoutInflater.from(this))
		widgetView = widgetBinding.root
		
		removeAreaBinding = WidgetRemoveAreaBinding.inflate(LayoutInflater.from(this))
		removeAreaView = removeAreaBinding.root
		
		// Modificar la elevación del widget para que esté por encima
		widgetView.elevation = 10f  // Un valor alto para asegurar que esté por encima
		removeAreaView.elevation = 5f  // Un valor menor para que esté por debajo

		applyWidgetSettings()
		setupWidget()
		startSpeedUpdates()
		setupConnectivityReceiver()
	}
    
	override fun onDestroy() {
		super.onDestroy()
		isRunning = false
		timer?.cancel()
		
		try {
			unregisterReceiver(connectivityReceiver)
		} catch (e: Exception) {
			e.printStackTrace()
		}
		
		try {
			if (::widgetView.isInitialized && widgetView.isAttachedToWindow) {
				windowManager.removeView(widgetView)
			}
			
			if (::removeAreaView.isInitialized && removeAreaView.isAttachedToWindow) {
				windowManager.removeView(removeAreaView)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			"UPDATE_WIDGET_SETTINGS" -> {
				// Aplicar nuevas configuraciones sin reiniciar completamente
				if (::widgetBinding.isInitialized) {
					// Guardar la posición actual antes de aplicar los cambios
					var oldX = -1
					var oldY = -1
					if (widgetView.isAttachedToWindow) {
						val params = widgetView.layoutParams as WindowManager.LayoutParams
						oldX = params.x
						oldY = params.y
					}

					applyWidgetSettings()

					// Verificar si el widget está fuera de los límites de la pantalla
					checkAndAdjustPosition()

					// Si teníamos una posición anterior, restaurarla para mantener el lado izquierdo fijo
					if (oldX >= 0 && widgetView.isAttachedToWindow) {
						widgetView.post {
							try {
								val params = widgetView.layoutParams as WindowManager.LayoutParams
								params.x = oldX
								params.y = oldY
								windowManager.updateViewLayout(widgetView, params)

								// Guardar la posición
								currentX = oldX
								currentY = oldY
							} catch (e: Exception) {
								e.printStackTrace()
							}
						}
					}
				}
				return START_STICKY
			}
			"MAIN_APP_FOREGROUND" -> {
				isMainAppForeground = true
				updateWidgetVisibility()
				return START_STICKY
			}
			"MAIN_APP_BACKGROUND" -> {
				isMainAppForeground = false
				updateWidgetVisibility()
				return START_STICKY
			}
			"SETTINGS_OPEN" -> {
				isSettingsOpen = true
				updateWidgetVisibility()
				return START_STICKY
			}
			"SETTINGS_CLOSED" -> {
				isSettingsOpen = false
				updateWidgetVisibility()
				return START_STICKY
			}
			"SERVICE_STATE_REQUEST" -> {
				// Responder con el estado actual del servicio
				sendServiceStateBroadcast(isRunning)
				return START_STICKY
			}
			"PREVIEW_WIDGET_SETTINGS" -> {
				// Si no estamos en modo vista previa, guardar la configuración original
				if (!isInPreviewMode) {
					saveOriginalSettings()
					isInPreviewMode = true
				}

				// Aplicar configuraciones de vista previa (sin guardar)
				if (::widgetBinding.isInitialized) {
					// Obtener valores del intent
					val size = intent.getStringExtra("size") ?: "medium"
					val transparency = intent.getIntExtra("transparency", 20)
					val displayInfo = intent.getStringExtra("display_info") ?: "both"
					val theme = intent.getStringExtra("theme") ?: "dark"
					val widgetColorString = intent.getStringExtra("widget_color") ?: "default"
					val downloadArrowColor = intent.getIntExtra("download_arrow_color",
						ContextCompat.getColor(this, R.color.secondary_color))
					val downloadTextColor = intent.getIntExtra("download_text_color",
						ContextCompat.getColor(this, R.color.secondary_color))
					val uploadArrowColor = intent.getIntExtra("upload_arrow_color",
						ContextCompat.getColor(this, R.color.primary_color))
					val uploadTextColor = intent.getIntExtra("upload_text_color",
						ContextCompat.getColor(this, R.color.primary_color))
					val networkIconColor = intent.getIntExtra("network_icon_color",
						ContextCompat.getColor(this, R.color.good_connection))

					// Aplicar configuraciones temporales
					applyTemporarySettings(size, transparency, displayInfo, theme, widgetColorString,
						downloadArrowColor, downloadTextColor, uploadArrowColor, uploadTextColor, networkIconColor)
				}
				return START_STICKY
			}
			"CANCEL_PREVIEW" -> {
				// Restaurar configuración original si estábamos en modo vista previa
				if (isInPreviewMode && ::widgetBinding.isInitialized) {
					restoreOriginalSettings()
					isInPreviewMode = false
				}
				return START_STICKY
			}
			else -> {
				// Comando de inicio normal - verificar estado inicial
				checkInitialAppState()
				return START_STICKY
			}
		}
	}

	private fun saveOriginalSettings() {
		val prefs = getSharedPreferences("widget_settings", MODE_PRIVATE)
		
		originalSize = prefs.getString("size", "medium")
		originalTransparency = prefs.getInt("transparency", 20)
		originalDisplayInfo = prefs.getString("display_info", "both")
		originalTheme = prefs.getString("theme", "dark")
		originalWidgetColor = prefs.getString("widget_color", "default")
		originalDownloadArrowColor = prefs.getInt("download_arrow_color", 
			ContextCompat.getColor(this, R.color.secondary_color))
		originalDownloadTextColor = prefs.getInt("download_text_color", 
			ContextCompat.getColor(this, R.color.secondary_color))
		originalUploadArrowColor = prefs.getInt("upload_arrow_color", 
			ContextCompat.getColor(this, R.color.primary_color))
		originalUploadTextColor = prefs.getInt("upload_text_color", 
			ContextCompat.getColor(this, R.color.primary_color))
		originalNetworkIconColor = prefs.getInt("network_icon_color", 
			ContextCompat.getColor(this, R.color.good_connection))
	}
	
	private fun restoreOriginalSettings() {
		if (originalSize != null) {
			applyTemporarySettings(originalSize!!, originalTransparency, originalDisplayInfo!!, 
				originalTheme!!, originalWidgetColor!!, originalDownloadArrowColor, 
				originalDownloadTextColor, originalUploadArrowColor, originalUploadTextColor, 
				originalNetworkIconColor)
		}
	}

	// Método para aplicar configuraciones temporales
	private fun applyTemporarySettings(size: String, transparency: Int, displayInfo: String, 
									  theme: String, widgetColorString: String, 
									  downloadArrowColor: Int, downloadTextColor: Int, 
									  uploadArrowColor: Int, uploadTextColor: Int, 
									  networkIconColor: Int) {
		// Aplicar tamaño
		val displayMetrics = resources.displayMetrics
		val screenWidth = displayMetrics.widthPixels
		val isSmallScreen = screenWidth < 720
		
		val scale = when {
			isSmallScreen -> when(size) {
				"small" -> 0.6f
				"medium" -> 0.7f
				"large" -> 0.8f
				else -> 0.7f
			}
			else -> when(size) {
				"small" -> 0.8f
				"medium" -> 1.0f
				"large" -> 1.2f
				else -> 1.0f
			}
		}
		
		widgetBinding.cardWidget.scaleX = scale
		widgetBinding.cardWidget.scaleY = scale
		
		// Aplicar transparencia
		val alpha = 1 - (transparency / 100f)
		widgetBinding.cardWidget.alpha = alpha
		
		// Aplicar visibilidad según displayInfo
		when (displayInfo) {
			"download_only" -> {
				widgetBinding.tvWidgetDownload.visibility = View.VISIBLE
				widgetBinding.tvWidgetUpload.visibility = View.GONE
			}
			"upload_only" -> {
				widgetBinding.tvWidgetDownload.visibility = View.GONE
				widgetBinding.tvWidgetUpload.visibility = View.VISIBLE
			}
			else -> {
				widgetBinding.tvWidgetDownload.visibility = View.VISIBLE
				widgetBinding.tvWidgetUpload.visibility = View.VISIBLE
			}
		}
		
		// Aplicar color de fondo
		val isDarkTheme = theme == "dark"
		
		val bgColor = if (widgetColorString == "default") {
			if (isDarkTheme) "#CC121212".toColorInt() else "#CCFFFFFF".toColorInt()
		} else {
			try {
                widgetColorString.toColorInt()
			} catch (e: Exception) {
				if (isDarkTheme) "#CC121212".toColorInt() else "#CCFFFFFF".toColorInt()
			}
		}
		
		widgetBinding.cardWidget.setCardBackgroundColor(bgColor)
		
		// Aplicar colores a flechas y textos
		// Aquí necesitamos acceder a las vistas de flechas y textos
		// Asumiendo que tienes ImageView para las flechas
		val downloadArrowView = widgetBinding.root.findViewById<ImageView>(R.id.ivDownloadArrow)
		val uploadArrowView = widgetBinding.root.findViewById<ImageView>(R.id.ivUploadArrow)
		
		if (downloadArrowView != null) downloadArrowView.setColorFilter(downloadArrowColor)
		if (uploadArrowView != null) uploadArrowView.setColorFilter(uploadArrowColor)
		
		// Aplicar colores a los textos
		widgetBinding.tvWidgetDownload.setTextColor(downloadTextColor)
		widgetBinding.tvWidgetUpload.setTextColor(uploadTextColor)
		
		// Aplicar color al icono de red si existe
		val networkIconView = widgetBinding.root.findViewById<ImageView>(R.id.ivNetworkType)
		if (networkIconView != null) networkIconView.setColorFilter(networkIconColor)
	}

	private fun checkAndAdjustPosition() {
		if (::windowManager.isInitialized && widgetView.isAttachedToWindow) {
			val displayMetrics = resources.displayMetrics
			val screenWidth = displayMetrics.widthPixels
			val screenHeight = displayMetrics.heightPixels
			
			val widgetWidth = widgetView.width
			val widgetHeight = widgetView.height
			
			// Obtener los parámetros actuales
			val params = WindowManager.LayoutParams()
			params.copyFrom(widgetView.layoutParams as WindowManager.LayoutParams)
			
			var needsUpdate = false
			
			// Ajustar solo si está completamente fuera de los límites
			if (params.x + widgetWidth < 0) {
				params.x = 0
				needsUpdate = true
			}
			if (params.y + widgetHeight < 0) {
				params.y = 0
				needsUpdate = true
			}
			if (params.x > screenWidth) {
				params.x = screenWidth - widgetWidth
				needsUpdate = true
			}
			if (params.y > screenHeight) {
				params.y = screenHeight - widgetHeight
				needsUpdate = true
			}
			
			// Actualizar la posición si fue ajustada
			if (needsUpdate) {
				windowManager.updateViewLayout(widgetView, params)
			}
			
			// Guardar la nueva posición
			currentX = params.x
			currentY = params.y
		}
	}
 
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FireNetStats Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra estadísticas de red en tiempo real"
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
    
	@SuppressLint("ClickableViewAccessibility")
    private fun setupWidget() {
		val displayMetrics = resources.displayMetrics
		val screenWidth = displayMetrics.widthPixels
		val screenHeight = displayMetrics.heightPixels
		
		// Usar la posición guardada o la posición inicial
		val startX = if (currentX >= 0) currentX else (screenWidth * 0.8).toInt() - 100
		val startY = if (currentY >= 0) currentY else (screenHeight * 0.1).toInt()
		
		val widgetParams = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
			else 
				WindowManager.LayoutParams.TYPE_PHONE,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			PixelFormat.TRANSLUCENT
		).apply {
			gravity = Gravity.TOP or Gravity.START
			x = startX
			y = startY
		}
		
		val removeAreaParams = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
			else 
				WindowManager.LayoutParams.TYPE_PHONE,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			PixelFormat.TRANSLUCENT
		).apply {
			gravity = Gravity.BOTTOM
		}
		
		widgetView.setOnTouchListener { view, event ->
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					// Guardar posición inicial
					initialX = widgetParams.x
					initialY = widgetParams.y
					initialTouchX = event.rawX
					initialTouchY = event.rawY
					
					val currentTime = System.currentTimeMillis()
					if (currentTime - lastTapTime < 300) {
						// Informar que la ventana de ajustes se abrirá
						isSettingsOpen = true
						updateWidgetVisibility()
						
						val intent = Intent(this, WidgetSettingsActivity::class.java)
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						startActivity(intent)
						return@setOnTouchListener true
					}
					lastTapTime = currentTime
					
					if (!isRemoveAreaVisible) {
						windowManager.addView(removeAreaView, removeAreaParams)
						isRemoveAreaVisible = true
					}
					true
				}
				MotionEvent.ACTION_MOVE -> {
					// Actualizar posición
					var newX = initialX + (event.rawX - initialTouchX).toInt()
					var newY = initialY + (event.rawY - initialTouchY).toInt()
					
					// Obtener las dimensiones de la pantalla
					val currentDisplayMetrics = resources.displayMetrics
					val currentScreenWidth = currentDisplayMetrics.widthPixels
					val currentScreenHeight = currentDisplayMetrics.heightPixels
					
					// Permitir que el widget llegue a los bordes de la pantalla
					// No limitamos la posición mínima para permitir que llegue a los bordes
					if (newX + widgetView.width > currentScreenWidth) newX = currentScreenWidth - widgetView.width
					if (newY + widgetView.height > currentScreenHeight) newY = currentScreenHeight - widgetView.height
					
					widgetParams.x = newX
					widgetParams.y = newY
					windowManager.updateViewLayout(widgetView, widgetParams)
					
					// Guardar la posición actual
					currentX = newX
					currentY = newY
					
					// Verificar si está sobre el área de eliminación
					if (isRemoveAreaVisible) {
						val removeArea = removeAreaBinding.removeArea
						val location = IntArray(2)
						removeArea.getLocationOnScreen(location)
						
						// Calcular el centro del área de eliminación
						val removeAreaCenterX = location[0] + removeArea.width / 2
						val removeAreaCenterY = location[1] + removeArea.height / 2
						
						// Calcular el centro del widget
						val widgetCenterX = newX + widgetView.width / 2
						val widgetCenterY = newY + widgetView.height / 2
						
						// Calcular la distancia entre los centros
						val distance = Math.sqrt(
							Math.pow((widgetCenterX - removeAreaCenterX).toDouble(), 2.0) + 
							Math.pow((widgetCenterY - removeAreaCenterY).toDouble(), 2.0)
						)
						
						// Si está cerca del área de eliminación, resaltar el área
						val isOverRemoveArea = distance < removeArea.width
						if (isOverRemoveArea) {
							removeArea.setCardBackgroundColor(ContextCompat.getColor(this, R.color.primary_color))
						} else {
							removeArea.setCardBackgroundColor(ContextCompat.getColor(this, R.color.poor_connection))
						}
					}
					true
				}
				MotionEvent.ACTION_UP -> {
					if (isRemoveAreaVisible) {
						val removeArea = removeAreaBinding.removeArea
						val location = IntArray(2)
						removeArea.getLocationOnScreen(location)
						
						// Calcular el centro del área de eliminación
						val removeAreaCenterX = location[0] + removeArea.width / 2
						val removeAreaCenterY = location[1] + removeArea.height / 2
						
						// Calcular el centro del widget
						val widgetCenterX = widgetParams.x + widgetView.width / 2
						val widgetCenterY = widgetParams.y + widgetView.height / 2
						
						// Calcular la distancia entre los centros
						val distance = Math.sqrt(
							Math.pow((widgetCenterX - removeAreaCenterX).toDouble(), 2.0) + 
							Math.pow((widgetCenterY - removeAreaCenterY).toDouble(), 2.0)
						)
						
						// Si está sobre el área de eliminación, cerrar el servicio
						val isOverRemoveArea = distance < removeArea.width
						if (isOverRemoveArea) {
							// Cerrar el servicio y la aplicación
							stopSelf()
							val intent = Intent(this, MainActivity::class.java)
							intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
							intent.putExtra("EXIT", true)
							startActivity(intent)
						}
						
						// Ocultar área de eliminación
						windowManager.removeView(removeAreaView)
						isRemoveAreaVisible = false
					}
					true
				}
				else -> false
			}
		}
		
		windowManager.addView(widgetView, widgetParams)
		updateWidgetVisibility()
	}
    
	private fun applyWidgetSettings() {
		val prefs = getSharedPreferences("widget_settings", MODE_PRIVATE)
		
		// Guardar la posición actual antes de aplicar los cambios
		var oldX = -1
		var oldY = -1
		if (widgetView.isAttachedToWindow) {
			val params = widgetView.layoutParams as WindowManager.LayoutParams
			oldX = params.x
			oldY = params.y
		}
		
		val displayMetrics = resources.displayMetrics
		val screenWidth = displayMetrics.widthPixels
		
		val isSmallScreen = screenWidth < 720
		
		val size = prefs.getString("size", "medium") ?: "medium"
		val scale = when {
			isSmallScreen -> when(size) {
				"small" -> 0.6f
				"medium" -> 0.7f
				"large" -> 0.8f
				else -> 0.7f
			}
			else -> when(size) {
				"small" -> 0.8f
				"medium" -> 1.0f
				"large" -> 1.2f
				else -> 1.0f
			}
		}
		
		val parent = widgetBinding.cardWidget.parent as? ViewGroup
		if (parent is LinearLayout) {
			widgetBinding.cardWidget.layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
		} else {
			widgetBinding.cardWidget.layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT
			)
		}
		
		widgetBinding.cardWidget.scaleX = scale
		widgetBinding.cardWidget.scaleY = scale
		
		val transparency = prefs.getInt("transparency", 20)
		val alpha = 1 - (transparency / 100f)
		widgetBinding.cardWidget.alpha = alpha
		
		// Configurar la visibilidad según las preferencias
		val displayInfo = prefs.getString("display_info", "both") ?: "both"
		
		// Obtener referencias a los layouts
		val layoutDownload = widgetBinding.root.findViewById<LinearLayout>(R.id.layoutDownload)
		val layoutUpload = widgetBinding.root.findViewById<LinearLayout>(R.id.layoutUpload)
		val separator = widgetBinding.root.findViewById<TextView>(R.id.tvSeparator)
		
		// Asegurarse de que el icono de tipo de red siempre sea visible
		val networkTypeIcon = widgetBinding.root.findViewById<ImageView>(R.id.ivNetworkType)
		networkTypeIcon?.visibility = View.VISIBLE
		
		when (displayInfo) {
			"download_only" -> {
				layoutDownload?.visibility = View.VISIBLE
				layoutUpload?.visibility = View.GONE
				separator?.visibility = View.GONE
			}
			"upload_only" -> {
				layoutDownload?.visibility = View.GONE
				layoutUpload?.visibility = View.VISIBLE
				separator?.visibility = View.GONE
			}
			else -> {
				layoutDownload?.visibility = View.VISIBLE
				layoutUpload?.visibility = View.VISIBLE
				separator?.visibility = View.VISIBLE
			}
		}
		
		// Obtener el tema (aunque no se muestre en la UI, lo necesitamos para el color predeterminado)
		val isDarkTheme = prefs.getString("theme", "dark") == "dark"
		
		// Aplicar color del widget
		val widgetColorString = prefs.getString("widget_color", "default")
		
		val bgColor = if (widgetColorString == "default") {
			// Usar color predeterminado según el tema
			if (isDarkTheme)
                "#CC121212".toColorInt()
			else
                "#CCFFFFFF".toColorInt()
		} else {
			try {
				widgetColorString?.toColorInt()
			} catch (e: Exception) {
				// Si hay error, usar color predeterminado
				if (isDarkTheme)
                    "#CC121212".toColorInt()
				else
                    "#CCFFFFFF".toColorInt()
			}
		}
		
		// Aplicar el color
		if (bgColor != null) {
			widgetBinding.cardWidget.setCardBackgroundColor(bgColor)
		}
		
		
		// Aplicar colores a los textos de velocidad y flechas
		val downloadArrowColor = prefs.getInt("download_arrow_color", 
			ContextCompat.getColor(this, R.color.secondary_color))
		val downloadTextColor = prefs.getInt("download_text_color", 
			ContextCompat.getColor(this, R.color.secondary_color))
		val uploadArrowColor = prefs.getInt("upload_arrow_color", 
			ContextCompat.getColor(this, R.color.primary_color))
		val uploadTextColor = prefs.getInt("upload_text_color", 
			ContextCompat.getColor(this, R.color.primary_color))
		
		// Aplicar colores a las flechas
		widgetBinding.ivDownloadArrow.setColorFilter(downloadArrowColor)
		widgetBinding.ivUploadArrow.setColorFilter(uploadArrowColor)
		
		// Aplicar colores a los textos
		widgetBinding.tvWidgetDownload.setTextColor(downloadTextColor)
		widgetBinding.tvWidgetUpload.setTextColor(uploadTextColor)
	
		// Actualizar el icono de tipo de red
		updateNetworkTypeIcon()
		
		// Si teníamos una posición anterior, restaurarla para mantener el lado izquierdo fijo
		if (oldX >= 0 && widgetView.isAttachedToWindow) {
			// Dar tiempo para que el layout se actualice
			widgetView.post {
				try {
					val params = widgetView.layoutParams as WindowManager.LayoutParams
					params.x = oldX
					params.y = oldY
					windowManager.updateViewLayout(widgetView, params)
					
					// Guardar la posición
					currentX = oldX
					currentY = oldY
				} catch (e: Exception) {
					e.printStackTrace()
				}
			}
		}
	}

	private fun startSpeedUpdates() {
		timer = Timer()
		timer?.schedule(object : TimerTask() {
			@SuppressLint("SetTextI18n")
            override fun run() {
				val networkStats = NetworkUtils.getNetworkStats(this@FloatingWidgetService)
				
				// Actualizar UI en el hilo principal
				widgetBinding.tvWidgetDownload.post {
					// Guardar la posición actual antes de actualizar el texto
					val currentParams = widgetView.layoutParams as? WindowManager.LayoutParams
					val oldX = currentParams?.x ?: 0
					val oldWidth = widgetView.width
					
					// Formatear las velocidades sin decimales
					val downloadSpeedFormatted = formatSpeedWithoutDecimals(networkStats.downloadSpeed)
					val uploadSpeedFormatted = formatSpeedWithoutDecimals(networkStats.uploadSpeed)
					
					// Actualizar los textos
					widgetBinding.tvWidgetDownload.text = "$downloadSpeedFormatted ${networkStats.downloadUnit}"
					widgetBinding.tvWidgetUpload.text = "$uploadSpeedFormatted ${networkStats.uploadUnit}"
					
					// Actualizar el icono de tipo de red
					updateNetworkTypeIcon()
					
					// Dar tiempo para que el layout se actualice
					widgetView.post {
						// Si el widget cambió de tamaño, mantener la posición izquierda fija
						if (widgetView.width != oldWidth && widgetView.isAttachedToWindow) {
							try {
								val newParams = widgetView.layoutParams as WindowManager.LayoutParams
								newParams.x = oldX
								windowManager.updateViewLayout(widgetView, newParams)
							} catch (e: Exception) {
								e.printStackTrace()
							}
						}
						
						updateWidgetVisibility()
					}
				}
			}
		}, 0, 1000)
	}

	private fun formatSpeedWithoutDecimals(speed: String): String {
		return try {
			// Intentar convertir a número y formatear sin decimales
			val speedValue = speed.toDouble()
			speedValue.toInt().toString()
		} catch (e: Exception) {
			// Si hay error, devolver el valor original
			speed
		}
	}

	private fun updateNetworkTypeIcon() {
		val isWifiConnected = NetworkUtils.isWifiConnected(this)
		val isMobileConnected = NetworkUtils.isMobileDataConnected(this)
		
		val networkIcon = when {
			isWifiConnected -> R.drawable.ic_wifi
			isMobileConnected -> R.drawable.ic_mobile_data
			else -> R.drawable.ic_no_network
		}
		
		// Obtener el color personalizado del icono de red
		val prefs = getSharedPreferences("widget_settings", MODE_PRIVATE)
		val networkIconColor = prefs.getInt("network_icon_color", ContextCompat.getColor(this, R.color.good_connection))
		
		widgetBinding.ivNetworkType.setImageResource(networkIcon)
		widgetBinding.ivNetworkType.setColorFilter(networkIconColor)
	}
		
	private fun setupConnectivityReceiver() {
		connectivityReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				if (intent.action == ConnectivityManager.CONNECTIVITY_ACTION) {
					// Actualizar el icono de tipo de red
					updateNetworkTypeIcon()
					
					// Actualizar visibilidad del widget
					updateWidgetVisibility()
					
					// Reiniciar el contador de velocidad para evitar picos falsos
					NetworkUtils.resetCounters()
				}
			}
		}
		
		val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
		registerReceiver(connectivityReceiver, filter)
	}
    
	private fun updateWidgetVisibility() {
		val isConnected = NetworkUtils.isWifiConnected(this) || NetworkUtils.isMobileDataConnected(this)

		// Mostrar el widget si hay conexión O si la app principal está en primer plano O si la ventana de ajustes está abierta
		if ((isConnected || isMainAppForeground || isSettingsOpen) && !isWidgetVisible && widgetView.isAttachedToWindow) {
			widgetView.visibility = View.VISIBLE
			isWidgetVisible = true
		}
		// Ocultar el widget solo si no hay conexión Y la app principal NO está en primer plano Y la ventana de ajustes NO está abierta
		else if (!isConnected && !isMainAppForeground && !isSettingsOpen && isWidgetVisible && widgetView.isAttachedToWindow) {
			widgetView.visibility = View.GONE
			isWidgetVisible = false
		}
		// Intentar configurar el widget si debería ser visible pero no está adjunto
		else if ((isConnected || isMainAppForeground || isSettingsOpen) && !widgetView.isAttachedToWindow) {
			try {
				setupWidget()
				isWidgetVisible = true
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	private fun checkInitialAppState() {
		// Verificar si la app principal está en primer plano al iniciar el servicio
		try {
			val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
			val runningTasks = activityManager.getRunningTasks(1)
			if (runningTasks.isNotEmpty()) {
				val topActivity = runningTasks[0].topActivity
				if (topActivity != null && topActivity.packageName == packageName) {
					// La app principal está en primer plano
					isMainAppForeground = true
				}
			}
		} catch (e: Exception) {
			// En caso de error, asumir que no está en primer plano
			isMainAppForeground = false
		}
	}

	private fun sendServiceStateBroadcast(isRunning: Boolean) {
		val intent = Intent("cu.maxwell.firenetstats.SERVICE_STATE_CHANGED")
		intent.putExtra("RUNNING", isRunning)
		sendBroadcast(intent)
	}


}
