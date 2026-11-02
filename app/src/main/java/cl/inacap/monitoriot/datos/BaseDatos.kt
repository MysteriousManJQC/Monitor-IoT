package cl.inacap.monitoriot.datos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cl.inacap.monitoriot.seguridad.Cripto

data class Lectura(val momento: Long, val clave: String, val valor: Double)

class BaseDatos(ctx: Context) : SQLiteOpenHelper(ctx, "monitoriot.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE usuarios (" +
                "usuario TEXT PRIMARY KEY, " +
                "sal BLOB NOT NULL, " +
                "hash BLOB NOT NULL, " +
                "rol TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE lecturas (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "momento INTEGER NOT NULL, " +
                "clave TEXT NOT NULL, " +
                "valor REAL NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, anterior: Int, nueva: Int) {}

    // El primer usuario queda como "operador"; los siguientes, como "observador".
    // Devuelve el rol asignado, o null si el usuario ya existe.
    fun registrar(usuario: String, clave: CharArray): String? {
        val hayUsuarios = readableDatabase.rawQuery("SELECT 1 FROM usuarios LIMIT 1", null).use {
            it.moveToFirst()
        }
        val rol = if (hayUsuarios) "observador" else "operador"
        val sal = Cripto.nuevaSal()
        val fila = ContentValues().apply {
            put("usuario", usuario)
            put("sal", sal)
            put("hash", Cripto.hash(clave, sal))
            put("rol", rol)
        }
        return if (writableDatabase.insert("usuarios", null, fila) == -1L) null else rol
    }

    // Devuelve el rol si las credenciales son válidas; null si no.
    fun autenticar(usuario: String, clave: CharArray): String? =
        readableDatabase.rawQuery(
            "SELECT sal, hash, rol FROM usuarios WHERE usuario = ?",
            arrayOf(usuario)
        ).use { c ->
            if (c.moveToFirst() && Cripto.verificar(clave, c.getBlob(0), c.getBlob(1)))
                c.getString(2)
            else null
        }

    fun guardarLectura(clave: String, valor: Double) {
        val fila = ContentValues().apply {
            put("momento", System.currentTimeMillis())
            put("clave", clave)
            put("valor", valor)
        }
        writableDatabase.insert("lecturas", null, fila)
    }

    fun lecturas(limite: Int = 50): List<Lectura> =
        readableDatabase.rawQuery(
            "SELECT momento, clave, valor FROM lecturas ORDER BY momento DESC LIMIT ?",
            arrayOf(limite.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Lectura(c.getLong(0), c.getString(1), c.getDouble(2)))
                }
            }
        }
}
