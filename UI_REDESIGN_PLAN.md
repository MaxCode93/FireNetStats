# 🎨 Plan de Rediseño UI - FireNetStats

**Fecha**: 14 de noviembre de 2025  
**Estado**: Pendiente de implementación  
**Aprobado**: ✅ Sí

---

## 📋 Resumen Ejecutivo

Mejora de la interfaz actual manteniendo la estructura general, con enfoque en:
- Nuevo esquema de colores (azul, verde, blanco)
- Cambio de gráfico de líneas a barras
- Implementación de lista de apps con consumo de datos
- Efecto pulsante en botón del widget

---

## 1️⃣ CAMBIOS EN COLORES

### Paleta de Colores Nueva
- **Verde**: `#4CAF50` - Descarga (↓)
- **Azul**: `#2196F3` - Carga (↑)
- **Blanco**: `#FFFFFF` - Textos
- **Fondo Oscuro**: `#0F1419` - Tema oscuro
- **Fondo Claro**: `#F5F5F5` - Tema claro

### Archivos a Modificar
- `res/values/colors.xml` - Agregar nuevos colores
- `res/values-night/colors.xml` - Colores para tema oscuro
- `res/values/themes.xml` - Aplicar colores
- `res/values-night/themes.xml` - Aplicar colores tema oscuro

---

## 2️⃣ GRÁFICO - CAMBIO A BARRAS

### Características
- ✅ Cambiar de **LineChart** a **BarChart**
- ✅ Mostrar **últimos 7 días**
- ✅ Cada barra con **etiqueta superior** (consumo total del día)
- ✅ Formato: "2.5GB", "1.8GB", etc.
- ✅ Colores: Verde (descarga) + Azul (carga) por barra
- ✅ **Tamaño reducido** (más compacto)
- ✅ Eje X: Nombres de días (Lun, Mar, Mié, Jue, Vie, Sáb, Dom)

### Implementación
```kotlin
// Cambiar:
// private val downloadSpeedEntries = ArrayList<Entry>()
// private val uploadSpeedEntries = ArrayList<Entry>()

// A:
// private val dailyConsumption = ArrayList<BarEntry>()
```

### Archivos a Modificar
- `MainActivity.kt` - Lógica del gráfico
- `activity_main.xml` - Layout del BarChart

---

## 3️⃣ LISTA DE APLICACIONES

### Estructura por Item
```
┌─────────────────────────────┬──────────┐
│ 📱 Nombre App               │          │
│    ▓▓▓░░░░░░░░░░░░░░░░░░░░ │ 300 MB   │
│                             │ (total)  │
└─────────────────────────────┴──────────┘
```

### Detalles
- **Parte Izquierda**:
  - Icono de app (40x40dp)
  - Nombre de app
  - Progress bar fina (3-4dp de alto)
    - Verde (descarga) - tamaño proporcional
    - Azul (carga) - tamaño proporcional
  
- **Parte Derecha**:
  - Total consumo (descarga + carga en MB/GB)

- **Progress Bar**:
  - SIN etiquetas de texto
  - Solo visual
  - El color más grande indica mayor consumo

### Datos Necesarios
- Obtener consumo por app del dispositivo
- Usar `NetworkStatsManager` (API 23+)
- Top 5-10 apps por consumo
- Sumar descarga + carga

### Archivos a Crear
1. **`utils/AppUsageItem.kt`** - Data class
   ```kotlin
   data class AppUsageItem(
       val appName: String,
       val packageName: String,
       val downloadBytes: Long,
       val uploadBytes: Long,
       val icon: Drawable?
   )
   ```

2. **`adapter/AppUsageAdapter.kt`** - RecyclerView Adapter
   - Mostrar lista de apps
   - Calcular progress bar
   - Formatear tamaños (MB/GB)

3. **`res/layout/item_app_usage.xml`** - Layout del item
   - LinearLayout horizontal
   - ImageView (icono)
   - TextView (nombre)
   - Custom ProgressBar (dos colores)
   - TextView (total)

### Archivos a Modificar
- `MainActivity.kt` - Lógica para obtener apps
- `activity_main.xml` - RecyclerView para lista de apps
- `AndroidManifest.xml` - Permisos necesarios

---

## 4️⃣ TOTAL DE DATOS USADOS

### Ubicación
- **Debajo de la lista de apps**
- Card o TextView destacado

