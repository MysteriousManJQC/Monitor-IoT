package cl.inacap.monitoriot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cl.inacap.monitoriot.Sesion
import cl.inacap.monitoriot.datos.BaseDatos
import cl.inacap.monitoriot.enlace.BluetoothEnlace
import cl.inacap.monitoriot.enlace.Enlace
import cl.inacap.monitoriot.enlace.EnlaceHttp
import cl.inacap.monitoriot.enlace.WifiEnlace
import cl.inacap.monitoriot.seguridad.Cripto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantallaConexion(
    db: BaseDatos,
    modifier: Modifier,
    onListo: (String) -> Unit,
    onSalir: () -> Unit
) {
    val ctx = LocalContext.current
    val adaptador = remember {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter
    }
    var esNodo by rememberSaveable { mutableStateOf(false) }
    // Medio: "http_esp32" (Unidad 3 por defecto), "bt" (Bluetooth Unidad 2), "wifi_tcp" (Wi-Fi TCP Unidad 2)
    var medio by rememberSaveable { mutableStateOf("http_esp32") }
    var boardUrl by rememberSaveable { mutableStateOf(Sesion.boardUrl) }
    var codigo by rememberSaveable { mutableStateOf("123456") }
    var ip by rememberSaveable { mutableStateOf("") }
    var elegido by remember { mutableStateOf<BluetoothDevice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val alcance = rememberCoroutineScope()

    val usarBt = medio == "bt"
    val usarHttpEsp32 = medio == "http_esp32"

    fun iniciar() {
        error = when {
            usarHttpEsp32 && boardUrl.isBlank() -> "Escribe la URL HTTP del ESP32 (ej. http://192.168.4.11/)."
            !usarHttpEsp32 && codigo.length != 6 -> "El código de vinculación debe tener 6 dígitos."
            usarBt && adaptador == null -> "Este teléfono no tiene Bluetooth."
            usarBt && !tienePermisoBt(ctx) -> "Falta el permiso de Bluetooth (Ajustes → Apps → MonitorIoT)."
            usarBt && adaptador?.isEnabled != true -> "Enciende el Bluetooth."
            usarBt && !esNodo && elegido == null -> "Elige el teléfono nodo."
            medio == "wifi_tcp" && !esNodo && ip.isBlank() -> "Escribe la IP del nodo."
            else -> null
        }
        if (error != null) return
        alcance.launch {
            if (usarHttpEsp32) {
                Sesion.boardUrl = EnlaceHttp.normalizarBaseUrl(boardUrl)
                val enlace = EnlaceHttp(Sesion.boardUrl).also { it.iniciarMonitoreo() }
                Sesion.iniciar(ctx, db, enlace, esNodo = false)
                onListo("panel")
            } else {
                val clave = withContext(Dispatchers.Default) { Cripto.claveDesdeCodigo(codigo) }
                val enlace: Enlace = if (usarBt) {
                    BluetoothEnlace(adaptador!!, clave).also {
                        if (esNodo) it.esperar() else it.conectar(elegido!!)
                    }
                } else {
                    WifiEnlace(clave).also {
                        if (esNodo) it.esperar() else it.conectar(ip)
                    }
                }
                Sesion.iniciar(ctx, db, enlace, esNodo)
                onListo(if (esNodo) "nodo" else "panel")
            }
        }
    }

    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Conexión Inalámbrica",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Usuario: ${Sesion.usuario ?: "—"} (${Sesion.rol})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Text("1. Medio inalámbrico:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = usarHttpEsp32,
                onClick = { medio = "http_esp32"; esNodo = false; error = null },
                label = { Text("HTTP ESP32 (Wi-Fi HMAC · U3)") }
            )
            FilterChip(
                selected = usarBt,
                onClick = { medio = "bt"; error = null },
                label = { Text("Bluetooth Clásico (U2)") }
            )
            FilterChip(
                selected = medio == "wifi_tcp",
                onClick = { medio = "wifi_tcp"; error = null },
                label = { Text("Wi-Fi TCP (U2)") }
            )
        }

        if (usarHttpEsp32) {
            Text(
                "Unidad 3 (Hertzbench): El teléfono actúa como Panel de Control y se comunica por Wi-Fi HTTP con el WebServer (:80) del ESP32 firmando cada comando con HMAC-SHA256 y contador anti-repetición.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = boardUrl,
                onValueChange = { boardUrl = it.trim() },
                label = { Text("2. URL del ESP32 (board_url)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("2. Rol de este teléfono:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = esNodo,
                    onClick = { esNodo = true; error = null },
                    label = { Text("Nodo IoT (Perímetro)") }
                )
                FilterChip(
                    selected = !esNodo,
                    onClick = { esNodo = false; error = null },
                    label = { Text("Panel de Control") }
                )
            }

            OutlinedTextField(
                value = codigo,
                onValueChange = { if (it.length <= 6) codigo = it.filter { c -> c.isDigit() } },
                label = { Text("3. Código de vinculación AES-256 (6 dígitos)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )

            when {
                usarBt && esNodo -> {
                    Text(
                        "4. Este equipo esperará conexión Bluetooth RFCOMM cifrada.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                usarBt && !esNodo -> {
                    Text("4. Elige el nodo (emparéjalo antes en Ajustes → Bluetooth):")
                    val emparejados = equiposEmparejados(ctx, adaptador)
                    if (emparejados.isEmpty()) {
                        Text(
                            "No hay equipos emparejados o falta permiso de dispositivos cercanos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            emparejados.forEach { d ->
                                FilterChip(
                                    selected = elegido?.address == d.address,
                                    onClick = { elegido = d },
                                    label = { Text(nombre(d)) }
                                )
                            }
                        }
                    }
                }
                !usarBt && esNodo -> {
                    Text(
                        "4. IP de este teléfono: ${WifiEnlace.ipLocal() ?: "sin red"}. Escríbela en el panel (Puerto ${WifiEnlace.PUERTO})."
                    )
                }
                !usarBt && !esNodo -> {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it.trim() },
                        label = { Text("4. IP del nodo (ej. 192.168.43.1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { iniciar() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    usarHttpEsp32 -> "Conectar al ESP32 (HTTP + HMAC)"
                    esNodo -> "Iniciar como Nodo Perimetral"
                    else -> "Conectar desde Panel"
                }
            )
        }

        OutlinedButton(
            onClick = {
                Sesion.cerrar()
                Sesion.usuario = null
                Sesion.rol = "observador"
                onSalir()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cerrar sesión")
        }
    }
}

private fun tienePermisoBt(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun equiposEmparejados(ctx: Context, adaptador: BluetoothAdapter?): List<BluetoothDevice> =
    if (adaptador != null && tienePermisoBt(ctx)) {
        adaptador.bondedDevices?.toList().orEmpty()
    } else {
        emptyList()
    }

@SuppressLint("MissingPermission")
private fun nombre(d: BluetoothDevice): String = d.name ?: d.address
