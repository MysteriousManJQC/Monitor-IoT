package cl.inacap.monitoriot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cl.inacap.monitoriot.datos.BaseDatos
import cl.inacap.monitoriot.ui.PantallaConexion
import cl.inacap.monitoriot.ui.PantallaHistorial
import cl.inacap.monitoriot.ui.PantallaLogin
import cl.inacap.monitoriot.ui.PantallaNodo
import cl.inacap.monitoriot.ui.PantallaPanel
import cl.inacap.monitoriot.ui.theme.MonitorIoTTheme

class MainActivity : ComponentActivity() {

    private val pedirPermisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notificaciones.crearCanal(this)
        pedirPermisos.launch(permisosNecesarios())
        val db = BaseDatos(this)

        setContent {
            MonitorIoTTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { relleno ->
                    var pantalla by rememberSaveable {
                        mutableStateOf(if (Sesion.usuario == null) "login" else "conexion")
                    }
                    val m = Modifier.padding(relleno)
                    when (pantalla) {
                        "login" -> PantallaLogin(db, m) { pantalla = "conexion" }
                        "conexion" -> PantallaConexion(
                            db = db,
                            modifier = m,
                            onListo = { rol -> pantalla = rol },
                            onSalir = { pantalla = "login" }
                        )
                        "nodo" -> PantallaNodo(m) { pantalla = "conexion" }
                        "panel" -> PantallaPanel(
                            modifier = m,
                            onHistorial = { pantalla = "historial" },
                            onSalir = { pantalla = "conexion" }
                        )
                        "historial" -> PantallaHistorial(db, m) { pantalla = "panel" }
                    }
                }
            }
        }
    }

    // Solo se piden los permisos que existen en la versión de Android del teléfono.
    private fun permisosNecesarios(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
