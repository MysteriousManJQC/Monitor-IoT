package cl.inacap.monitoriot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.inacap.monitoriot.Sesion
import cl.inacap.monitoriot.enlace.Mensaje
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Este teléfono hace de dispositivo IoT perimetral (Unidad 1):
// - Sensor ultrasónico HC-SR04 (distancia en cm simulada con deslizador 10..200 cm)
// - Sensor infrarrojo PIR HC-SR501 (detección de movimiento)
// - Actuador: Relé Foco Disuasivo + Sirena Central que se controla desde el panel.
@Composable
fun PantallaNodo(modifier: Modifier, onSalir: () -> Unit) {
    val enlace = Sesion.enlace ?: run {
        LaunchedEffect(Unit) { onSalir() }
        return
    }
    val estado by enlace.estado.collectAsState()
    val alarmaActiva by Sesion.alarmaActiva.collectAsState()
    var distancia by remember { mutableFloatStateOf(150f) }
    var pirActivo by remember { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()

    // Envía una lectura cada 2 segundos mientras esta pantalla esté abierta.
    LaunchedEffect(enlace) {
        while (true) {
            enlace.enviar(
                Mensaje("LECTURA", "dist", String.format(Locale.US, "%.1f", distancia))
            )
            enlace.enviar(
                Mensaje("LECTURA", "pir", if (pirActivo) "1" else "0")
            )
            delay(2_000)
        }
    }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Nodo IoT Perimetral (Simulado)",
            style = MaterialTheme.typography.headlineSmall
        )
        Text("Enlace: $estado")

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
                    "Sensor Ultrasónico HC-SR04: %.1f cm".format(distancia),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (distancia < Sesion.UMBRAL_DISTANCIA)
                        "¡INTRUSO EN PERÍMETRO (< ${Sesion.UMBRAL_DISTANCIA.toInt()} cm)!"
                    else
                        "Perímetro despejado (>= ${Sesion.UMBRAL_DISTANCIA.toInt()} cm)",
                    color = if (distancia < Sesion.UMBRAL_DISTANCIA)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = distancia,
                    onValueChange = { distancia = it },
                    valueRange = 10f..200f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sensor PIR (Movimiento IR):")
                    Switch(
                        checked = pirActivo,
                        onCheckedChange = { nuevo ->
                            pirActivo = nuevo
                            alcance.launch {
                                enlace.enviar(Mensaje("LECTURA", "pir", if (nuevo) "1" else "0"))
                            }
                        }
                    )
                }
            }
        }

        Text(
            if (alarmaActiva) "Sirena / Foco Relé: ENCENDIDO" else "Sirena / Foco Relé: APAGADO",
            style = MaterialTheme.typography.headlineSmall,
            color = if (alarmaActiva)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.outline
        )

        if (alarmaActiva) {
            Button(
                onClick = {
                    Sesion.alarmaActiva.value = false
                    alcance.launch { enlace.enviar(Mensaje("ACK", "alarma", "OFF")) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Botón físico de desarme local (GPIO 4)")
            }
        }

        Text(
            "Lineamiento OT (IEC 62443): Si el enlace se corta, el relé y la sirena vuelven a APAGADO (estado seguro).",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = { Sesion.cerrar(); onSalir() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Desconectar")
        }
    }
}