### Formato
```
┌──────────────────────────┐
│ Total Usado Hoy: 2.5 GB  │
└──────────────────────────┘
```

### Implementación
- Sumar consumo de todas las apps
- Formatear en MB o GB
- Mostrar dinámicamente

---

## 5️⃣ BOTÓN WIDGET - EFECTO PULSANTE

### Texto Dinámico
- Si widget está **inactivo**: "✨ ACTIVAR WIDGET"
- Si widget está **activo**: "✨ DESACTIVAR WIDGET"

### Efecto Pulsante
- **Animación de escala**: 1.0 → 1.1 → 1.0
- **Animación de opacidad**: 1.0 → 0.7 → 1.0
- **Duración**: 1.5s
- **Repetición**: Infinita

### Archivos a Crear
1. **`res/anim/pulse_button.xml`** - Animación mejorada
   ```xml
   <set>
     <scale fromXScale="1.0" toXScale="1.1" ... />
     <alpha fromAlpha="1.0" toAlpha="0.7" ... />
   </set>
   ```

### Archivos a Modificar
- `MainActivity.kt` - Aplicar animación dinámicamente
- `activity_main.xml` - Botón del widget

---

## 6️⃣ MANTENER IGUAL

- ✅ Estructura general de la app
- ✅ Header con botones (Update, Config, About)
- ✅ Información de red actual (WiFi/Móvil, Velocidad, etc.)
- ✅ Widget flotante y configuración
- ✅ Lógica de actualizaciones
- ✅ Permisos actuales

---

## 📁 Archivos a Crear

```
app/src/main/java/cu/maxwell/firenetstats/
├── utils/
│   └── AppUsageItem.kt          (NEW)
├── adapter/
│   └── AppUsageAdapter.kt       (NEW)
└── ...

app/src/main/res/
├── layout/
│   └── item_app_usage.xml       (NEW)
├── anim/
│   └── pulse_button.xml         (NEW)
└── ...
```

---

## 📝 Archivos a Modificar

1. **`app/build.gradle`** - Verificar dependencias
2. **`res/values/colors.xml`** - Nuevos colores
3. **`res/values-night/colors.xml`** - Colores tema oscuro
4. **`res/values/themes.xml`** - Aplicar colores
5. **`res/values-night/themes.xml`** - Aplicar colores tema oscuro
6. **`res/layout/activity_main.xml`** - Agregar RecyclerView, cambiar gráfico
7. **`MainActivity.kt`** - Lógica completa
8. **`AndroidManifest.xml`** - Permisos (si es necesario)

---

## 🔧 Requisitos Técnicos

### Permisos Necesarios
```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
```

### Dependencias
- MPAndroidChart (ya existe) - BarChart
- RecyclerView (ya existe)
- Kotlin Coroutines (ya existe)

### APIs Mínima
- API 23+ para NetworkStatsManager
- API 24+ para acceso a stats de apps

---

## 📊 Timeline Estimado

| Tarea | Tiempo Estimado |
|-------|-----------------|
| Crear data classes y adapter | 30 min |
| Crear layouts | 30 min |
| Cambiar gráfico a barras | 20 min |
| Implementar lógica de apps | 45 min |
| Estilos y colores | 20 min |
| Animaciones | 15 min |
| Testing y ajustes | 20 min |
| **TOTAL** | **~3 horas** |

---

## ✅ Checklist de Implementación

- [ ] Crear AppUsageItem.kt
- [ ] Crear AppUsageAdapter.kt
- [ ] Crear item_app_usage.xml
- [ ] Crear pulse_button.xml
- [ ] Actualizar colors.xml (claro)
- [ ] Actualizar colors.xml (oscuro)
- [ ] Actualizar themes.xml (claro)
- [ ] Actualizar themes.xml (oscuro)
- [ ] Cambiar gráfico LineChart → BarChart
- [ ] Implementar lógica de obtener apps
- [ ] Agregar RecyclerView a activity_main.xml
- [ ] Agregar botón widget con efecto pulsante
- [ ] Probar en tema claro
- [ ] Probar en tema oscuro
- [ ] Compilar sin errores
- [ ] Testing final

---

## 🚀 Estado Actual

**Pendiente de**: Esperar indicación del usuario para continuar

**Última actualización**: 14 de noviembre de 2025

---
