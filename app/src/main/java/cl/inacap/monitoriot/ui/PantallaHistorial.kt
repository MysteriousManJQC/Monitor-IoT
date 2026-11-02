package cl.inacap.monitoriot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.inacap.monitoriot.datos.BaseDatos
import cl.inacap.monitoriot.datos.Lectura
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PantallaHistorial(db: BaseDatos, modifier: Modifier, onVolver: () -> Unit) {
    val lecturas by produceState(emptyList<Lectura>()) {
        value = withContext(Dispatchers.IO) { db.lecturas() }
    }
    val hora = remember { SimpleDateFormat("dd-MM HH:mm:ss", Locale.getDefault()) }

    Column(modifier = modifier.padding(24.dp)) {
        Text(
            "Historial (últimas 50 lecturas)",
            style = MaterialTheme.typography.headlineSmall
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(lecturas) { l ->
                val descripcion = if (l.clave == "pir") {
                    "PIR: Movimiento detectado"
                } else {
                    "Distancia: %.1f cm".format(l.valor)
                }
                Text(
                    "${hora.format(Date(l.momento))} · $descripcion",
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                HorizontalDivider()
            }
        }
        Button(
            onClick = onVolver,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Volver al panel")
        }
    }
}
