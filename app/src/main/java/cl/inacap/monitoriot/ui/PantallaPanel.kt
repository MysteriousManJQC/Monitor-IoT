package cl.inacap.monitoriot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.inacap.monitoriot.Sesion
import cl.inacap.monitoriot.enlace.EnlaceHttp
import cl.inacap.monitoriot.enlace.Mensaje
import kotlinx.coroutines.launch

@Composable
fun PantallaPanel(modifier: Modifier, onHistorial: () -> Unit, onSalir: () -> Unit) {
    val enlace = Sesion.enlace ?: run {
        LaunchedEffect(Unit) { onSalir() }
        return
    }
    val estado by enlace.estado.collectAsState()
    val dist by Sesion.distancia.collectAsState()
    val pir by Sesion.pirDetectado.collectAsState()
    val alarmaActiva by Sesion.alarmaActiva.collectAsState()
    val alcance = rememberCoroutineScope()
    val puedeControlar = Sesion.rol == "operador" // control de acceso por rol (ISO 27000 / 27400)

    val enlaceHttp = enlace as? EnlaceHttp
    val ultimaRespuestaHttp by (enlaceHttp?.ultimaRespuestaHttp
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()

    val hayIntrusion = (dist != null && dist!! < Sesion.UMBRAL_DISTANCIA) || pir

    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Panel de Control Central",
            style = MaterialTheme.typography.headlineSmall
        )
        Text("Enlace: $estado · Rol: ${Sesion.rol}")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (hayIntrusion)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Distancia HC-SR04:",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    dist?.let { "%.1f cm".format(it) } ?: "— cm",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(
                    "Sensor PIR: " + if (pir) "MOVIMIENTO DETECTADO" else "Sin movimiento",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (hayIntrusion) {
                    Text(
                        "¡ALERTA DE INTRUSIÓN! Objeto bajo el umbral de ${Sesion.UMBRAL_DISTANCIA.toInt()} cm o PIR activo.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }

        Text(
            "Sirena y Foco Relé: " + if (alarmaActiva) "ENCENDIDO" else "APAGADO",
            style = MaterialTheme.typography.titleMedium
        )

        Button(
            enabled = puedeControlar && (estado == "Conectado" || enlaceHttp != null),
            onClick = {
                alcance.launch {
                    enlace.enviar(
                        Mensaje("CMD", "alarma", if (alarmaActiva) "OFF" else "ON")
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (alarmaActiva)
                    "Desactivar Sirena / Foco (HMAC válido)"
                else
                    "Activar Sirena / Foco Disuasivo (HMAC válido)"
            )
        }

        if (!puedeControlar) {
            Text(
                "Tu rol es observador: puedes monitorear el perímetro, pero no enviar comandos al actuador.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Sección de pruebas de seguridad HMAC-SHA256 y Contador para Unidad 3 (ESP32 HTTP)
        if (enlaceHttp != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Unidad 3 · Pruebas de Seguridad ESP32 (HMAC-SHA256)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    ultimaRespuestaHttp?.let { resp ->
                        Text(
                            "Última respuesta ESP32: $resp",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (resp.startsWith("200"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedButton(
                        enabled = puedeControlar,
                        onClick = {
                            alcance.launch {
                                enlaceHttp.probarComandoFirmaAlterada("SIRENA_ON")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Probar comando con firma alterada (Esperado: 401)")
                    }
                    OutlinedButton(
                        enabled = puedeControlar,
                        onClick = {
                            alcance.launch {
                                enlaceHttp.probarComandoRepetido("SIRENA_ON")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Probar comando repetido / Replay (Esperado: 409)")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onHistorial,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ver historial de lecturas (SQLite)")
        }

        OutlinedButton(
            onClick = { Sesion.cerrar(); onSalir() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Desconectar")
        }
    }
}
