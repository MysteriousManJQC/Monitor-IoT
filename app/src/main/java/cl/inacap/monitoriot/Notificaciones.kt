package cl.inacap.monitoriot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notificaciones {
    private const val CANAL = "alertas"

    fun crearCanal(ctx: Context) {
        val canal = NotificationChannel(
            CANAL,
            "Alertas IoT Perimetral",
            NotificationManager.IMPORTANCE_HIGH
        )
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    fun mostrar(ctx: Context, titulo: String, texto: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n)
    }
}
