package cl.inacap.monitoriot.enlace

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EnlaceHttp.kt (Unidad 3 - Integración con ESP32 en Hertzbench sobre Wi-Fi)
 * Implementa la interfaz Enlace de la Unidad 2 reemplazando el transporte por HTTP
 * hacia el WebServer(:80) del ESP32, firmando cada comando con HMAC-SHA256 y un
 * contador estrictamente creciente (anti-repetición) y registrando en logcat con tag "HZB".
 */
class EnlaceHttp(urlInicial: String = DEFAULT_BOARD_URL) : Enlace {

    val baseUrl: String = normalizarBaseUrl(urlInicial)

    private val _estado = MutableStateFlow("Conectando a $baseUrl…")
    override val estado: StateFlow<String> = _estado

    private val _entrantes = MutableSharedFlow<Mensaje>(extraBufferCapacity = 64)
    override val entrantes: SharedFlow<Mensaje> = _entrantes

    // Última respuesta HTTP recibida del ESP32 para mostrar en vivo en PantallaPanel
    private val _ultimaRespuestaHttp = MutableStateFlow<String?>(null)
    val ultimaRespuestaHttp: StateFlow<String?> = _ultimaRespuestaHttp

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun iniciarMonitoreo() {
        alcance.launch {
            while (isActive) {
                try {
                    consultarLectura(baseUrl)
                } catch (e: Exception) {
                    Log.w(TAG_HZB, "esperando ESP32 en $baseUrl: ${e.message}")
                }
                delay(2_000)
            }
        }
    }

