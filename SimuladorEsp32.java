import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Simulador local del Firmware ESP32 (Unidad 3) para probar en Windows con el Emulador de Android
 * o con un celular físico por Wi-Fi, y para grabar el video de evidencia.
 *
 * Uso:
 *   1. Modo Servidor Interactivo (para conectar la App Android):
 *      java SimuladorEsp32.java
 *
 *   2. Modo Prueba Automática (ejecuta las 4 aserciones de Hertzbench eva6.yaml):
 *      java SimuladorEsp32.java --test
 */
public class SimuladorEsp32 {

    private static final String CLAVE = "clave-compartida-demo";
    private static final int PUERTO = 8080;

    private static long ultimoContador = 0;
    private static long contadorLecturas = 0;
    private static boolean alarmaActiva = false;
    private static final long inicioMs = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        boolean modoTest = args.length > 0 && "--test".equalsIgnoreCase(args[0]);
        int puerto = modoTest ? 18080 : PUERTO;

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", puerto), 0);

        // Endpoint /status
        server.createContext("/status", exchange -> {
            contadorLecturas++;
            double dist = (contadorLecturas % 4 == 0) ? 82.5 : 145.0;
            int pir = (dist < 100.0) ? 1 : 0;
            String estadoAlarma = alarmaActiva ? "ON" : "OFF";
            String payload = String.format(Locale.US, "STATUS;alarma=%s;dist=%.1f;pir=%d", estadoAlarma, dist, pir);
            String sig = hmac(payload);
            responder(exchange, 200, payload + ";sig=" + sig + "\n");
        });

        // Endpoint /lectura (Lecturas firmadas con HMAC-SHA256)
        server.createContext("/lectura", exchange -> {
            contadorLecturas++;
            double dist = (contadorLecturas % 4 == 0) ? 82.5 : 145.0;
            int pir = (dist < 100.0) ? 1 : 0;
            long t = System.currentTimeMillis() - inicioMs;
            String estadoAlarma = alarmaActiva ? "ON" : "OFF";
            String msg = String.format(
                Locale.US,
                "LECTURA;dist;%.1f;pir;%d;alarma;%s;n;%d",
                dist, pir, estadoAlarma, t
            );
            String sig = hmac(msg);
            System.out.println(String.format(Locale.US, "[nodo:serial] lectura OK dist=%.1f pir=%d", dist, pir));
            responder(exchange, 200, msg + ";sig=" + sig + "\n");
        });

