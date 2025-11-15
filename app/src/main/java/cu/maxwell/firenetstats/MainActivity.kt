package cu.maxwell.firenetstats

import android.app.ActivityManager
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import cu.maxwell.firenetstats.databinding.ActivityMainBinding
import cu.maxwell.firenetstats.utils.NetworkUtils
import cu.maxwell.firenetstats.utils.UpdateChecker
import cu.maxwell.firenetstats.utils.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isWidgetActive = false
    private val downloadSpeedEntries = ArrayList<Entry>()
    private val uploadSpeedEntries = ArrayList<Entry>()
    private var entryCount = 0
    private var timer: Timer? = null
    private var syncTimer: Timer? = null // Timer para sincronización periódica del widget
    private val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    private lateinit var updateChecker: UpdateChecker

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
		private const val PHONE_STATE_PERMISSION_REQUEST_CODE = 1002
        private const val ALL_PERMISSIONS_REQUEST_CODE = 1003
    }

	@SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		// Verificar si se debe cerrar la app (desde el widget)
		if (intent.getBooleanExtra("EXIT", false)) {
			finish()
			return
		}

		// Inicializar update checker
		updateChecker = UpdateChecker(this)

		// Verificar permisos
		checkAndRequestAllPermissions()

		setupChart()
		updateNetworkInfo()
		setupWidgetButton()
		setupTopBarButtons()

		// Iniciar la actualización periódica de datos
		startDataUpdates()

		// Iniciar widget automáticamente si tiene permisos
		checkAndStartWidgetIfPermitted()

		// Verificar actualizaciones disponibles
		checkForUpdates()

		val filter = IntentFilter("cu.maxwell.firenetstats.SERVICE_STATE_CHANGED")
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(
				serviceStateReceiver,
				filter,
				RECEIVER_NOT_EXPORTED // Use the constant from Context
			)
		} else {
			// For versions before Android 13
			registerReceiver(serviceStateReceiver, filter)
		}
	}

	override fun onResume() {
		super.onResume()

		// Verificar el estado real del servicio y actualizar la UI
		val realServiceState = getServiceRealState()
		if (isWidgetActive != realServiceState) {
			isWidgetActive = realServiceState
			updateWidgetButtonState()

			// Enviar broadcast para sincronizar estado
			sendServiceStateBroadcast(realServiceState)
		}

		// Informar al servicio que la app principal está en primer plano
		if (isWidgetActive) {
			val intent = Intent(this, FloatingWidgetService::class.java)
			intent.action = "MAIN_APP_FOREGROUND"
			startService(intent)
		}
		
		// Programar una sincronización periódica cada 5 segundos
		schedulePeriodicSync()
	}
	
	override fun onRestart() {
		super.onRestart()
		// Cuando la app vuelve a primer plano, verificar sincronización inmediatamente
		val realState = getServiceRealState()
		if (isWidgetActive != realState) {
			isWidgetActive = realState
			updateWidgetButtonState()
		}
	}
	
	/**
		* Programa una sincronización periódica del estado del widget para evitar desincronización
		*/
	private fun schedulePeriodicSync() {
		// Cancelar timer anterior si existe
		syncTimer?.cancel()
		syncTimer = null
		
		// Crear nuevo timer para sincronización
		syncTimer = Timer()
		syncTimer?.scheduleAtFixedRate(object : TimerTask() {
			override fun run() {
				// Verificar sincronización en hilo de fondo
				val realState = getServiceRealState()
				
				// Si hay discrepancia, actualizar UI en hilo principal
				if (isWidgetActive != realState) {
					runOnUiThread {
						isWidgetActive = realState
						updateWidgetButtonState()
					}
				}
			}
		}, 5000, 5000) // Cada 5 segundos
	}

	override fun onPause() {
		super.onPause()
		
		// Informar al servicio que la app principal está en segundo plano
		if (isWidgetActive) {
			val intent = Intent(this, FloatingWidgetService::class.java)
			intent.action = "MAIN_APP_BACKGROUND"
			startService(intent)
		}
	}
	
	private fun checkAndRequestAllPermissions() {
		val permissionsToRequest = mutableListOf<String>()
		
		// Verificar permiso de ubicación
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != 
				PackageManager.PERMISSION_GRANTED) {
				permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
			}
			
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != 
				PackageManager.PERMISSION_GRANTED) {
				permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
			}
			
			// Verificar permiso de estado del teléfono
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != 
				PackageManager.PERMISSION_GRANTED) {
				permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
			}
		}
		
		// Si hay permisos que solicitar, hacerlo
		if (permissionsToRequest.isNotEmpty()) {
			ActivityCompat.requestPermissions(
				this,
				permissionsToRequest.toTypedArray(),
				ALL_PERMISSIONS_REQUEST_CODE
			)
		}
	}

	// Método para verificar el permiso de ubicación (mantener para compatibilidad)
	private fun checkLocationPermissions() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val fineLocationPermission = ContextCompat.checkSelfPermission(
				this, Manifest.permission.ACCESS_FINE_LOCATION)
			val coarseLocationPermission = ContextCompat.checkSelfPermission(
				this, Manifest.permission.ACCESS_COARSE_LOCATION)
				
			if (fineLocationPermission != PackageManager.PERMISSION_GRANTED || 
				coarseLocationPermission != PackageManager.PERMISSION_GRANTED) {
				requestLocationPermissions()
			}
		}
	}

	// Método para verificar el permiso de teléfono (mantener para compatibilidad)
	private fun checkPhoneStatePermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val phoneStatePermission = ContextCompat.checkSelfPermission(
				this, Manifest.permission.READ_PHONE_STATE)
				
			if (phoneStatePermission != PackageManager.PERMISSION_GRANTED) {
				requestPhoneStatePermission()
			}
		}
	}

	// Método para solicitar el permiso de ubicación
	private fun requestLocationPermissions() {
		ActivityCompat.requestPermissions(
			this,
			arrayOf(
				Manifest.permission.ACCESS_FINE_LOCATION,
				Manifest.permission.ACCESS_COARSE_LOCATION
			),
			LOCATION_PERMISSION_REQUEST_CODE
		)
	}

	// Método para solicitar el permiso de teléfono
	private fun requestPhoneStatePermission() {
		ActivityCompat.requestPermissions(
			this,
			arrayOf(Manifest.permission.READ_PHONE_STATE),
			PHONE_STATE_PERMISSION_REQUEST_CODE
		)
	}

	// Modificar el método onRequestPermissionsResult para manejar todos los permisos
	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		
		when (requestCode) {
			LOCATION_PERMISSION_REQUEST_CODE -> {
				if (grantResults.isNotEmpty() && 
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					// Permiso concedido, actualizar información
					updateNetworkInfo()
				} else {
					// Permiso denegado, mostrar mensaje
					Toast.makeText(
						this,
						"Se requieren permisos de ubicación para mostrar el nombre de la red WiFi",
						Toast.LENGTH_LONG
					).show()
				}
			}
			PHONE_STATE_PERMISSION_REQUEST_CODE -> {
				if (grantResults.isNotEmpty() && 
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					// Permiso concedido, actualizar información
					updateNetworkInfo()
				} else {
					// Permiso denegado, mostrar mensaje
					Toast.makeText(
						this,
						"Se requiere permiso para acceder a información detallada de la red móvil",
						Toast.LENGTH_LONG
					).show()
				}
			}
			ALL_PERMISSIONS_REQUEST_CODE -> {
				// Procesar cada permiso individualmente
				var allGranted = true
				
				for (i in permissions.indices) {
					if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
						allGranted = false
						
						// Mostrar mensaje específico según el permiso denegado
						when (permissions[i]) {
							Manifest.permission.ACCESS_FINE_LOCATION, 
							Manifest.permission.ACCESS_COARSE_LOCATION -> {
								Toast.makeText(
									this,
									"Se requieren permisos de ubicación para mostrar el nombre de la red WiFi",
									Toast.LENGTH_LONG
								).show()
							}
							Manifest.permission.READ_PHONE_STATE -> {
								Toast.makeText(
									this,
									"Se requiere permiso para acceder a información detallada de la red móvil",
									Toast.LENGTH_LONG
								).show()
							}
						}
					}
				}
				
				// Si todos los permisos fueron concedidos, actualizar la información
				if (allGranted) {
					updateNetworkInfo()
				}
			}
		}
	}

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        syncTimer?.cancel() // Cancelar timer de sincronización
  
  try {
   unregisterReceiver(serviceStateReceiver)
  } catch (e: Exception) {
   // Ignorar si no estaba registrado
  }
    }
	
	private fun setupTopBarButtons() {
		// Configurar botón de actualización
		binding.btnUpdate.setOnClickListener {
			showUpdateDialog()
		}

		// Configurar botón de ajustes del widget
		binding.btnWidgetConfig.setOnClickListener {
			// Abrir actividad de configuración del widget
			val intent = Intent(this, WidgetSettingsActivity::class.java)
			startActivity(intent)
		}
		
		// Configurar botón de información
		binding.btnAboutInfo.setOnClickListener {
			showAboutDialog()
		}
	}

	private fun showAboutDialog() {
		val dialog = Dialog(this)
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
		dialog.setContentView(R.layout.dialog_about)
		
		// Obtener la versión de la app
		val packageInfo = packageManager.getPackageInfo(packageName, 0)
		val versionName = packageInfo.versionName
		
		// Configurar la versión
		val tvVersion = dialog.findViewById<TextView>(R.id.tvVersion)
		tvVersion.text = "Versión $versionName"
		
		// Configurar botones
		dialog.findViewById<ImageView>(R.id.btnGithub).setOnClickListener {
			openUrl("https://github.com/MaxCode93")
		}
		
		dialog.findViewById<ImageView>(R.id.btnFacebook).setOnClickListener {
			openUrl("https://facebook.com/carlos.maxwell93")
		}
		
		dialog.findViewById<ImageView>(R.id.btnRate).setOnClickListener {
			openPlayStore()
		}
		
		dialog.findViewById<ImageView>(R.id.btnShare).setOnClickListener {
			shareApp()
		}
		
		dialog.findViewById<Button>(R.id.btnClose).setOnClickListener {
			dialog.dismiss()
		}
		
		// Configurar el diálogo para que ocupe el ancho máximo
		val window = dialog.window
		window?.setLayout(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.WRAP_CONTENT
		)
		window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		
		dialog.show()
	}

	// Métodos auxiliares para las acciones
	private fun openUrl(url: String) {
		try {
			val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
			startActivity(intent)
		} catch (e: Exception) {
			Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
		}
	}

	private fun openPlayStore() {
		try {
			val uri = Uri.parse("market://details?id=$packageName")
			val intent = Intent(Intent.ACTION_VIEW, uri)
			intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or
					Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
					Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
			startActivity(intent)
		} catch (e: ActivityNotFoundException) {
			// Si Play Store no está instalado, abre en el navegador
			val uri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
			val intent = Intent(Intent.ACTION_VIEW, uri)
			startActivity(intent)
		}
	}

	private fun shareApp() {
		val shareIntent = Intent(Intent.ACTION_SEND)
		shareIntent.type = "text/plain"
		shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
		val shareMessage = "¡Prueba FireNetStats, una excelente app para monitorear tu red! " +
				"Descárgala desde: https://play.google.com/store/apps/details?id=$packageName"
		shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage)
		startActivity(Intent.createChooser(shareIntent, "Compartir vía"))
	}

    private fun setupChart() {
        val chart = binding.speedChart
        
        // Configurar apariencia del gráfico
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.setBackgroundColor(ContextCompat.getColor(this, R.color.card_background))
        
        // Configurar ejes
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        xAxis.setDrawGridLines(false)
        xAxis.valueFormatter = object : ValueFormatter() {
            private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            
            override fun getFormattedValue(value: Float): String {
                return dateFormat.format(Date(System.currentTimeMillis() - (30000 * (30 - value.toInt()))))
            }
        }
        
        val leftAxis = chart.axisLeft
        leftAxis.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMinimum = 0f
        
        val rightAxis = chart.axisRight
        rightAxis.isEnabled = false
        
        // Inicializar conjuntos de datos vacíos
        val downloadDataSet = LineDataSet(downloadSpeedEntries, "Download")
        downloadDataSet.color = ContextCompat.getColor(this, R.color.secondary_color)
        downloadDataSet.setCircleColor(ContextCompat.getColor(this, R.color.secondary_color))
        downloadDataSet.lineWidth = 2f
        downloadDataSet.circleRadius = 3f
        downloadDataSet.setDrawCircleHole(false)
        downloadDataSet.valueTextSize = 9f
        downloadDataSet.setDrawFilled(true)
        downloadDataSet.fillColor = ContextCompat.getColor(this, R.color.secondary_color)
        downloadDataSet.fillAlpha = 50
        downloadDataSet.setDrawValues(false)
        downloadDataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        
        val uploadDataSet = LineDataSet(uploadSpeedEntries, "Upload")
        uploadDataSet.color = ContextCompat.getColor(this, R.color.primary_color)
        uploadDataSet.setCircleColor(ContextCompat.getColor(this, R.color.primary_color))
        uploadDataSet.lineWidth = 2f
        uploadDataSet.circleRadius = 3f
        uploadDataSet.setDrawCircleHole(false)
        uploadDataSet.valueTextSize = 9f
        uploadDataSet.setDrawFilled(true)
        uploadDataSet.fillColor = ContextCompat.getColor(this, R.color.primary_color)
        uploadDataSet.fillAlpha = 50
        uploadDataSet.setDrawValues(false)
        uploadDataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        
        val lineData = LineData(downloadDataSet, uploadDataSet)
        chart.data = lineData
        chart.legend.textColor = ContextCompat.getColor(this, R.color.text_primary)
        chart.invalidate()
    }

	private fun updateNetworkInfo() {
		val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
		val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
		
		if (networkCapabilities != null) {
			// Tipo de red
			when {
				networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
					binding.tvNetworkType.text = "WiFi"
					binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
						ContextCompat.getDrawable(this, R.drawable.ic_wifi), null, null, null)
					updateWifiInfo()
				}
				networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
					binding.tvNetworkType.text = "Datos Móviles"
					binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
						ContextCompat.getDrawable(this, R.drawable.ic_mobile_data), null, null, null)
					
					// Intentar obtener información del operador móvil
					val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
					val operatorName = telephonyManager?.networkOperatorName
					
					if (!operatorName.isNullOrEmpty()) {
						binding.tvWifiName.text = operatorName
					} else {
						binding.tvWifiName.text = "Desconocido"
					}
					
					// Verificar permisos para información detallada
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
						checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
						binding.tvSignalStrength.text = "Sin permiso"
						binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.poor_connection))
						binding.tvSignalStrength.setOnClickListener {
							requestPhoneStatePermission()
						}
					} else {
						// Obtener la intensidad de la señal móvil
						updateMobileSignalStrength()
					}
				}
				else -> {
					binding.tvNetworkType.text = "Otra"
					binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
						ContextCompat.getDrawable(this, R.drawable.ic_network_other), null, null, null)
					binding.tvWifiName.text = "N/A"
					binding.tvSignalStrength.text = "N/A"
					binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
				}
			}
		} else {
			binding.tvNetworkType.text = getString(R.string.not_connected)
			binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
				ContextCompat.getDrawable(this, R.drawable.ic_no_network), null, null, null)
			binding.tvWifiName.text = "N/A"
			binding.tvSignalStrength.text = "N/A"
			binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
		}
		
		// Uso de datos (calculado por NetworkUtils)
		val dataUsage = NetworkUtils.getMonthlyDataUsage(this)
		binding.tvDataUsage.text = dataUsage
	}

	@SuppressLint("MissingPermission")
	private fun updateMobileSignalStrength() {
		try {
			val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
			
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				// Para Android 10 (API 29) y superior
				val signalStrength = telephonyManager.signalStrength
				if (signalStrength != null) {
					// Obtener el nivel de señal para la red celular (0-4)
					val level = signalStrength.level
					
					when (level) {
						0 -> { // SIGNAL_STRENGTH_NONE_OR_UNKNOWN
							binding.tvSignalStrength.text = "Desconocida"
							binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
							binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
								ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_unknown), null, null, null)
						}
						1 -> { // SIGNAL_STRENGTH_POOR
							binding.tvSignalStrength.text = "Débil"
							binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.poor_connection))
							binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
								ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_weak), null, null, null)
						}
						2 -> { // SIGNAL_STRENGTH_MODERATE
							binding.tvSignalStrength.text = "Moderada"
							binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.medium_connection))
							binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
								ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_medium), null, null, null)
						}
						3, 4 -> { // SIGNAL_STRENGTH_GOOD, SIGNAL_STRENGTH_GREAT
							binding.tvSignalStrength.text = "Excelente"
							binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.good_connection))
							binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
								ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_strong), null, null, null)
						}
						else -> {
							binding.tvSignalStrength.text = "N/A"
							binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
							binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
								ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_unknown), null, null, null)
						}
					}
				} else {
					binding.tvSignalStrength.text = "N/A"
					binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
					binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
						ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_unknown), null, null, null)
				}
			} else {
				// Para versiones anteriores a Android 10
				// Usamos PhoneStateListener para obtener la intensidad de la señal
				val phoneStateListener = object : PhoneStateListener() {
					override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
						super.onSignalStrengthsChanged(signalStrength)
						
						if (signalStrength != null) {
							// Obtener el nivel de señal GSM (0-31) o nivel general
							val gsmSignalStrength = try {
								// Intentar obtener el valor GSM mediante reflexión
								val method = SignalStrength::class.java.getDeclaredMethod("getGsmSignalStrength")
								method.isAccessible = true
								method.invoke(signalStrength) as Int
							} catch (e: Exception) {
								// Si falla, intentar obtener el nivel general
								try {
									val method = SignalStrength::class.java.getDeclaredMethod("getLevel")
									method.isAccessible = true
									method.invoke(signalStrength) as Int
								} catch (e2: Exception) {
									-1
								}
							}
							
							runOnUiThread {
								when {
									gsmSignalStrength <= 0 || gsmSignalStrength >= 99 -> {
										binding.tvSignalStrength.text = "Desconocida"
										binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
										binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
											ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mobile_signal_unknown), null, null, null)
									}
									gsmSignalStrength < 10 -> {
										binding.tvSignalStrength.text = "Débil"
										binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.poor_connection))
										binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
											ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mobile_signal_weak), null, null, null)
									}
									gsmSignalStrength < 20 -> {
										binding.tvSignalStrength.text = "Moderada"
										binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.medium_connection))
										binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
											ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mobile_signal_medium), null, null, null)
									}
									else -> {
										binding.tvSignalStrength.text = "Excelente"
										binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.good_connection))
										binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
											ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mobile_signal_strong), null, null, null)
									}
								}
							}
						}
						
						// Dejar de escuchar después de obtener la información
						telephonyManager.listen(this, LISTEN_NONE)
					}
				}
				
				// Comenzar a escuchar cambios en la intensidad de la señal
				telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
				
				// Establecer un valor predeterminado mientras se obtiene la información
				binding.tvSignalStrength.text = "Obteniendo..."
				binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
			}
		} catch (e: Exception) {
			// En caso de error, mostrar N/A
			binding.tvSignalStrength.text = "N/A"
			binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
			binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
				ContextCompat.getDrawable(this, R.drawable.ic_mobile_signal_unknown), null, null, null)
		}
	}

	private fun updateWifiInfo() {
		val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
		val wifiInfo = wifiManager.connectionInfo
		
		// Verificar permisos de ubicación
		val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
				PackageManager.PERMISSION_GRANTED ||
			ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == 
				PackageManager.PERMISSION_GRANTED
		} else {
			true // En versiones anteriores a M, el permiso se otorga en la instalación
		}
		
		var ssid = wifiInfo.ssid
		
		// Eliminar comillas si están presentes
		if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
			ssid = ssid.substring(1, ssid.length - 1)
		}
		
		// Manejar casos especiales
		when {
			!hasLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
				binding.tvWifiName.text = "Requiere permiso de ubicación"
				binding.tvWifiName.setOnClickListener {
					requestLocationPermissions()
				}
			}
			ssid == "<unknown ssid>" -> {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission) {
					// En Android 10+ con permisos, pero aún no se puede obtener
					binding.tvWifiName.text = "Nombre no disponible"
				} else {
					binding.tvWifiName.text = "Desconocido"
				}
			}
			ssid.isEmpty() -> binding.tvWifiName.text = "No conectado a WiFi"
			else -> binding.tvWifiName.text = ssid
		}
		
		// El resto del método se mantiene igual...
		val rssi = wifiInfo.rssi
		val signalLevel = WifiManager.calculateSignalLevel(rssi, 5)
		
		when (signalLevel) {
			0, 1 -> {
				binding.tvSignalStrength.text = "Débil"
				binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.poor_connection))
				binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
					ContextCompat.getDrawable(this, R.drawable.ic_wifi_signal_weak), null, null, null)
			}
			2, 3 -> {
				binding.tvSignalStrength.text = "Buena"
				binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.medium_connection))
				binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
					ContextCompat.getDrawable(this, R.drawable.ic_wifi_signal_medium), null, null, null)
			}
			4 -> {
				binding.tvSignalStrength.text = "Excelente"
				binding.tvSignalStrength.setTextColor(ContextCompat.getColor(this, R.color.good_connection))
				binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
					ContextCompat.getDrawable(this, R.drawable.ic_wifi_signal_strong), null, null, null)
			}
		}
		
		// Actualizar icono de nombre de WiFi
		binding.tvWifiNameLabel.setCompoundDrawablesWithIntrinsicBounds(
			ContextCompat.getDrawable(this, R.drawable.ic_wifi_name), null, null, null)
		
		// Actualizar icono de uso de datos
		binding.tvDataUsageLabel.setCompoundDrawablesWithIntrinsicBounds(
			ContextCompat.getDrawable(this, R.drawable.ic_data_usage), null, null, null)
	}

    private fun setupWidgetButton() {
        binding.btnToggleWidget.setOnClickListener {
            if (!isWidgetActive) {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else {
                    startFloatingWidget()
                }
            } else {
                stopFloatingWidget()
            }
        }
        
        // Actualizar estado inicial del botón
        updateWidgetButtonState()
    }
    
	private fun updateWidgetButtonState(firstTime: Boolean = false) {
		// Verificar el estado real del servicio usando múltiples métodos
		val serviceRunning = getServiceRealState()
		isWidgetActive = serviceRunning
		
		// Actualizar texto e icono del botón según el estado
		if (isWidgetActive || firstTime) {
			binding.btnToggleWidget.text = getString(R.string.disable_floating_widget)
			binding.btnToggleWidget.setIconResource(R.drawable.ic_disable_floating_widget)
		} else {
			binding.btnToggleWidget.text = getString(R.string.enable_floating_widget)
			binding.btnToggleWidget.setIconResource(R.drawable.ic_enable_floating_widget)
		}
	}
	
	/**
	 * Verifica el estado real del servicio usando múltiples métodos para mayor precisión
	 */
	private fun getServiceRealState(): Boolean {
		try {
			// Método 1: Verificar usando ActivityManager (más confiable)
			val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
			val isServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
				.any { it.service.className == FloatingWidgetService::class.java.name }
			
			// Método 2: Verificar usando isRunning estático como confirmación
			val staticState = FloatingWidgetService.isRunning
			
			// Si ambos métodos coinciden, retornar ese estado
			if (isServiceRunning == staticState) {
				return isServiceRunning
			}
			
			// Si hay discrepancia, confiar en ActivityManager (más confiable)
			return isServiceRunning
		} catch (e: Exception) {
			// En caso de error, usar el estado estático como fallback
			return FloatingWidgetService.isRunning
		}
	}

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
			// Verificar si el permiso fue concedido
			if (Settings.canDrawOverlays(this)) {
				// Iniciar el widget flotante
				startFloatingWidget()
				
				// Actualizar el estado del botón
				updateWidgetButtonState(true)
				
				// Mostrar confirmación
				Toast.makeText(this, "Permiso concedido. Widget activado.", Toast.LENGTH_SHORT).show()
			} else {
				// Asegurarse de que el botón refleje el estado correcto
				updateWidgetButtonState()
				
				// Mostrar mensaje solo si el usuario realmente denegó el permiso
				if (!isWidgetActive) {
					Toast.makeText(this, "Permiso necesario para mostrar el widget", Toast.LENGTH_SHORT).show()
				}
			}
		}
	}

	private fun startFloatingWidget() {
		val intent = Intent(this, FloatingWidgetService::class.java)
		startService(intent)

		// Esperar un momento para que el servicio se inicie y luego verificar
		android.os.Handler(Looper.getMainLooper()).postDelayed({
			// Verificar el estado real después del inicio
			val realState = getServiceRealState()
			if (realState) {
				// Servicio iniciado correctamente
				isWidgetActive = true
				updateWidgetButtonState()
				
				// Enviar broadcast para sincronizar estado
				sendServiceStateBroadcast(true)
				
				// Guardar preferencia
				val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
				prefs.edit().putBoolean("auto_start_widget", true).apply()
				
				Toast.makeText(this, "Widget activado correctamente", Toast.LENGTH_SHORT).show()
			} else {
				// Error al iniciar el servicio
				isWidgetActive = false
				updateWidgetButtonState()
				Toast.makeText(this, "Error al activar el widget", Toast.LENGTH_SHORT).show()
			}
		}, 300) // Esperar 300ms para que el servicio se inicie
	}

	private fun stopFloatingWidget() {
		val intent = Intent(this, FloatingWidgetService::class.java)
		
		// Verificar que el servicio está realmente ejecutándose antes de intentar pararlo
		if (!getServiceRealState()) {
			// El servicio ya está detenido, sincronizar estado local
			isWidgetActive = false
			updateWidgetButtonState()
			
			// Guardar preferencia
			val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
			prefs.edit().putBoolean("auto_start_widget", false).apply()
			return
		}
		
		stopService(intent)

		// Esperar un momento para que el servicio se detenga y luego verificar
		android.os.Handler(Looper.getMainLooper()).postDelayed({
			// Verificar el estado real después de la parada
			val realState = getServiceRealState()
			if (!realState) {
				// Servicio detenido correctamente
				isWidgetActive = false
				updateWidgetButtonState()
				
				// Enviar broadcast para sincronizar estado
				sendServiceStateBroadcast(false)
				
				// Guardar preferencia
				val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
				prefs.edit().putBoolean("auto_start_widget", false).apply()
				
				Toast.makeText(this, "Widget desactivado correctamente", Toast.LENGTH_SHORT).show()
			} else {
				// Error al detener el servicio
				isWidgetActive = true
				updateWidgetButtonState()
				Toast.makeText(this, "Error al desactivar el widget", Toast.LENGTH_SHORT).show()
			}
		}, 200) // Esperar 200ms para que el servicio se detenga
	}

	private fun sendServiceStateBroadcast(isRunning: Boolean) {
		val intent = Intent("cu.maxwell.firenetstats.SERVICE_STATE_CHANGED")
		intent.putExtra("RUNNING", isRunning)
		sendBroadcast(intent)
	}
    
    private fun checkAndStartWidgetIfPermitted() {
        // Verificar si el servicio ya está en ejecución usando método robusto
        val serviceAlreadyRunning = getServiceRealState()
        
        if (serviceAlreadyRunning) {
            isWidgetActive = true
            binding.btnToggleWidget.text = getString(R.string.disable_floating_widget)
            binding.btnToggleWidget.setIconResource(R.drawable.ic_disable_floating_widget)
            return
        }
        
        // Verificar si tiene permisos y preferencias guardadas
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val autoStartWidget = prefs.getBoolean("auto_start_widget", true) // Por defecto activado
        
        if (autoStartWidget && Settings.canDrawOverlays(this)) {
            startFloatingWidget()
        }
    }

	private fun startDataUpdates() {
		timer = Timer()
		timer?.scheduleAtFixedRate(object : TimerTask() {
			override fun run() {
				val networkStats = NetworkUtils.getNetworkStats(this@MainActivity)
				
				runOnUiThread {
					// Usar las unidades específicas para cada velocidad
					binding.tvDownloadSpeed.text = "${networkStats.downloadSpeed} ${networkStats.downloadUnit}"
					binding.tvUploadSpeed.text = "${networkStats.uploadSpeed} ${networkStats.uploadUnit}"
					
					// Actualizar gráfico
					updateChart(networkStats.downloadSpeedRaw, networkStats.uploadSpeedRaw)
					
					// Actualizar información de red
					updateNetworkInfo()
				}
			}
		}, 0, 1000)
	}

    private fun updateChart(downloadSpeed: Float, uploadSpeed: Float) {
        if (downloadSpeedEntries.size >= 30) {
            downloadSpeedEntries.removeAt(0)
            uploadSpeedEntries.removeAt(0)
            
            // Actualizar índices
            for (i in 0 until downloadSpeedEntries.size) {
                downloadSpeedEntries[i] = Entry(i.toFloat(), downloadSpeedEntries[i].y)
                uploadSpeedEntries[i] = Entry(i.toFloat(), uploadSpeedEntries[i].y)
            }
        }
        
        downloadSpeedEntries.add(Entry(downloadSpeedEntries.size.toFloat(), downloadSpeed))
        uploadSpeedEntries.add(Entry(uploadSpeedEntries.size.toFloat(), uploadSpeed))
        
        val chart = binding.speedChart
        val data = chart.data
        
        if (data != null) {
            val downloadDataSet = data.getDataSetByIndex(0) as LineDataSet
            downloadDataSet.values = downloadSpeedEntries
            
            val uploadDataSet = data.getDataSetByIndex(1) as LineDataSet
            uploadDataSet.values = uploadSpeedEntries
            
            data.notifyDataChanged()
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    }
	
	private val serviceStateReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			val running = intent.getBooleanExtra("RUNNING", false)
			
			// Verificar el estado real antes de actualizar para evitar sincronizaciones erróneas
			val realState = getServiceRealState()
			
			// Solo actualizar si el broadcast confirma el estado real
			if (realState == running) {
				isWidgetActive = running
				updateWidgetButtonState()
			} else {
				// Si hay discrepancia, sincronizar con el estado real
				isWidgetActive = realState
				updateWidgetButtonState()
			}
		}
	}

	// ======================== UPDATE SYSTEM ========================
	
	private fun checkForUpdates() {
		updateChecker.checkForUpdates(object : UpdateChecker.UpdateCheckListener {
			override fun onUpdateAvailable(updateState: UpdateState) {
				runOnUiThread {
					showUpdateIcon(true)
				}
			}

			override fun onNoUpdate() {
				runOnUiThread {
					showUpdateIcon(false)
				}
			}

			override fun onCheckError(error: String) {
				// Silenciar errores al usuario, pero ocultar icono si falla
				runOnUiThread {
					showUpdateIcon(false)
				}
			}
		}, forceCheck = true)
	}

	private fun showUpdateIcon(show: Boolean) {
		// El icono se mostrará/ocultará según si hay actualización disponible
		if (show) {
			binding.btnUpdateContainer.visibility = android.view.View.VISIBLE
			// Iniciar animación de pulso en el badge
			val pulseAnimation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
			binding.updateBadge.startAnimation(pulseAnimation)
		} else {
			binding.btnUpdateContainer.visibility = android.view.View.GONE
			binding.updateBadge.clearAnimation()
		}
	}

	private fun showUpdateDialog() {
		val updateState = updateChecker.getUpdateInfo()

		if (!updateState.available) {
			Toast.makeText(this, getString(R.string.update_no_new), Toast.LENGTH_SHORT).show()
			return
		}

		val dialog = AlertDialog.Builder(this)
			.setTitle(null)  // Sin título, lo haremos custom
			.setMessage(formatChangelogMessage(updateState.changelog))
			.setNegativeButton(getString(R.string.update_cancel)) { dialogInterface, _ ->
				dialogInterface.dismiss()
			}
			.setPositiveButton(getString(R.string.update_download)) { dialogInterface, _ ->
				dialogInterface.dismiss()
				openApkLisDownload()
			}
			.create()

		// Customizar la apariencia del diálogo con Material 3
		dialog.setOnShowListener { dialogInterface ->
			val alertDialog = dialogInterface as AlertDialog
			
			// Customizar el título con emoji y estilo
			val titleView = alertDialog.findViewById<TextView>(android.R.id.title)
			if (titleView != null) {
				titleView.text = "✨ ${getString(R.string.update_available_title, updateState.latestVersion)}"
				titleView.textSize = 18f
				titleView.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
				titleView.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
			}
			
			// Customizar el mensaje con espaciado mejorado
			val messageView = alertDialog.findViewById<TextView>(android.R.id.message)
			if (messageView != null) {
				messageView.textSize = 14f
				messageView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
				messageView.setLineSpacing(6f, 1.4f)
			}
			
			// Customizar botones con colores diferenciados
			val positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
			val negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
			
			if (positiveButton != null) {
				positiveButton.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
				positiveButton.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
			}
			
			if (negativeButton != null) {
				negativeButton.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
			}
		}

		dialog.show()
	}

	private fun formatChangelogMessage(changelog: String): String {
		val lines = changelog.split("\n")
		val formattedLines = mutableListOf<String>()
		
		formattedLines.add("📦 ${getString(R.string.update_changelog)}:")
		formattedLines.add("")

		// Parsear el changelog y formatearlo con viñetas limpias
		for (line in lines) {
			var trimmedLine = line.trim()
			
			if (trimmedLine.isNotBlank()) {
				// Remover símbolos de encabezados múltiples
				while (trimmedLine.startsWith("#")) {
					trimmedLine = trimmedLine.substring(1).trim()
				}
				
				// Remover guiones múltiples
				while (trimmedLine.startsWith("-")) {
					trimmedLine = trimmedLine.substring(1).trim()
				}
				
				// Remover asteriscos
				while (trimmedLine.startsWith("*")) {
					trimmedLine = trimmedLine.substring(1).trim()
				}
				
				// Si la línea aún tiene contenido después de limpiar
				if (trimmedLine.isNotBlank()) {
					// Agregar viñeta si no tiene una
					if (!trimmedLine.startsWith("•")) {
						formattedLines.add("• $trimmedLine")
					} else {
						formattedLines.add(trimmedLine)
					}
				}
			}
		}

		formattedLines.add("")
		formattedLines.add("ℹ️ ${getString(R.string.update_free_download)}")

		return formattedLines.joinToString("\n")
	}

	private fun openApkLisDownload() {
		try {
			val uri = Uri.parse("https://www.apklis.cu/application/cu.maxwell.firenetstats")
			val intent = Intent(Intent.ACTION_VIEW, uri)
			startActivity(intent)
		} catch (e: Exception) {
			Toast.makeText(this, "Error al abrir el navegador", Toast.LENGTH_SHORT).show()
		}
	}

}

