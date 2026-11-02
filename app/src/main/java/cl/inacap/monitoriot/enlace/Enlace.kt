package cl.inacap.monitoriot.enlace

import cl.inacap.monitoriot.seguridad.Cripto
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

// Una línea de texto por mensaje: TIPO;CLAVE;VALOR
// Proyecto Seguridad Perimetral (Unidad 1 -> Unidad 2):
// LECTURA;dist;85.0  (nodo → panel: distancia ultrasónico HC-SR04 en cm)
// LECTURA;pir;1      (nodo → panel: sensor PIR movimiento detectado)
// CMD;alarma;ON      (panel → nodo: activar relé foco disuasivo y sirena)
// ACK;alarma;ON      (nodo → panel: confirmación de estado del actuador)
data class Mensaje(val tipo: String, val clave: String, val valor: String) {
    fun aLinea() = "$tipo;$clave;$valor"

    companion object {
        private val TIPOS = setOf("LECTURA", "CMD", "ACK")

        // Todo lo que no calce con el formato se descarta (medida de seguridad ISO/IEC 27400).
        fun desdeLinea(linea: String): Mensaje? {
            val p = linea.split(";")
            if (p.size != 3 || p[0] !in TIPOS || p[1].length > 16 || p[2].length > 16) return null
            return Mensaje(p[0], p[1], p[2])
        }
    }
}

// Lo que el resto de la app necesita saber de la conexión, sea Bluetooth o Wi-Fi.
interface Enlace {
    val estado: StateFlow<String>
    val entrantes: SharedFlow<Mensaje>
    suspend fun enviar(m: Mensaje)
    fun cerrar()
}

// Lógica común: leer líneas cifradas de un socket y escribir líneas cifradas en él.
abstract class EnlaceBase(private val clave: SecretKey) : Enlace {
    protected val _estado = MutableStateFlow("Desconectado")
    override val estado: StateFlow<String> = _estado
    private val _entrantes = MutableSharedFlow<Mensaje>(extraBufferCapacity = 64)
    override val entrantes: SharedFlow<Mensaje> = _entrantes
    protected val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var escritor: BufferedWriter? = null

    // Se llama cuando el socket quedó conectado; bloquea hasta que se corta.
    protected fun atender(entrada: InputStream, salida: OutputStream) {
        escritor = salida.bufferedWriter()
        _estado.value = "Conectado"
        try {
            entrada.bufferedReader().forEachLine { linea ->
                val texto = Cripto.descifrar(linea, clave) ?: return@forEachLine
                Mensaje.desdeLinea(texto)?.let { _entrantes.tryEmit(it) }
            }
        } catch (e: IOException) {
            // El otro equipo se desconectó o se cerró el socket.
        }
        escritor = null
        _estado.value = "Desconectado"
    }

    override suspend fun enviar(m: Mensaje) {
        val w = escritor ?: return
        withContext(Dispatchers.IO) {
            try {
                synchronized(w) {
                    w.write(Cripto.cifrar(m.aLinea(), clave))
                    w.newLine()
                    w.flush()
                }
            } catch (e: IOException) {
                _estado.value = "Desconectado"
            }
        }
    }

    protected fun terminar() = alcance.cancel()
}