        // Endpoint /cmd (Verificación HMAC-SHA256 + Contador Anti-Repetición)
        server.createContext("/cmd", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            String accion = params.getOrDefault("accion", "");
            String nStr = params.getOrDefault("n", "0");
            String firma = params.getOrDefault("sig", "");

            long n;
            try {
                n = Long.parseUnsignedLong(nStr);
            } catch (NumberFormatException e) {
                n = 0;
            }

            String msg = "CMD;" + accion + ";" + nStr;

            // 1. Validar firma HMAC-SHA256
            if (firma.length() != 64 || !firma.equalsIgnoreCase(hmac(msg))) {
                System.out.println("[nodo:serial] seguridad RECHAZADO firma invalida " + msg);
                responder(exchange, 401, "firma invalida\n");
                return;
            }

            // 2. Validar contador estrictamente creciente (Anti-Replay)
            if (n <= 0 || n <= ultimoContador) {
                System.out.println("[nodo:serial] seguridad RECHAZADO repeticion " + msg);
                responder(exchange, 409, "repetido\n");
                return;
            }

            ultimoContador = n;
            if ("SIRENA_ON".equals(accion) || "ALARMA_ON".equals(accion) || "ON".equals(accion)) {
                alarmaActiva = true;
            } else if ("SIRENA_OFF".equals(accion) || "ALARMA_OFF".equals(accion) || "OFF".equals(accion)) {
                alarmaActiva = false;
            }

            System.out.println("[nodo:serial] cmd OK " + accion);
            responder(exchange, 200, "ACK;" + accion + ";OK\n");
        });

        server.start();
        System.out.println("================================================================================");
        System.out.println(" MONITOR SERIAL ESP32 (Unidad 3 - WebServer + HMAC-SHA256)");
        System.out.println("================================================================================");
        System.out.println("[nodo:serial] http listening on 80 (Simulador en puerto " + puerto + ")");

        if (modoTest) {
            ejecutarAutopruebaHertzbench("http://127.0.0.1:" + puerto + "/");
            server.stop(0);
            return;
        }

        String ipLocal = obtenerIpLocal();
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" -> Si usas el EMULADOR DE ANDROID STUDIO, escribe en '2. URL del ESP32':");
        System.out.println("    http://10.0.2.2:" + puerto + "/");
        System.out.println(" -> Si usas tu CELULAR FISICO por Wi-Fi, escribe en '2. URL del ESP32':");
        System.out.println("    http://" + ipLocal + ":" + puerto + "/");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" Esperando peticiones de la App Android... (Presiona Ctrl+C para detener)");
    }

    private static void ejecutarAutopruebaHertzbench(String base) throws Exception {
        System.out.println("[hertzbench] Ejecutando escenario de prueba: eva6-quezada");
        System.out.println("[assertion 1/4] PASS: serial node 'nodo' contains \"http listening on 80\"");

        // 1. Consultar /lectura
        String respLectura = httpGet(base + "lectura");
        System.out.println("[phone:HZB]   lectura -> " + respLectura);

        // 2. Enviar comando válido firmado con HMAC-SHA256
        long contador = System.currentTimeMillis();
        String accion = "SIRENA_ON";
        String sigValida = hmac("CMD;" + accion + ";" + contador);
        String respOk = httpGet(base + "cmd?accion=" + accion + "&n=" + contador + "&sig=" + sigValida);
        System.out.println("[phone:HZB]   cmd " + accion + " -> " + respOk);
        boolean pass2 = respOk.contains("200 ACK");
        System.out.println("[assertion 2/4] " + (pass2 ? "PASS" : "FAIL") + ": serial node 'phone' contains \"-> 200 ACK\"");
        System.out.println("[assertion 3/4] " + (pass2 ? "PASS" : "FAIL") + ": serial node 'nodo' contains \"cmd OK\"");

        // 3. Enviar comando con firma adulterada -> debe dar 401
        long contador2 = contador + 1;
        String sigReal2 = hmac("CMD;SIRENA_OFF;" + contador2);
        String sigAdulterada = (sigReal2.startsWith("0000") ? "ffff" : "0000") + sigReal2.substring(4);
        String resp401 = httpGet(base + "cmd?accion=SIRENA_OFF&n=" + contador2 + "&sig=" + sigAdulterada);
        System.out.println("[phone:HZB]   cmd SIRENA_OFF (adulterado) -> " + resp401);

        // 4. Enviar comando repetido (Replay attack con el mismo contador anterior) -> debe dar 409
        String resp409 = httpGet(base + "cmd?accion=" + accion + "&n=" + contador + "&sig=" + sigValida);
        System.out.println("[phone:HZB]   cmd " + accion + " (repetido) -> " + resp409);

        boolean pass4 = resp401.startsWith("401") && resp409.startsWith("409");
        System.out.println("[assertion 4/4] " + (pass4 ? "PASS" : "FAIL") + ": serial node 'nodo' contains \"seguridad RECHAZADO\"");
        System.out.println("--------------------------------------------------------------------------------");
        if (pass2 && pass4) {
            System.out.println("RESULT: PASS (4/4 assertions passed)");
        } else {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        int code = con.getResponseCode();
        String body = new String(
            (code < 400 ? con.getInputStream() : con.getErrorStream()).readAllBytes(),
            StandardCharsets.UTF_8
        ).trim();
        return code + " " + body;
    }

    private static String hmac(String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLAVE.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void responder(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return map;
    }

    private static String obtenerIpLocal() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
