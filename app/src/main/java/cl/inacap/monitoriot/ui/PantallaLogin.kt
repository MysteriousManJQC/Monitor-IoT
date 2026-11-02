package cl.inacap.monitoriot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cl.inacap.monitoriot.Sesion
import cl.inacap.monitoriot.datos.BaseDatos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PantallaLogin(db: BaseDatos, modifier: Modifier, onIngreso: () -> Unit) {
    var usuario by rememberSaveable { mutableStateOf("") }
    var clave by remember { mutableStateOf("") } // no se guarda al rotar por seguridad
    var mensaje by remember { mutableStateOf<String?>(null) }
    var fallos by remember { mutableIntStateOf(0) }
    var ocupado by remember { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()

    fun ingresar(registrar: Boolean) {
        if (fallos >= 5) {
            mensaje = "Demasiados intentos. Espera 30 segundos."
            return
        }
        if (usuario.isBlank() || clave.length < 8) {
            mensaje = "Escribe un usuario y una contraseña de al menos 8 caracteres."
            return
        }
        ocupado = true
        alcance.launch {
            val nombre = usuario.trim()
            val rol = withContext(Dispatchers.Default) {
                if (registrar) db.registrar(nombre, clave.toCharArray())
                else db.autenticar(nombre, clave.toCharArray())
            }
            ocupado = false
            if (rol != null) {
                Sesion.usuario = nombre
                Sesion.rol = rol
                clave = ""
                onIngreso()
            } else if (registrar) {
                mensaje = "Ese usuario ya existe."
            } else {
                fallos++
                mensaje = "Usuario o contraseña incorrectos (intento $fallos de 5)."
                if (fallos >= 5) {
                    mensaje = "Demasiados intentos. Bloqueado por 30 segundos."
                    delay(30_000)
                    fallos = 0
                    mensaje = null
                }
            }
        }
    }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "MonitorIoT · Seguridad Perimetral",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Control y monitoreo inalámbrico (PIR + HC-SR04)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = usuario,
            onValueChange = { usuario = it },
            label = { Text("Usuario") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it },
            label = { Text("Contraseña (mínimo 8 caracteres)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(
            onClick = { ingresar(registrar = false) },
            enabled = !ocupado && fallos < 5,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ingresar")
        }
        OutlinedButton(
            onClick = { ingresar(registrar = true) },
            enabled = !ocupado && fallos < 5,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Crear cuenta")
        }
        mensaje?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "El primer usuario creado queda como operador (puede activar/desactivar alarma); los siguientes, como observadores (solo monitoreo).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
