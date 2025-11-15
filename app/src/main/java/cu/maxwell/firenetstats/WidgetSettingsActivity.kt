package cu.maxwell.firenetstats

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.maxwell.firenetstats.databinding.ActivityWidgetSettingsBinding

class WidgetSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetSettingsBinding
	private var currentWidgetColor: Int = Color.parseColor("#CC121212") // Color por defecto
    private var defaultWidgetColor: Int = Color.parseColor("#CC121212") // Color predeterminado
	private var downloadArrowColor: Int = Color.parseColor("#2196F3") // Azul por defecto
	private var downloadTextColor: Int = Color.parseColor("#2196F3")
	private var uploadArrowColor: Int = Color.parseColor("#FF5722") // Naranja por defecto
	private var uploadTextColor: Int = Color.parseColor("#FF5722")
	private var networkIconColor = 0
	private var isPreviewMode = false
    
	override fun onCreate(savedInstanceState: Bundle?) {
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		super.onCreate(savedInstanceState)
		binding = ActivityWidgetSettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		
		supportActionBar?.hide()
		
		// Informar al servicio que la ventana de ajustes está abierta
		if (FloatingWidgetService.isRunning) {
			val intent = Intent(this, FloatingWidgetService::class.java)
			intent.action = "SETTINGS_OPEN"
			startService(intent)
		}
		
		setupDialogWindow()
		loadSavedSettings()
		setupListeners()
	}
	
	override fun onDestroy() {
		super.onDestroy()

		// Informar al servicio que la ventana de ajustes se cerró
		if (FloatingWidgetService.isRunning) {
			val intent = Intent(this, FloatingWidgetService::class.java)
			intent.action = "SETTINGS_CLOSED"
			startService(intent)
		}

		// Si estábamos en modo vista previa, restaurar la configuración original
		if (isPreviewMode && FloatingWidgetService.isRunning) {
			val intent = Intent(this, FloatingWidgetService::class.java)
			intent.action = "CANCEL_PREVIEW"
			startService(intent)
		}
	}
    
    private fun setupDialogWindow() {
        val width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        window.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }
	
	private fun setViewBackgroundColor(view: View, color: Int) {
		val drawable = view.background
		if (drawable is GradientDrawable) {
			drawable.setColor(color)
		} else {
			// Si por alguna razón no es un GradientDrawable, usar setBackgroundColor
			view.setBackgroundColor(color)
		}
	}

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("widget_settings", MODE_PRIVATE)
        
        // Tamaño - ahora el valor por defecto es "medium"
        val size = prefs.getString("size", "medium") ?: "medium"
        when (size) {
            "small" -> binding.rbSmall.isChecked = true
            "medium" -> binding.rbMedium.isChecked = true
            "large" -> binding.rbLarge.isChecked = true
        }
        
        // Transparencia
        val transparency = prefs.getInt("transparency", 20)
        binding.sbTransparency.progress = transparency
        updateTransparencyLabel(transparency)
        
        // Información a mostrar
        val displayInfo = prefs.getString("display_info", "both") ?: "both"
        when (displayInfo) {
            "download_only" -> binding.rbDownloadOnly.isChecked = true
            "upload_only" -> binding.rbUploadOnly.isChecked = true
            "both" -> binding.rbBoth.isChecked = true
        }
        
		// Tema (aunque no se muestre en la UI, lo necesitamos para el color predeterminado)
		val isDarkTheme = prefs.getString("theme", "dark") == "dark"
		
		// Establecer color predeterminado según el tema
		defaultWidgetColor = if (isDarkTheme) 
			Color.parseColor("#CC121212") 
		else 
			Color.parseColor("#CCFFFFFF")
		
		// Mostrar el color predeterminado
		setViewBackgroundColor(binding.viewDefaultColor, defaultWidgetColor)
		
		// Cargar color del widget
		val widgetColorString = prefs.getString("widget_color", "default")
		if (widgetColorString == "default") {
			// Usar color predeterminado
			currentWidgetColor = defaultWidgetColor
			binding.etColorCode.setText(String.format("#%08X", currentWidgetColor))
		} else {
			try {
				currentWidgetColor = Color.parseColor(widgetColorString)
				binding.etColorCode.setText(widgetColorString)
			} catch (e: Exception) {
				// Si hay error, usar color predeterminado
				currentWidgetColor = defaultWidgetColor
				binding.etColorCode.setText(String.format("#%08X", currentWidgetColor))
			}
		}
		
		// Actualizar vista previa del color actual
		setViewBackgroundColor(binding.viewCurrentColor, currentWidgetColor)
		
		// Cargar colores de texto y flechas
		downloadArrowColor = prefs.getInt("download_arrow_color", ContextCompat.getColor(this, R.color.secondary_color))
		downloadTextColor = prefs.getInt("download_text_color", ContextCompat.getColor(this, R.color.secondary_color))
		uploadArrowColor = prefs.getInt("upload_arrow_color", ContextCompat.getColor(this, R.color.primary_color))
		uploadTextColor = prefs.getInt("upload_text_color", ContextCompat.getColor(this, R.color.primary_color))
		networkIconColor = prefs.getInt("network_icon_color", ContextCompat.getColor(this, R.color.good_connection))
		
		// Actualizar vistas de colores
		setViewBackgroundColor(binding.viewDownloadArrowColor, downloadArrowColor)
		setViewBackgroundColor(binding.viewDownloadTextColor, downloadTextColor)
		setViewBackgroundColor(binding.viewUploadArrowColor, uploadArrowColor)
		setViewBackgroundColor(binding.viewUploadTextColor, uploadTextColor)
		setViewBackgroundColor(binding.viewNetworkIconColor, networkIconColor)
    }
    
	private fun setupListeners() {
		// Listener para la barra de transparencia
		binding.sbTransparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
				updateTransparencyLabel(progress)
				if (fromUser) applyPreview()
			}
			
			override fun onStartTrackingTouch(seekBar: SeekBar?) {}
			
			override fun onStopTrackingTouch(seekBar: SeekBar?) {}
		})
		
		// Nuevo listener para el botón "Cambiar" color del widget
		binding.btnChangeWidgetColor.setOnClickListener {
			showColorPickerDialog("Seleccionar color del widget", currentWidgetColor) { color ->
				currentWidgetColor = color
				setViewBackgroundColor(binding.viewCurrentColor, color)
				binding.etColorCode.setText(String.format("#%08X", currentWidgetColor))
				applyPreview()
			}
		}
		
		// Botón para aplicar color personalizado
		binding.btnApplyColor.setOnClickListener {
			val colorCode = binding.etColorCode.text.toString()
			try {
				val color = Color.parseColor(colorCode)
				currentWidgetColor = color
				setViewBackgroundColor(binding.viewCurrentColor, currentWidgetColor)
				applyPreview()
			} catch (e: Exception) {
				Toast.makeText(this, "Formato de color inválido", Toast.LENGTH_SHORT).show()
			}
		}
		
		// Botón para usar color predeterminado
		binding.btnUseDefault.setOnClickListener {
			currentWidgetColor = defaultWidgetColor
			setViewBackgroundColor(binding.viewCurrentColor, currentWidgetColor)
			binding.etColorCode.setText(String.format("#%08X", currentWidgetColor))
			applyPreview()
		}
		
		// Listeners para los botones de cambio de color de texto y flechas
		binding.btnChangeDownloadArrowColor.setOnClickListener {
			showColorPickerDialog("Seleccionar color de flecha de descarga", downloadArrowColor) { color ->
				downloadArrowColor = color
				setViewBackgroundColor(binding.viewDownloadArrowColor, color)
				applyPreview()
			}
		}

		binding.btnChangeDownloadTextColor.setOnClickListener {
			showColorPickerDialog("Seleccionar color de texto de descarga", downloadTextColor) { color ->
				downloadTextColor = color
				setViewBackgroundColor(binding.viewDownloadTextColor, color)
				applyPreview()
			}
		}

		binding.btnChangeUploadArrowColor.setOnClickListener {
			showColorPickerDialog("Seleccionar color de flecha de carga", uploadArrowColor) { color ->
				uploadArrowColor = color
				setViewBackgroundColor(binding.viewUploadArrowColor, color)
				applyPreview()
			}
		}

		binding.btnChangeUploadTextColor.setOnClickListener {
			showColorPickerDialog("Seleccionar color de texto de carga", uploadTextColor) { color ->
				uploadTextColor = color
				setViewBackgroundColor(binding.viewUploadTextColor, color)
				applyPreview()
			}
		}
		
		binding.btnChangeNetworkIconColor.setOnClickListener {
			showColorPickerDialog("Seleccionar color del icono de red", networkIconColor) { color ->
				networkIconColor = color
				setViewBackgroundColor(binding.viewNetworkIconColor, color)
				applyPreview()
			}
		}
		
		// Botón para restablecer colores predeterminados
		binding.btnResetTextColors.setOnClickListener {
			resetTextColors()
			applyPreview()
		}
		
		// Botón Guardar
		binding.btnSave.setOnClickListener {
			saveSettings()
			finish()
		}
		
		// Botón Cancelar
		binding.btnCancel.setOnClickListener {
			finish()
		}
		
		// Botón Restablecer
		binding.btnReset.setOnClickListener {
			showResetConfirmationDialog()
		}
	}
	
	// Método para restablecer los colores de texto y flechas a sus valores predeterminados
	private fun resetTextColors() {
		// Restablecer colores a sus valores predeterminados
		downloadArrowColor = ContextCompat.getColor(this, R.color.secondary_color)
		downloadTextColor = ContextCompat.getColor(this, R.color.secondary_color)
		uploadArrowColor = ContextCompat.getColor(this, R.color.primary_color)
		uploadTextColor = ContextCompat.getColor(this, R.color.primary_color)
		networkIconColor = ContextCompat.getColor(this, R.color.good_connection)
		
		// Actualizar las vistas con los colores predeterminados
		setViewBackgroundColor(binding.viewDownloadArrowColor, downloadArrowColor)
		setViewBackgroundColor(binding.viewDownloadTextColor, downloadTextColor)
		setViewBackgroundColor(binding.viewUploadArrowColor, uploadArrowColor)
		setViewBackgroundColor(binding.viewUploadTextColor, uploadTextColor)
		setViewBackgroundColor(binding.viewNetworkIconColor, networkIconColor)
		
		// Mostrar mensaje de confirmación
		Toast.makeText(this, "Colores restablecidos", Toast.LENGTH_SHORT).show()
	}

	// Método para aplicar vista previa
	private fun applyPreview() {
		// Marcar que estamos en modo vista previa
		isPreviewMode = true
		
		// Verificar si el servicio está en ejecución
		if (FloatingWidgetService.isRunning) {
			// Crear un intent con los valores actuales (sin guardarlos en preferencias)
			val intent = Intent(this, FloatingWidgetService::class.java)
			intent.action = "PREVIEW_WIDGET_SETTINGS"
			
			// Añadir todos los valores actuales como extras
			intent.putExtra("size", when {
				binding.rbSmall.isChecked -> "small"
				binding.rbMedium.isChecked -> "medium"
				binding.rbLarge.isChecked -> "large"
				else -> "medium"
			})
			
			intent.putExtra("transparency", binding.sbTransparency.progress)
			
			intent.putExtra("display_info", when {
				binding.rbDownloadOnly.isChecked -> "download_only"
				binding.rbUploadOnly.isChecked -> "upload_only"
				binding.rbBoth.isChecked -> "both"
				else -> "both"
			})
			
			intent.putExtra("theme", "dark")
			
			// Añadir colores personalizados
			intent.putExtra("widget_color", if (currentWidgetColor == defaultWidgetColor) 
				"default" else String.format("#%08X", currentWidgetColor))
			
			intent.putExtra("download_arrow_color", downloadArrowColor)
			intent.putExtra("download_text_color", downloadTextColor)
			intent.putExtra("upload_arrow_color", uploadArrowColor)
			intent.putExtra("upload_text_color", uploadTextColor)
			intent.putExtra("network_icon_color", networkIconColor)
			
			// Enviar el intent al servicio
			startService(intent)
		} else {
			// Si el servicio no está en ejecución, mostrar un mensaje
			Toast.makeText(this, "El widget no está activo", Toast.LENGTH_SHORT).show()
			isPreviewMode = false
		}
	}
	
	// Método mejorado para mostrar el selector de colores
	private fun showColorPickerDialog(title: String, currentColor: Int, onColorSelected: (Int) -> Unit) {
		// Definir colores comunes (sin transparencia para texto y flechas)
		val colors = arrayOf(
			"#000000", // Negro
			"#FFFFFF", // Blanco
			"#2196F3", // Azul
			"#F44336", // Rojo
			"#4CAF50", // Verde
			"#FFEB3B", // Amarillo
			"#FF9800", // Naranja
			"#9C27B0", // Púrpura
			"#795548", // Marrón
			"#607D8B", // Gris azulado
			"#3F51B5", // Índigo
			"#009688", // Verde azulado
			"#CDDC39", // Lima
			"#FFC107", // Ámbar
			"#E91E63", // Rosa
			"#673AB7", // Violeta
			"#00BCD4", // Cian
			"#8BC34A", // Verde claro
			"#FF5722", // Naranja profundo
			"#9E9E9E"  // Gris
		)
		
		val colorNames = arrayOf(
			"Negro", "Blanco", "Azul", "Rojo", "Verde", 
			"Amarillo", "Naranja", "Púrpura", "Marrón", "Gris azulado",
			"Índigo", "Verde azulado", "Lima", "Ámbar", "Rosa",
			"Violeta", "Cian", "Verde claro", "Naranja", "Gris"
		)
		
		// Convertir el color actual a formato hexadecimal para comparar
		val currentColorHex = String.format("#%06X", 0xFFFFFF and currentColor)
		Log.d("WidgetSettings", "Current color: $currentColorHex")
		
		// Crear un layout personalizado para mostrar los colores en una cuadrícula
		val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
		val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewColors)
		val titleTextView = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
		
		// Ocultar el TextView del título
		titleTextView.visibility = View.GONE
		
		// Configurar el RecyclerView
		recyclerView.layoutManager = GridLayoutManager(this, 4) // 4 columnas
		
		// Crear un adaptador personalizado para mostrar los colores
		val adapter = ColorAdapter(colors, colorNames, currentColorHex) { selectedColorString ->
			try {
				val selectedColor = Color.parseColor(selectedColorString)
				onColorSelected(selectedColor)
			} catch (e: Exception) {
				Log.e("WidgetSettings", "Error parsing color: $selectedColorString", e)
			}
		}
		
		recyclerView.adapter = adapter
		
		// Mostrar el diálogo CON TÍTULO
		val dialog = AlertDialog.Builder(this)
			.setTitle(title)
			.setView(dialogView)
			.setPositiveButton("Cerrar", null)
			.create()
		
		dialog.show()
	}
			
    private fun updateTransparencyLabel(progress: Int) {
        binding.tvTransparencyValue.text = "$progress%"
    }
    
	private fun saveSettings() {
		val prefs = getSharedPreferences("widget_settings", MODE_PRIVATE)
		val editor = prefs.edit()
		
		// Guardar tamaño
		val size = when {
			binding.rbSmall.isChecked -> "small"
			binding.rbMedium.isChecked -> "medium"
			binding.rbLarge.isChecked -> "large"
			else -> "medium"
		}
		editor.putString("size", size)
		
		// Guardar transparencia
		val transparency = binding.sbTransparency.progress
		editor.putInt("transparency", transparency)
		
		// Guardar información a mostrar
		val displayInfo = when {
			binding.rbDownloadOnly.isChecked -> "download_only"
			binding.rbUploadOnly.isChecked -> "upload_only"
			binding.rbBoth.isChecked -> "both"
			else -> "both"
		}
		editor.putString("display_info", displayInfo)
		
		// Guardar tema
		editor.putString("theme", "dark")
		
		// Guardar colores personalizados
		if (currentWidgetColor == defaultWidgetColor) {
			editor.putString("widget_color", "default")
		} else {
			editor.putString("widget_color", String.format("#%08X", currentWidgetColor))
		}
		
		editor.putInt("download_arrow_color", downloadArrowColor)
		editor.putInt("download_text_color", downloadTextColor)
		editor.putInt("upload_arrow_color", uploadArrowColor)
		editor.putInt("upload_text_color", uploadTextColor)
		editor.putInt("network_icon_color", networkIconColor)
		
		editor.apply()
		
		// Aplicar cambios al widget
		applyChangesToWidget()
		
		// Resetear el modo vista previa
		isPreviewMode = false
	}

    private fun applyChangesToWidget() {
        // Solo aplicar cambios si el widget está activo
        if (FloatingWidgetService.isRunning) {
            val intent = Intent(this, FloatingWidgetService::class.java)
            intent.action = "UPDATE_WIDGET_SETTINGS"
            startService(intent)
        }
    }
   
    private fun restartWidget() {
        // Solo reiniciar si el widget está activo
        if (FloatingWidgetService.isRunning) {
            // Detener el servicio actual
            val stopIntent = Intent(this, FloatingWidgetService::class.java)
            stopService(stopIntent)
            
            // Esperar un momento para asegurar que el servicio se detenga completamente
            Handler(Looper.getMainLooper()).postDelayed({
                // Verificar que el servicio realmente se haya detenido
                if (!FloatingWidgetService.isRunning) {
                    // Iniciar el servicio nuevamente
                    val startIntent = Intent(this, FloatingWidgetService::class.java)
                    startService(startIntent)
                }
            }, 200) // Pequeño retraso para asegurar que el servicio se detenga
        }
    }
    
    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_settings)
            .setMessage(R.string.reset_confirmation)
            .setPositiveButton(R.string.yes) { _, _ ->
                // Primero detener el servicio
                if (FloatingWidgetService.isRunning) {
                    val stopIntent = Intent(this, FloatingWidgetService::class.java)
                    stopService(stopIntent)
                    
                    // Esperar a que el servicio se detenga
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Restablecer configuraciones
                        resetSettings()
                        
                        // Iniciar el servicio nuevamente
                        val startIntent = Intent(this, FloatingWidgetService::class.java)
                        startService(startIntent)
                        
                        // Cerrar la actividad
                        finish()
                    }, 300)
                } else {
                    // Si el servicio no está en ejecución, simplemente restablecer
                    resetSettings()
                    finish()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }
    
    private fun resetSettings() {
        // Restablecer valores por defecto en las preferencias
        val prefs = getSharedPreferences("widget_settings", MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putString("size", "medium")
        editor.putInt("transparency", 20)
        editor.putString("display_info", "both")
        editor.putString("units", "auto") // Siempre auto
        editor.putString("theme", "dark")
        editor.putString("widget_color", "default")
        
        // Restablecer colores de texto y flechas
        val defaultDownloadColor = ContextCompat.getColor(this, R.color.secondary_color)
        val defaultUploadColor = ContextCompat.getColor(this, R.color.primary_color)
		networkIconColor = ContextCompat.getColor(this, R.color.good_connection)
        
        editor.putInt("download_arrow_color", defaultDownloadColor)
        editor.putInt("download_text_color", defaultDownloadColor)
        editor.putInt("upload_arrow_color", defaultUploadColor)
        editor.putInt("upload_text_color", defaultUploadColor)
		editor.putInt("network_icon_color", networkIconColor)
        
        editor.apply()
        
        // Actualizar variables
        defaultWidgetColor = Color.parseColor("#CC121212") // Tema oscuro por defecto
        currentWidgetColor = defaultWidgetColor
        downloadArrowColor = defaultDownloadColor
        downloadTextColor = defaultDownloadColor
        uploadArrowColor = defaultUploadColor
        uploadTextColor = defaultUploadColor
        
        // Actualizar UI
        binding.rbMedium.isChecked = true
        binding.sbTransparency.progress = 20
        updateTransparencyLabel(20)
        binding.rbBoth.isChecked = true
        binding.etColorCode.setText(String.format("#%08X", currentWidgetColor))
        
        // Actualizar vistas de colores
        setViewBackgroundColor(binding.viewCurrentColor, currentWidgetColor)
        setViewBackgroundColor(binding.viewDefaultColor, defaultWidgetColor)
        setViewBackgroundColor(binding.viewDownloadArrowColor, downloadArrowColor)
        setViewBackgroundColor(binding.viewDownloadTextColor, downloadTextColor)
        setViewBackgroundColor(binding.viewUploadArrowColor, uploadArrowColor)
        setViewBackgroundColor(binding.viewUploadTextColor, uploadTextColor)
		setViewBackgroundColor(binding.viewNetworkIconColor, networkIconColor)
        
        restartWidget()
    }
    
	// Clase adaptadora para el selector de colores
	inner class ColorAdapter(
		private val colors: Array<String>,
		private val colorNames: Array<String>,
		private val onColorSelected: (String) -> Unit
	) : RecyclerView.Adapter<ColorAdapter.ColorViewHolder>() {
		
		inner class ColorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
			val colorView: View = itemView.findViewById(R.id.viewColor)
			val colorNameText: TextView = itemView.findViewById(R.id.tvColorName)
			
			init {
				itemView.setOnClickListener {
					onColorSelected(colors[adapterPosition])
				}
			}
		}
		
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorViewHolder {
			val view = layoutInflater.inflate(R.layout.item_color, parent, false)
			return ColorViewHolder(view)
		}
		
		override fun onBindViewHolder(holder: ColorViewHolder, position: Int) {
			val color = Color.parseColor(colors[position])
			holder.colorView.setBackgroundColor(color)
			holder.colorNameText.text = colorNames[position]
			holder.itemView.contentDescription = colorNames[position]
		}
		
		override fun getItemCount(): Int = colors.size
	}

}
