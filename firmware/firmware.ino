/*
 * APLICACIONES MÓVILES PARA IOT · INACAP · UNIDAD 3 (Evaluación 5 y 6)
 * Proyecto: Sistema de Seguridad Perimetral Inalámbrico (Adaptación Unidad 1 -> Unidad 3)
 * Estudiante: Josué Quezada
 * Docente: Juan Pablo Díaz
 *
 * Descripción:
 * Firmware para ESP32 (real o emulado en QEMU/Hertzbench) que integra:
 * 1. Sensores y actuadores de la Unidad 1:
 *    - Sensor PIR (GPIO 13) y Sensor Ultrasónico HC-SR04 (TRIG 12, ECHO 14)
 *    - Relé Foco Disuasivo (GPIO 19), Sirena Central (GPIO 18), Botón Desarme (GPIO 4)
 *    - Indicadores LED_OK (GPIO 16) y LED_ALERTA (GPIO 17)
 * 2. Servidor HTTP (WebServer en puerto 80) sobre Wi-Fi ("Espressif", canal 5)
 * 3. Verificación criptográfica HMAC-SHA256 (mbedTLS) con clave compartida y
 *    contador estrictamente creciente (anti-repetición / anti-replay).
 * 4. Registro de auditoría en Monitor Serial (sin exponer nunca la clave secreta).
 */

#include <WiFi.h>
#include <WebServer.h>
#include "mbedtls/md.h"

// Pines de Sensores (Unidad 1)
#define PIR_PIN 13
#define TRIG_PIN 12
#define ECHO_PIN 14
#define BUZZER_LOCAL 27
#define LED_TX 2

// Pines de Actuadores Centrales (Unidad 1)
#define RELAY_PIN 19
#define BUZZER_CENTRAL 18
#define BUTTON_PIN 4
#define LED_OK_PIN 16
#define LED_ALERTA_PIN 17

// Clave compartida para HMAC-SHA256 (en producción se aprovisiona en NVS/efuse, nunca en código fuente)
const char *CLAVE = "clave-compartida-demo";

WebServer server(80);

// Contador anti-repetición (soporta contadores de 64 bits como System.currentTimeMillis())
unsigned long long ultimoContador = 0;
unsigned long contadorLecturas = 0;

// Estado de los actuadores y sensores de la Unidad 1
bool alarmaActiva = false;
float ultimaDistanciaCm = 145.0;
bool ultimoPir = false;

