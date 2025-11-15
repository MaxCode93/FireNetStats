package cu.maxwell.firenetstats

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ColorAdapter(
    private val colors: Array<String>,
    private val colorNames: Array<String>,
    private val selectedColorHex: String,
    private val onColorSelected: (String) -> Unit
) : RecyclerView.Adapter<ColorAdapter.ColorViewHolder>() {

    private var selectedPosition = -1

    init {
        // Encontrar la posición del color seleccionado
        for (i in colors.indices) {
            if (colors[i].equals(selectedColorHex, ignoreCase = true)) {
                selectedPosition = i
                break
            }
        }
        Log.d("ColorAdapter", "Selected color: $selectedColorHex, position: $selectedPosition")
    }

    class ColorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorView: View = view.findViewById(R.id.viewColor)
        val colorName: TextView = view.findViewById(R.id.tvColorName)
        val cardView: MaterialCardView = view.findViewById(R.id.cardColor)
        val selectedText: TextView = view.findViewById(R.id.tvSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color, parent, false)
        return ColorViewHolder(view)
    }

    override fun onBindViewHolder(holder: ColorViewHolder, position: Int) {
        val color = colors[position]
        val name = colorNames[position]
        
        try {
            val colorInt = Color.parseColor(color)
            holder.colorView.setBackgroundColor(colorInt)
            holder.colorName.text = name
            
            // Mostrar indicador de selección si este color es el seleccionado
            if (position == selectedPosition) {
                holder.selectedText.visibility = View.VISIBLE
                holder.cardView.cardElevation = 8f
                // Cambiar el borde para el elemento seleccionado
                holder.cardView.strokeWidth = 3
                holder.cardView.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.primary_color)
                
                // Ajustar el color del indicador para que sea visible
                val brightness = calculateBrightness(colorInt)
                if (brightness > 0.6) {
                    holder.selectedText.setTextColor(Color.BLACK)
                    holder.selectedText.setShadowLayer(2f, 1f, 1f, Color.WHITE)
                } else {
                    holder.selectedText.setTextColor(Color.WHITE)
                    holder.selectedText.setShadowLayer(2f, 1f, 1f, Color.BLACK)
                }
                
                Log.d("ColorAdapter", "Showing indicator for position: $position")
            } else {
                holder.selectedText.visibility = View.GONE
                holder.cardView.cardElevation = 2f
                // Borde normal para elementos no seleccionados
                holder.cardView.strokeWidth = 1
                holder.cardView.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.divider_color)
            }
            
            holder.itemView.setOnClickListener {
                val oldPosition = selectedPosition
                selectedPosition = holder.adapterPosition
                notifyItemChanged(oldPosition)
                notifyItemChanged(selectedPosition)
                onColorSelected(colors[holder.adapterPosition])
            }
        } catch (e: Exception) {
            holder.colorName.text = "Error"
        }
    }
    
    // Función para calcular el brillo de un color (0-1)
    private fun calculateBrightness(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        
        // Fórmula para calcular el brillo percibido
        return (0.299 * r + 0.587 * g + 0.114 * b)
    }

    override fun getItemCount() = colors.size
}