    /**
     * Consulta el endpoint /lectura del ESP32, verifica la firma HMAC-SHA256 de la lectura
     * y emite los valores al flujo de la aplicación.
     */
    suspend fun consultarLectura(base: String = baseUrl): String = withContext(Dispatchers.IO) {
        val url = URL("${normalizarBaseUrl(base)}lectura")
        val con = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            requestMethod = "GET"
        }
        try {
            val code = con.responseCode
            val cuerpo = (if (code < 400) con.inputStream else con.errorStream)
                ?.bufferedReader()?.readText()?.trim().orEmpty()
            Log.i(TAG_HZB, "lectura -> $code $cuerpo")

            if (code == 200 && procesarLecturaFirmada(cuerpo)) {
                _estado.value = "Conectado"
            }
            cuerpo
        } finally {
            con.disconnect()
        }
    }

    // Valida la firma HMAC de la trama LECTURA;dist;85.0;pir;0;alarma;OFF;n;1234;sig=...
    private fun procesarLecturaFirmada(linea: String): Boolean {
        val idxSig = linea.lastIndexOf(";sig=")
        if (idxSig <= 0) return false
        val payload = linea.substring(0, idxSig)
        val sigRecibida = linea.substring(idxSig + 5).trim()
        val sigEsperada = firmar(payload)
        if (!MessageDigest.isEqual(sigRecibida.lowercase().toByteArray(), sigEsperada.toByteArray())) {
            Log.w(TAG_HZB, "lectura descartada por firma HMAC invalida")
            return false
        }

        val partes = payload.split(";")
        // LECTURA;dist;<d>;pir;<p>;alarma;<a>;n;<t>
        for (i in 1 until partes.size - 1 step 2) {
            val k = partes[i]
            val v = partes[i + 1]
            when (k) {
                "dist" -> _entrantes.tryEmit(Mensaje("LECTURA", "dist", v))
                "pir" -> _entrantes.tryEmit(Mensaje("LECTURA", "pir", v))
                "alarma" -> _entrantes.tryEmit(Mensaje("ACK", "alarma", v))
            }
        }
        return true
    }

    override suspend fun enviar(m: Mensaje) {
        if (m.tipo == "CMD") {
            val accion = when {
                m.clave == "alarma" && m.valor == "ON" -> "SIRENA_ON"
                m.clave == "alarma" && m.valor == "OFF" -> "SIRENA_OFF"
                else -> "${m.clave.uppercase()}_${m.valor.uppercase()}"
            }
            val resp = enviarComando(baseUrl, accion)
            _ultimaRespuestaHttp.value = resp
            if (resp.contains("ACK;")) {
                _estado.value = "Conectado"
                _entrantes.tryEmit(Mensaje("ACK", m.clave, m.valor))
            }
        }
    }

    /**
     * Envía un comando válido firmado con HMAC-SHA256 y contador creciente.
     */
    suspend fun enviarAccionDirecta(accion: String): String {
        val resp = enviarComando(baseUrl, accion)
        _ultimaRespuestaHttp.value = resp
        if (resp.contains("ACK;")) {
            _estado.value = "Conectado"
            val nuevoEstado = if (accion.endsWith("ON")) "ON" else "OFF"
            _entrantes.tryEmit(Mensaje("ACK", "alarma", nuevoEstado))
        }
        return resp
    }

    /**
     * Botón de prueba de seguridad (Paso 3 de la guía):
     * Manda un comando con la firma alterada; el ESP32 debe responder 401.
     */
    suspend fun probarComandoFirmaAlterada(accion: String = "SIRENA_ON"): String {
        val resp = enviarComandoFirmaAlterada(baseUrl, accion)
        _ultimaRespuestaHttp.value = resp
        return resp
    }

    /**
     * Botón de prueba anti-replay (Criterio 3.1.1 y 3.1.4):
     * Manda un comando repitiendo el contador anterior; el ESP32 debe responder 409.
     */
    suspend fun probarComandoRepetido(accion: String = "SIRENA_ON"): String {
        val resp = enviarComandoRepetido(baseUrl, accion)
        _ultimaRespuestaHttp.value = resp
        return resp
    }

    override fun cerrar() {
        _estado.value = "Desconectado"
        alcance.cancel()
    }

    companion object {
        const val TAG_HZB = "HZB"
        const val DEFAULT_BOARD_URL = "http://192.168.4.11/"

        private val clave = "clave-compartida-demo".toByteArray()
        @Volatile
        private var contador = System.currentTimeMillis() // siempre creciente

        fun normalizarBaseUrl(url: String): String {
            val limpia = url.trim().ifBlank { DEFAULT_BOARD_URL }
            return if (limpia.endsWith("/")) limpia else "$limpia/"
        }

        fun firmar(msg: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(clave, "HmacSHA256"))
            return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        // Función principal solicitada en Paso 3 de la Guía Unidad 3
        suspend fun enviarComando(base: String, accion: String): String =
            withContext(Dispatchers.IO) {
                val baseNorm = normalizarBaseUrl(base)
                contador++
                val n = contador
                val sig = firmar("CMD;$accion;$n")
                val url = URL("${baseNorm}cmd?accion=$accion&n=$n&sig=$sig")
                val con = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                }
                try {
                    val code = con.responseCode
                    val cuerpo = (if (code < 400) con.inputStream else con.errorStream)
                        ?.bufferedReader()?.readText()?.trim().orEmpty()
                    Log.i(TAG_HZB, "cmd $accion -> $code $cuerpo") // Hertzbench lee el tag HZB
                    "$code $cuerpo"
                } finally {
                    con.disconnect()
                }
            }

        // Envía un comando alterando deliberadamente la firma HMAC -> el ESP32 responde 401
        suspend fun enviarComandoFirmaAlterada(base: String, accion: String): String =
            withContext(Dispatchers.IO) {
                val baseNorm = normalizarBaseUrl(base)
                contador++
                val n = contador
                val sigReal = firmar("CMD;$accion;$n")
                val prefijoAdulterado = if (sigReal.startsWith("0000")) "ffff" else "0000"
                val sigAdulterada = prefijoAdulterado + sigReal.drop(4)
                val url = URL("${baseNorm}cmd?accion=$accion&n=$n&sig=$sigAdulterada")
                val con = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                }
                try {
                    val code = con.responseCode
                    val cuerpo = (if (code < 400) con.inputStream else con.errorStream)
                        ?.bufferedReader()?.readText()?.trim().orEmpty()
                    Log.i(TAG_HZB, "cmd $accion -> $code $cuerpo")
                    "$code $cuerpo"
                } finally {
                    con.disconnect()
                }
            }

        // Envía un comando reutilizando el mismo contador anterior -> el ESP32 responde 409
        suspend fun enviarComandoRepetido(base: String, accion: String): String =
            withContext(Dispatchers.IO) {
                val baseNorm = normalizarBaseUrl(base)
                val nRepetido = contador // no se incrementa
                val sig = firmar("CMD;$accion;$nRepetido")
                val url = URL("${baseNorm}cmd?accion=$accion&n=$nRepetido&sig=$sig")
                val con = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                }
                try {
                    val code = con.responseCode
                    val cuerpo = (if (code < 400) con.inputStream else con.errorStream)
                        ?.bufferedReader()?.readText()?.trim().orEmpty()
                    Log.i(TAG_HZB, "cmd $accion -> $code $cuerpo")
                    "$code $cuerpo"
                } finally {
                    con.disconnect()
                }
            }
    }
}
