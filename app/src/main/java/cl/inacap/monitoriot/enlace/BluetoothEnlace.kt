package cl.inacap.monitoriot.enlace

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.launch

// Bluetooth clásico (RFCOMM) entre dos teléfonos ya emparejados en Ajustes.
// El permiso BLUETOOTH_CONNECT se pide en MainActivity antes de llegar aquí.
@SuppressLint("MissingPermission")
class BluetoothEnlace(private val adaptador: BluetoothAdapter, clave: SecretKey) :
    EnlaceBase(clave) {
    private var servidor: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null

    // Rol nodo. "listenUsingRfcomm…" (sin "Insecure") exige emparejamiento y cifra el enlace.
    fun esperar() = alcance.launch {
        try {
            _estado.value = "Esperando conexión Bluetooth…"
            val srv = adaptador.listenUsingRfcommWithServiceRecord("MonitorIoT", UUID_APP).also {
                servidor = it
            }
            val s = srv.accept().also { socket = it }
            srv.close()
            atender(s.inputStream, s.outputStream)
        } catch (e: IOException) {
            _estado.value = "Error: ${e.message}"
        }
    }

    // Rol panel.
    fun conectar(equipo: BluetoothDevice) = alcance.launch {
        try {
            _estado.value = "Conectando a ${equipo.name}…"
            val s = equipo.createRfcommSocketToServiceRecord(UUID_APP).also { socket = it }
            s.connect()
            atender(s.inputStream, s.outputStream)
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
        // Mismo UUID en ambos teléfonos: identifica el servicio de esta app.
        val UUID_APP: UUID = UUID.fromString("7f3c2a10-5b8e-4d21-9c6f-2e8a4b1d9f03")
    }
}