// Calcula HMAC-SHA256 en hexadecimal minúscula usando mbedTLS por hardware/software del ESP32
String hmac(const String &msg) {
  byte out[32];
  mbedtls_md_context_t c;
  mbedtls_md_init(&c);
  mbedtls_md_setup(&c, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  mbedtls_md_hmac_starts(&c, (const byte *)CLAVE, strlen(CLAVE));
  mbedtls_md_hmac_update(&c, (const byte *)msg.c_str(), msg.length());
  mbedtls_md_hmac_finish(&c, out);
  mbedtls_md_free(&c);

  String hex;
  hex.reserve(64);
  for (int i = 0; i < 32; i++) {
    char b[3];
    sprintf(b, "%02x", out[i]);
    hex += b;
  }
  return hex;
}

// Actualiza el estado físico de los actuadores de la Unidad 1 (Relé, Sirena y LEDs)
void aplicarEstadoActuadores(bool activar) {
  alarmaActiva = activar;
  if (alarmaActiva) {
    digitalWrite(RELAY_PIN, HIGH);
    digitalWrite(BUZZER_CENTRAL, HIGH);
    digitalWrite(LED_OK_PIN, LOW);
    digitalWrite(LED_ALERTA_PIN, HIGH);
  } else {
    digitalWrite(RELAY_PIN, LOW);
    digitalWrite(BUZZER_CENTRAL, LOW);
    digitalWrite(LED_OK_PIN, HIGH);
    digitalWrite(LED_ALERTA_PIN, LOW);
  }
}

// Lee los sensores de la Unidad 1; si corre en QEMU sin eco físico, genera una lectura simulada estable
void actualizarSensores() {
  bool pirFisico = digitalRead(PIR_PIN) == HIGH;

  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  // Timeout corto (5 ms) para no bloquear el WebServer ni el watchdog en QEMU
  long duration = pulseIn(ECHO_PIN, HIGH, 5000);
  if (duration > 0) {
    ultimaDistanciaCm = duration * 0.034f / 2.0f;
    ultimoPir = pirFisico;
  } else {
    // En QEMU/Hertzbench simulamos una oscilación realista del perímetro (entre 75 cm y 160 cm)
    contadorLecturas++;
    ultimaDistanciaCm = (contadorLecturas % 4 == 0) ? 82.5f : 145.0f;
    ultimoPir = (ultimaDistanciaCm < 100.0f);
  }
}

// Endpoint /status (Evaluación 5 Formativa: verificación de estado)
void handleStatus() {
  actualizarSensores();
  String estadoAlarma = alarmaActiva ? "ON" : "OFF";
  String payload = "STATUS;alarma=" + estadoAlarma +
                   ";dist=" + String(ultimaDistanciaCm, 1) +
                   ";pir=" + String(ultimoPir ? 1 : 0);
  String sig = hmac(payload);
  server.send(200, "text/plain", payload + ";sig=" + sig + "\n");
}

// Endpoint /lectura (Evaluación 5 Formativa y Evaluación 6 Sumativa: lecturas firmadas con HMAC)
void handleLectura() {
  actualizarSensores();
  unsigned long t = millis();
  String estadoAlarma = alarmaActiva ? "ON" : "OFF";
  // Formato: LECTURA;dist;<cm>;pir;<0|1>;alarma;<ON|OFF>;n=<t>
  String msg = "LECTURA;dist;" + String(ultimaDistanciaCm, 1) +
               ";pir;" + String(ultimoPir ? 1 : 0) +
               ";alarma;" + estadoAlarma +
               ";n;" + String(t);
  String sig = hmac(msg);
  Serial.println("lectura OK dist=" + String(ultimaDistanciaCm, 1) + " pir=" + String(ultimoPir ? 1 : 0));
  server.send(200, "text/plain", msg + ";sig=" + sig + "\n");
}

// Endpoint /cmd (Evaluación 6 Sumativa: control de actuadores con verificación HMAC-SHA256 y anti-repetición)
void handleCmd() {
  String accion = server.arg("accion"); // p. ej. SIRENA_ON, SIRENA_OFF, ALARMA_ON, ALARMA_OFF
  String nStr = server.arg("n");        // contador monótono del emisor
  String firma = server.arg("sig");     // firma HMAC-SHA256 en hex

  unsigned long long n = strtoull(nStr.c_str(), NULL, 10);
  String msg = "CMD;" + accion + ";" + nStr;

  // 1. Verificar autenticidad e integridad mediante HMAC-SHA256 (Criterio 3.1.1 y 3.1.2)
  if (firma.length() != 64 || !firma.equalsIgnoreCase(hmac(msg))) {
    Serial.println("seguridad RECHAZADO firma invalida " + msg);
    server.send(401, "text/plain", "firma invalida\n");
    return;
  }

  // 2. Verificar frescura del mensaje mediante contador creciente anti-replay (Criterio 3.1.1 y 3.1.4)
  if (n == 0 || n <= ultimoContador) {
    Serial.println("seguridad RECHAZADO repeticion " + msg);
    server.send(409, "text/plain", "repetido\n");
    return;
  }

  ultimoContador = n;

  // 3. Accionar actuadores del Sistema de Seguridad Perimetral (Criterio 3.1.3)
  if (accion == "SIRENA_ON" || accion == "ALARMA_ON" || accion == "ON") {
    aplicarEstadoActuadores(true);
  } else if (accion == "SIRENA_OFF" || accion == "ALARMA_OFF" || accion == "OFF") {
    aplicarEstadoActuadores(false);
  }

  Serial.println("cmd OK " + accion);
  server.send(200, "text/plain", "ACK;" + accion + ";OK\n");
}

void setup() {
  Serial.begin(115200);

  // Configurar pines de la Unidad 1
  pinMode(PIR_PIN, INPUT);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  pinMode(BUZZER_LOCAL, OUTPUT);
  pinMode(LED_TX, OUTPUT);

  pinMode(RELAY_PIN, OUTPUT);
  pinMode(BUZZER_CENTRAL, OUTPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_OK_PIN, OUTPUT);
  pinMode(LED_ALERTA_PIN, OUTPUT);

  // Estado inicial seguro (Relé y Sirena apagados, LED_OK encendido)
  aplicarEstadoActuadores(false);

  // Conexión Wi-Fi en el SSID y canal simulado por Hertzbench
  WiFi.begin("Espressif", "", 5);
  while (WiFi.status() != WL_CONNECTED) {
    delay(100);
  }

  // Registrar endpoints HTTP
  server.on("/status", handleStatus);
  server.on("/lectura", handleLectura);
  server.on("/cmd", handleCmd);
  server.begin();

  Serial.println("http listening on 80");
}

void loop() {
  server.handleClient();

  // Permite desarme físico local con el pulsador en GPIO 4 (Unidad 1)
  if (digitalRead(BUTTON_PIN) == LOW && alarmaActiva) {
    aplicarEstadoActuadores(false);
    Serial.println("cmd OK DESARME_LOCAL_GPIO4");
    delay(200);
  }
}
