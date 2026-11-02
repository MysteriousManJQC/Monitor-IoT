package cl.inacap.monitoriot.enlace

import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey
import kotlinx.coroutines.launch

// Ambos teléfonos en la misma red Wi-Fi (o uno conectado al hotspot del otro).
class WifiEnlace(clave: SecretKey) : EnlaceBase(clave) {
    private var servidor: ServerSocket? = null
    private var socket: Socket? = null

    // Rol nodo: espera a que el panel se conecte.
    fun esperar() = alcance.launch {
        try {
            _estado.value = "Esperando en ${ipLocal()}:$PUERTO"
            val srv = ServerSocket(PUERTO).also { servidor = it }
            val s = srv.accept().also { socket = it }
            srv.close()
            atender(s.getInputStream(), s.getOutputStream())
        } catch (e: IOException) {
            _estado.value = "Error: ${e.message}"
        }
    }

    // Rol panel: se conecta a la IP del nodo.
    fun conectar(ip: String) = alcance.launch {
        try {
            _estado.value = "Conectando a $ip…"
            val s = Socket().also { socket = it }
            s.connect(InetSocketAddress(ip, PUERTO), 5_000)
            atender(s.getInputStream(), s.getOutputStream())
        } catch (e: IOException) {
            _estado.value = "Error: ${e.message}"
        }
    }

    override fun cerrar() {
        runCatching { socket?.close() }
        runCatching { servidor?.close() }
        terminar()
    }

    companion object {
        const val PUERTO = 5050

        fun ipLocal(): String? = NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }
}
