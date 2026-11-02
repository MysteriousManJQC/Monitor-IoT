package cl.inacap.monitoriot.seguridad

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Cripto {
    private const val ITERACIONES = 120_000

    fun nuevaSal(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    // PBKDF2: la contraseña nunca se guarda, solo este resumen con sal.
    fun hash(clave: CharArray, sal: ByteArray): ByteArray {
        val spec = PBEKeySpec(clave, sal, ITERACIONES, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verificar(clave: CharArray, sal: ByteArray, esperado: ByteArray): Boolean =
        MessageDigest.isEqual(hash(clave, sal), esperado)

    // Clave AES-256 derivada del código de vinculación que se escribe en ambos teléfonos.
    fun claveDesdeCodigo(codigo: String): SecretKey =
        SecretKeySpec(hash(codigo.toCharArray(), "MonitorIoT-v1".toByteArray()), "AES")

    // AES-GCM cifra y además detecta mensajes alterados. Salida: Base64(iv + datos).
    fun cifrar(texto: String, k: SecretKey): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        return Base64.encodeToString(c.iv + c.doFinal(texto.toByteArray()), Base64.NO_WRAP)
    }

    // Devuelve null si el mensaje no se puede descifrar (código distinto o datos alterados).
    fun descifrar(linea: String, k: SecretKey): String? = try {
        val datos = Base64.decode(linea, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, datos, 0, 12))
        String(c.doFinal(datos, 12, datos.size - 12))
    } catch (e: Exception) {
        null
    }
}
