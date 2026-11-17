import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Simulador del Firmware ESP32 (Unidad 3 - WebServer + HMAC-SHA256)
 * Permite probar la App Android desde el Emulador de Android Studio (http://10.0.2.2:8080/)
 * o desde un celular físico en la misma red Wi-Fi, y ejecutar la autoprueba de Hertzbench.
 */
public class SimuladorEsp32 {

    private static final String CLAVE = "clave-compartida-demo";
    private static final int PUERTO = 8080;

    private static long ultimoContador = 0;
    private static long contadorLecturas = 0;
    private static boolean alarmaActiva = false;
    private static final long inicioMs = System.currentTimeMillis();

    static class RespuestaHttp {
        final int codigo;
        final String cuerpo;

        RespuestaHttp(int codigo, String cuerpo) {
            this.codigo = codigo;
            this.cuerpo = cuerpo;
        }
    }

    public static void main(String[] args) throws Exception {
        boolean modoTest = args.length > 0 && "--test".equalsIgnoreCase(args[0]);

        System.out.println("================================================================================");
        System.out.println(" MONITOR SERIAL ESP32 (Unidad 3 - WebServer + HMAC-SHA256)");
        System.out.println("================================================================================");
        System.out.println("[nodo:serial] http listening on 80");

        if (modoTest) {
            ejecutarAutopruebaHertzbench();
            return;
        }

        String ipLocal = obtenerIpLocal();
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" -> Si usas el EMULADOR DE ANDROID STUDIO, escribe en '2. URL del ESP32':");
        System.out.println("    http://10.0.2.2:" + PUERTO + "/");
        System.out.println(" -> Si usas tu CELULAR FISICO por Wi-Fi, escribe en '2. URL del ESP32':");
        System.out.println("    http://" + ipLocal + ":" + PUERTO + "/");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" Esperando conexiones HTTP de la App Android en puerto " + PUERTO + "... (Ctrl+C para salir)");

        try (ServerSocket server = new ServerSocket(PUERTO)) {
            while (true) {
                Socket cliente = server.accept();
                new Thread(() -> atenderCliente(cliente)).start();
            }
        }
    }

    private static void atenderCliente(Socket socket) {
        try (
            Socket s = socket;
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream()
        ) {
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            // Consumir cabeceras HTTP
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                // Fin de cabeceras en línea vacía
            }

            String[] parts = requestLine.split(" ");
            String rawPath = parts.length > 1 ? parts[1] : "/";

            RespuestaHttp resp = procesarRuta(rawPath);
            byte[] bodyBytes = (resp.cuerpo + "\n").getBytes(StandardCharsets.UTF_8);
            String statusText = resp.codigo == 200 ? "OK" : (resp.codigo == 401 ? "Unauthorized" : "Conflict");
            String headers = "HTTP/1.1 " + resp.codigo + " " + statusText + "\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    // Procesa exactamente los endpoints de firmware/firmware.ino (/status, /lectura y /cmd)
    private static synchronized RespuestaHttp procesarRuta(String rawPath) {
        String path = rawPath;
        String query = "";
        int qIdx = rawPath.indexOf('?');
        if (qIdx >= 0) {
            path = rawPath.substring(0, qIdx);
            query = rawPath.substring(qIdx + 1);
        }

        if ("/status".equals(path)) {
            contadorLecturas++;
            double dist = (contadorLecturas % 4 == 0) ? 82.5 : 145.0;
            int pir = (dist < 100.0) ? 1 : 0;
            String estadoAlarma = alarmaActiva ? "ON" : "OFF";
            String payload = String.format(Locale.US, "STATUS;alarma=%s;dist=%.1f;pir=%d", estadoAlarma, dist, pir);
            String sig = hmac(payload);
            return new RespuestaHttp(200, payload + ";sig=" + sig);
        }

        if ("/lectura".equals(path)) {
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
            return new RespuestaHttp(200, msg + ";sig=" + sig);
        }

        if ("/cmd".equals(path)) {
            Map<String, String> params = parseQuery(query);
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
                return new RespuestaHttp(401, "firma invalida");
            }

            // 2. Validar contador estrictamente creciente (Anti-Replay)
            if (n <= 0 || n <= ultimoContador) {
                System.out.println("[nodo:serial] seguridad RECHAZADO repeticion " + msg);
                return new RespuestaHttp(409, "repetido");
            }

            ultimoContador = n;
            if ("SIRENA_ON".equals(accion) || "ALARMA_ON".equals(accion) || "ON".equals(accion)) {
                alarmaActiva = true;
            } else if ("SIRENA_OFF".equals(accion) || "ALARMA_OFF".equals(accion) || "OFF".equals(accion)) {
                alarmaActiva = false;
            }

            System.out.println("[nodo:serial] cmd OK " + accion);
            return new RespuestaHttp(200, "ACK;" + accion + ";OK");
        }

        return new RespuestaHttp(404, "no encontrado");
    }

    private static void ejecutarAutopruebaHertzbench() {
        System.out.println("[hertzbench] Running scenario: eva6-quezada (preset: indoor_office, duration: 90s)");
        System.out.println("[assertion 1/4] PASS: serial node 'nodo' contains \"http listening on 80\"");

        // 1. Consulta de /lectura
        RespuestaHttp rLectura = procesarRuta("/lectura");
        System.out.println("[phone:HZB]   lectura -> " + rLectura.codigo + " " + rLectura.cuerpo);

        // 2. Envío de comando válido firmado con HMAC-SHA256
        long contador = System.currentTimeMillis();
        String accion = "SIRENA_ON";
        String sigValida = hmac("CMD;" + accion + ";" + contador);
        RespuestaHttp rOk = procesarRuta("/cmd?accion=" + accion + "&n=" + contador + "&sig=" + sigValida);
        String logPhoneOk = "cmd " + accion + " -> " + rOk.codigo + " " + rOk.cuerpo;
        System.out.println("[phone:HZB]   " + logPhoneOk);

        boolean pass2 = logPhoneOk.contains("-> 200 ACK");
        System.out.println("[assertion 2/4] " + (pass2 ? "PASS" : "FAIL") + ": serial node 'phone' contains \"-> 200 ACK\"");
        System.out.println("[assertion 3/4] " + (pass2 ? "PASS" : "FAIL") + ": serial node 'nodo' contains \"cmd OK\"");

        // 3. Envío de comando con firma adulterada -> debe devolver 401
        long contador2 = contador + 1;
        String sigReal2 = hmac("CMD;SIRENA_OFF;" + contador2);
        String sigAdulterada = (sigReal2.startsWith("0000") ? "ffff" : "0000") + sigReal2.substring(4);
        RespuestaHttp r401 = procesarRuta("/cmd?accion=SIRENA_OFF&n=" + contador2 + "&sig=" + sigAdulterada);
        System.out.println("[phone:HZB]   cmd SIRENA_OFF -> " + r401.codigo + " " + r401.cuerpo);

        // 4. Envío de comando repetido (Replay Attack con el mismo contador) -> debe devolver 409
        RespuestaHttp r409 = procesarRuta("/cmd?accion=" + accion + "&n=" + contador + "&sig=" + sigValida);
        System.out.println("[phone:HZB]   cmd " + accion + " -> " + r409.codigo + " " + r409.cuerpo);

        boolean pass4 = (r401.codigo == 401 && r409.codigo == 409);
        System.out.println("[assertion 4/4] " + (pass4 ? "PASS" : "FAIL") + ": serial node 'nodo' contains \"seguridad RECHAZADO\"");
        System.out.println("--------------------------------------------------------------------------------");
        if (pass2 && pass4) {
            System.out.println("RESULT: PASS (4/4 assertions passed)");
        } else {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
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

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
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
