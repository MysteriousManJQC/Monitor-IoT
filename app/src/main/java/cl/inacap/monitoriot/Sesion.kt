package cl.inacap.monitoriot

import android.content.Context
import cl.inacap.monitoriot.datos.BaseDatos
import cl.inacap.monitoriot.enlace.Enlace
import cl.inacap.monitoriot.enlace.Mensaje
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Estado de la app mientras dura la conexión. Vive fuera de la pantalla,
// así que sobrevive a la rotación y a cambiar de pantalla.
// Adaptado al proyecto de Unidad 1: Sistema de Seguridad Perimetral (PIR + Ultrasónico HC-SR04 + Relé/Sirena).
object Sesion {
    // Umbral de proximidad de intrusión definido en Unidad 1 (sketch.ino: distancia_cm < 100.0)
    const val UMBRAL_DISTANCIA = 100.0

    var usuario: String? = null
    var rol = "observador"
    var enlace: Enlace? = null
        private set

    // Lectura de distancia ultrasónica en cm (HC-SR04) y estado del sensor PIR
    val distancia = MutableStateFlow<Double?>(null)
    val pirDetectado = MutableStateFlow(false)
    // Actuador: Sirena Central + Foco Disuasivo (Relé)
    val alarmaActiva = MutableStateFlow(false)

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trabajo: Job? = null

    fun iniciar(ctx: Context, db: BaseDatos, nuevo: Enlace, esNodo: Boolean) {
        cerrar()
        enlace = nuevo
        val app = ctx.applicationContext
        trabajo = alcance.launch {
            // Si se corta el enlace: avisar y, en el nodo, volver al estado seguro (IEC 62443 OT).
            launch {
                var antes = ""
                nuevo.estado.collect { e ->
                    if (antes == "Conectado" && e != "Conectado") {
                        if (esNodo) alarmaActiva.value = false
                        Notificaciones.mostrar(
                            app,
                            "Enlace perdido",
                            "Se cortó la conexión con el otro teléfono. Estado seguro activado."
                        )
                    }
                    antes = e
                }
            }
            nuevo.entrantes.collect { m ->
                when {
                    // Nodo recibe comando desde el Panel para encender/apagar Sirena y Foco (Relé)
                    esNodo && m.tipo == "CMD" && m.clave == "alarma" -> {
                        alarmaActiva.value = m.valor == "ON"
                        nuevo.enviar(Mensaje("ACK", "alarma", m.valor))
                        Notificaciones.mostrar(
                            app,
                            "Comando recibido",
                            "Sirena y Foco (Relé): ${if (m.valor == "ON") "ACTIVADOS" else "DESACTIVADOS"}"
                        )
                    }
                    // Panel recibe lectura periódica de distancia (HC-SR04) desde el Nodo
                    !esNodo && m.tipo == "LECTURA" && m.clave == "dist" -> {
                        val v = m.valor.toDoubleOrNull() ?: return@collect
                        val anterior = distancia.value
                        distancia.value = v
                        db.guardarLectura("dist", v)
                        // Alerta si el intruso entra al perímetro (< 100 cm) sin repetir hasta que salga y vuelva a entrar
                        if (v < UMBRAL_DISTANCIA && (anterior == null || anterior >= UMBRAL_DISTANCIA)) {
                            Notificaciones.mostrar(
                                app,
                                "Alerta de intrusión perimetral",
                                String.format(Locale.US, "Intruso detectado a %.1f cm del perímetro", v)
                            )
                        }
                    }
                    // Panel recibe evento de movimiento PIR desde el Nodo
                    !esNodo && m.tipo == "LECTURA" && m.clave == "pir" -> {
                        val detectado = m.valor == "1"
                        val anterior = pirDetectado.value
                        pirDetectado.value = detectado
                        if (detectado) {
                            db.guardarLectura("pir", 1.0)
                            if (!anterior) {
                                Notificaciones.mostrar(
                                    app,
                                    "Alerta Sensor PIR",
                                    "Movimiento infrarrojo detectado en el perímetro exterior"
                                )
                            }
                        }
                    }
                    // Panel recibe confirmación ACK del estado del actuador en el Nodo
                    !esNodo && m.tipo == "ACK" && m.clave == "alarma" -> {
                        alarmaActiva.value = m.valor == "ON"
                    }
                }
            }
        }
    }

    fun cerrar() {
        trabajo?.cancel()
        enlace?.cerrar()
        enlace = null
        distancia.value = null
        pirDetectado.value = false
        alarmaActiva.value = false
    }
}
