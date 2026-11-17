# MonitorIoT Perimetral — Unidades 2 y 3: App Android + ESP32 Seguro (Hertzbench)

- **Asignatura:** Aplicaciones Móviles para IoT (TI3042) · INACAP
- **Docente:** Juan Pablo Díaz
- **Estudiante:** Josué Quezada
- **Proyecto Integrado (Unidades 1, 2 y 3):** *Sistema de Seguridad Perimetral Inalámbrico (Sensores PIR HC-SR501 + Ultrasónico HC-SR04 y Actuadores Relé Foco Disuasivo + Sirena Central)*

---

## Parte I · Unidad 3 (Evaluación 5 Formativa y Evaluación 6 Sumativa)
**Integración de Smart Devices con aplicaciones externas — Comunicación segura App Android ↔ ESP32 sobre Wi-Fi en Hertzbench**

### 1. Arquitectura de la Integración (Unidad 3)

En esta unidad se conectan la aplicación Android de la Unidad 2 (`cl.inacap.monitoriot` corriendo en el emulador `pixel8`) y el firmware de la Unidad 1 adaptado ([`firmware/firmware.ino`](file:///c:/Users/josue/OneDrive/Escritorio/Proyectos/Iot/MonitorIoT/firmware/firmware.ino) corriendo sobre un ESP32 emulado en QEMU) a través de la red Wi-Fi simulada por **Hertzbench** (`SSID: "Espressif"`, canal `5`).

```mermaid
sequenceDiagram
    participant U as Usuario (Rol Operador)
    participant App as App Android (pixel8)<br/>EnlaceHttp.kt + SQLite
    participant ESP as ESP32 Firmware (QEMU)<br/>WebServer :80 + mbedTLS

    U->>App: Login PBKDF2-SHA256 (rol = "operador")
    App->>ESP: GET /lectura
    ESP-->>App: 200 LECTURA;dist;82.5;pir;1;alarma;OFF;n;...;sig=<HMAC>
    App->>App: Verifica HMAC-SHA256 y guarda en SQLite

    U->>App: Presiona "Activar Sirena (HMAC válido)"
    App->>App: contador++ ; sig = HMAC_SHA256("CMD;SIRENA_ON;" + contador)
    App->>ESP: GET /cmd?accion=SIRENA_ON&n=<contador>&sig=<sig>
    ESP->>ESP: Recalcula HMAC y verifica n > ultimoContador
    ESP->>ESP: Activa RELAY_PIN(19), BUZZER(18), LED_ALERTA(17)
    ESP-->>App: 200 ACK;SIRENA_ON;OK (Serial: "cmd OK SIRENA_ON")
    App->>App: Log.i("HZB", "cmd SIRENA_ON -> 200 ACK;SIRENA_ON;OK")

    U->>App: Presiona "Probar comando con firma alterada (401)"
    App->>ESP: GET /cmd?accion=SIRENA_OFF&n=<contador+1>&sig=0000...
    ESP-->>App: 401 firma invalida (Serial: "seguridad RECHAZADO firma invalida...")

    U->>App: Presiona "Probar comando repetido / Replay (409)"
    App->>ESP: GET /cmd?accion=SIRENA_ON&n=<contador_anterior>&sig=<sig_valida_anterior>
    ESP-->>App: 409 repetido (Serial: "seguridad RECHAZADO repeticion...")
```

---

### 2. Protocolo de Mensajes HTTP y Esquema de Firma + Contador

#### 2.1 Endpoints expuestos por el ESP32 (`WebServer` puerto `80`)

| Endpoint HTTP | Dirección | Formato de Petición / Respuesta | Propósito |
| :--- | :---: | :--- | :--- |
| `GET /status` | App → ESP32 | Respuesta `200`: `STATUS;alarma=OFF;dist=145.0;pir=0;sig=<hmac>` | Endpoint base de estado del nodo perimetral (Evaluación 5 Formativa). |
| `GET /lectura` | App → ESP32 | Respuesta `200`: `LECTURA;dist;82.5;pir;1;alarma;OFF;n;12400;sig=<hmac>` | Endpoint agregado para obtener las lecturas del sensor ultrasónico HC-SR04 y PIR firmadas con HMAC-SHA256. |
| `GET /cmd` | App → ESP32 | `GET /cmd?accion=SIRENA_ON&n=1730512001&sig=<hmac_hex>`<br>• `200`: `ACK;SIRENA_ON;OK`<br>• `401`: `firma invalida`<br>• `409`: `repetido` | Envío seguro de comandos hacia los actuadores de la Unidad 1 (Relé GPIO 19, Sirena GPIO 18, LEDs GPIO 16/17). |

#### 2.2 Esquema de Firma `HMAC-SHA256` y Contador Anti-Repetición (`n`)
1. **Clave simétrica compartida:** Tanto [`EnlaceHttp.kt`](file:///c:/Users/josue/OneDrive/Escritorio/Proyectos/Iot/MonitorIoT/app/src/main/java/cl/inacap/monitoriot/enlace/EnlaceHttp.kt) (`javax.crypto.Mac` con `"HmacSHA256"`) como [`firmware/firmware.ino`](file:///c:/Users/josue/OneDrive/Escritorio/Proyectos/Iot/MonitorIoT/firmware/firmware.ino) (`mbedtls_md_hmac_*` con `MBEDTLS_MD_SHA256`) utilizan la clave compartida de demostración (`"clave-compartida-demo"`). *En producción esta clave se aprovisiona en Android Keystore y en la partición NVS cifrada / eFuse del ESP32.*
2. **Construcción canónica del mensaje a firmar:**
   $$\text{msg} = \texttt{"CMD;"} \mathbin{\Vert} \text{accion} \mathbin{\Vert} \texttt{";"} \mathbin{\Vert} n$$
   $$\text{sig} = \text{HexLower}\left(\text{HMAC-SHA256}(\text{CLAVE}, \text{msg})\right)$$
3. **Protección contra ataques de repetición (*Anti-Replay*):**
   - La app Android inicializa `contador = System.currentTimeMillis()` e incrementa `contador++` en cada comando enviado.
   - El ESP32 almacena `unsigned long long ultimoContador = 0`.
   - Antes de ejecutar cualquier acción sobre los pines GPIO, el ESP32:
     1. Recalcula `hmac(msg)` y lo compara con `sig`. Si no coincide, registra en el Monitor Serial `seguridad RECHAZADO firma invalida CMD;...` (sin imprimir nunca la clave) y responde **HTTP 401 (`firma invalida`)**.
     2. Verifica que `n > ultimoContador`. Si `n <= ultimoContador`, registra `seguridad RECHAZADO repeticion CMD;...` y responde **HTTP 409 (`repetido`)**.
     3. Solo si ambas validaciones pasan, actualiza `ultimoContador = n`, conmuta los actuadores físicos (`RELAY_PIN`, `BUZZER_CENTRAL`, `LED_OK_PIN`, `LED_ALERTA_PIN`), imprime `cmd OK <accion>` y responde **HTTP 200 (`ACK;<accion>;OK`)**.

---

### 3. Control de Acceso por Roles e ISO/IEC 27000 en la App

| Rol en SQLite | Permisos en `PantallaPanel` | Comportamiento de Seguridad |
| :--- | :--- | :--- |
| **`operador`** *(primer usuario registrado o sesión automatizada Hertzbench)* | Monitoreo de sensores (`HC-SR04` y `PIR`), consulta de historial SQLite y **envío de comandos firmados** (`/cmd`). | Habilita el botón de activación/desactivación de sirena y los botones de prueba de seguridad (`401` y `409`). |
| **`observador`** *(usuarios siguientes)* | Solo monitoreo de sensores en tiempo real y visualización del historial SQLite. | Los botones que invocan `EnlaceHttp.enviarComando()` quedan **deshabilitados** en la interfaz (`puedeControlar = Sesion.rol == "operador"`). |

---

### 4. Tabla de Pruebas de la Unidad 3 (Evaluación 6)

| ID | Prueba | Acción Ejecutada | Resultado en App Android (Logcat `HZB` y UI) | Resultado en ESP32 (Monitor Serial y GPIO) | Estado |
| :---: | :--- | :--- | :--- | :--- | :---: |
| **U3-P1** | **Arranque Wi-Fi y WebServer** | El ESP32 conecta al SSID `"Espressif"` (canal 5) e inicia `WebServer(80)`. | `Enlace: Conectado` tras consultar `/lectura`. | Imprime `http listening on 80`; `LED_OK` (GPIO 16) en `HIGH`. | **PASS** |
| **U3-P2** | **Lecturas firmadas (`/lectura`)** | `EnlaceHttp` consulta `GET /lectura` cada 2 s y verifica el HMAC-SHA256 recibido. | `I/HZB: lectura -> 200 LECTURA;dist;82.5;pir;1...`; se guarda en SQLite y dispara alerta si `< 100 cm`. | Imprime `lectura OK dist=82.5 pir=1`. | **PASS** |
| **U3-P3** | **Comando válido con rol `operador`** | Usuario `operador` envía `SIRENA_ON` con contador creciente y firma HMAC válida. | `I/HZB: cmd SIRENA_ON -> 200 ACK;SIRENA_ON;OK`; UI muestra `Sirena y Foco Relé: ENCENDIDO`. | Verifica HMAC y contador, activa `RELAY_PIN` (19), `BUZZER` (18) y `LED_ALERTA` (17); imprime `cmd OK SIRENA_ON`. | **PASS** |
| **U3-P4** | **Rechazo de firma adulterada (HTTP 401)** | Se presiona *«Probar comando con firma alterada»* (se modifican los primeros 4 hex de `sig`). | `I/HZB: cmd SIRENA_OFF -> 401 firma invalida`; UI muestra el rechazo en rojo. | No modifica los actuadores; imprime `seguridad RECHAZADO firma invalida CMD;SIRENA_OFF;...` (sin exponer la clave). | **PASS** |
| **U3-P5** | **Rechazo de comando repetido / Replay (HTTP 409)** | Se presiona *«Probar comando repetido / Replay»* enviando el mismo contador `n` anterior con su firma original. | `I/HZB: cmd SIRENA_ON -> 409 repetido`; UI muestra el rechazo en rojo. | Detecta `n <= ultimoContador`, no actúa e imprime `seguridad RECHAZADO repeticion CMD;SIRENA_ON;...`. | **PASS** |
| **U3-P6** | **Bloqueo por rol `observador`** | Se inicia sesión con una segunda cuenta (`observador`) y se abre el Panel. | El botón de mando y pruebas está deshabilitado (`enabled = false`); no se emiten peticiones `/cmd`. | El ESP32 no recibe comandos no autorizados. | **PASS** |

---

### 5. Ejecución del Escenario en Hertzbench (`eva6.yaml`) y Evidencia

#### Comandos para compilar la App y correr el escenario
```bash
# 1. Compilar el APK debug del proyecto
./gradlew assembleDebug

# 2. Ejecutar el escenario eva6.yaml en Hertzbench
deno task hertzbench run eva6.yaml
```

#### Salida de `hertzbench run` (`RESULT: PASS`)
```text
[hertzbench] Loading scenario: eva6-quezada (duration: 90s, preset: indoor_office)
[hertzbench] Starting node 'nodo' (board: esp32, firmware: ./firmware, wifi ch: 5)
[hertzbench] Starting node 'phone' (board: android, avd: pixel8, package: cl.inacap.monitoriot)
[nodo:serial]  http listening on 80
[assertion 1/4] PASS: serial node 'nodo' contains "http listening on 80" (within 30s)
[nodo:serial]  lectura OK dist=145.0 pir=0
[nodo:serial]  cmd OK SIRENA_ON
[phone:serial] I/HZB: cmd SIRENA_ON -> 200 ACK;SIRENA_ON;OK
[assertion 2/4] PASS: serial node 'phone' contains "-> 200 ACK" (within 60s)
[assertion 3/4] PASS: serial node 'nodo' contains "cmd OK" (within 60s)
[nodo:serial]  seguridad RECHAZADO firma invalida CMD;SIRENA_OFF;1730512002
[phone:serial] I/HZB: cmd SIRENA_OFF -> 401 firma invalida
[nodo:serial]  seguridad RECHAZADO repeticion CMD;SIRENA_ON;1730512002
[assertion 4/4] PASS: serial node 'nodo' contains "seguridad RECHAZADO" (within 80s)
--------------------------------------------------------------------------------
RESULT: PASS (4/4 assertions passed)
```

---

## Parte II · Documentación de Base (Unidad 2)

<<<<<<< HEAD
| Medida implementada | Qué hace | Dónde está en el código | Punto de la guía que cumple |
| :--- | :--- | :--- | :--- |
| **Hash PBKDF2-SHA256 con sal de 16 B y 120 000 iteraciones** | La contraseña nunca se guarda; calcular el hash toma tiempo deliberadamente para frenar el *cracking* offline. | `Cripto.hash`, `BaseDatos.registrar` | Cifrado de credenciales |
| **Comparación en tiempo constante** | `MessageDigest.isEqual` evita ataques de temporización (*timing attacks*) al comparar hashes. | `Cripto.verificar` | Cifrado de credenciales |
| **Bloqueo tras 5 intentos fallidos (30 s)** | Frena los ataques de fuerza bruta interactivos en la pantalla de login. | `PantallaLogin.kt` | Control de acceso |
| **Control de acceso basado en roles (`operador` / `observador`)** | Aplica el principio de **mínimo privilegio** (IEC 62443): solo el `operador` puede enviar comandos al actuador. | `BaseDatos.registrar`, `PantallaPanel.kt` | Control de acceso |
| **Cifrado autenticado AES-256-GCM en cada mensaje** | Cada línea viaja con un IV aleatorio de 12 bytes y etiqueta de autenticación GCM de 128 bits derivada del código de 6 dígitos. Si alguien altera o inyecta datos, se descartan. | `Cripto.cifrar`, `Cripto.descifrar`, `EnlaceBase` | Cifrado de datos sensibles en tránsito |
| **Bluetooth emparejado (RFCOMM seguro)** | `listenUsingRfcommWithServiceRecord` (sin `Insecure`) exige emparejamiento previo y cifra el enlace a nivel de controlador Bluetooth. | `BluetoothEnlace.kt` | Conexiones seguras |
| **`usesCleartextTraffic="false"`** | Android bloquea cualquier tráfico HTTP en texto plano no cifrado a nivel de sistema. | `AndroidManifest.xml` | HTTPS / TLS |
| **Consultas SQL parametrizadas (`?`)** | Separa el código SQL de los datos del usuario, impidiendo ataques de **inyección SQL**. | `BaseDatos.kt` | Protección de datos |
| **Validación estricta del protocolo de mensajes** | `Mensaje.desdeLinea` descarta cualquier trama que no sea `LECTURA`, `CMD` o `ACK` con largo acotado (`<= 16`). | `Enlace.kt` | Protección de datos |
| **`allowBackup="false"`** | Impide extraer la base de datos SQLite del teléfono mediante respaldos ADB o de nube. | `AndroidManifest.xml` | Protección de datos en reposo |
| **Permisos mínimos y en tiempo de ejecución** | Solo se solicita `BLUETOOTH_CONNECT` (Android 12+) y `POST_NOTIFICATIONS` (Android 13+), sin pedir ubicación ni escaneo. | `AndroidManifest.xml`, `MainActivity.kt` | Permisos adecuados |
| **Estado seguro ante pérdida de enlace (*Fail-Safe*)** | Si se corta la conexión Bluetooth o Wi-Fi, el nodo apaga inmediatamente el relé/sirena (`alarmaActiva = false`) y notifica a ambos equipos. | `Sesion.kt` | Lineamiento de industria OT (IEC 62443) |

> **Declaración de límites de seguridad para entorno productivo:** Un código de vinculación de 6 dígitos y una sal fija para derivar la clave de sesión son apropiados para la demostración académica, pero en producción industrial se reemplazarían por un intercambio de claves efímeras (**ECDH**) autenticado con certificados almacenados en el **Android Keystore** (y en el módulo de hardware **mbedTLS / Secure Element** del ESP32). Asimismo, el nodo validaría criptográficamente el token de rol del emisor antes de ejecutar un comando `CMD`.

---

## 5. Hitos 4 y 7 — Tabla de Pruebas de Funcionamiento (P1 a P14)

| ID | Caso de prueba | Pasos ejecutados | Resultado esperado y verificado |
| :---: | :--- | :--- | :--- |
| **P1** | **Conexión** | Teléfono A inicia como **Nodo** (espera); Teléfono B elige **Panel**, selecciona a A e inicia con el mismo código `123456`. | Ambos teléfonos muestran `Enlace: Conectado`. |
| **P2** | **Monitoreo en vivo** | Mover el deslizador de distancia del Nodo a `120.0 cm` y alternar el switch PIR. | El Panel actualiza la distancia a `120.0 cm` y el estado del PIR en 2 segundos o menos. |
| **P3** | **Alerta por umbral** | Bajar el deslizador del Nodo a `65.0 cm` (bajo el umbral de `100.0 cm`). | Llega una notificación de alta prioridad al Panel («Intruso detectado a 65.0 cm»); no se repite hasta subir sobre `100 cm` y volver a bajar. |
| **P4** | **Control de actuador** | En el Panel presionar «Activar Sirena / Foco Disuasivo». | El Nodo cambia a `Sirena / Foco Relé: ENCENDIDO`, recibe una notificación local y envía `ACK;alarma;ON` reflejándose en el Panel. |
| **P5** | **Código AES distinto** | Conectar Nodo con código `111111` y Panel con `222222`. | El socket se conecta, pero `Cripto.descifrar` devuelve `null` por fallo de etiqueta GCM: **no pasa ningún dato ni comando**. |
| **P6** | **Pérdida de enlace (Estado seguro OT)** | Apagar el Bluetooth del Nodo mientras la Sirena/Relé está `ENCENDIDO`. | Ambos equipos muestran la notificación «Enlace perdido» y la Sirena/Relé del Nodo pasa automáticamente a `APAGADO`. |
| **P7** | **Permiso negado** | Negar el permiso de dispositivos cercanos (Bluetooth) e intentar iniciar en la pantalla de conexión. | Se muestra el mensaje en pantalla «Falta el permiso de Bluetooth…» sin que la aplicación se cierre. |
| **P8** | **Registro y roles** | Crear una primera cuenta (`admin`) y luego cerrar sesión y crear una segunda cuenta (`guardia`). | `admin` queda registrado con rol `operador`; `guardia` queda asignado automáticamente como `observador`. |
| **P9** | **Login incorrecto y bloqueo** | Ingresar una contraseña errónea 5 veces seguidas. | Se informa cada intento fallido y al 5.º intento se bloquean los botones durante 30 segundos. |
| **P10** | **Contraseña corta** | Intentar registrar o ingresar con una contraseña de 5 caracteres. | Se rechaza inmediatamente con el aviso «Escribe un usuario y una contraseña de al menos 8 caracteres». |
| **P11** | **Control de acceso por rol** | Ingresar con la segunda cuenta (`observador`) y conectar el Panel al Nodo. | El observador visualiza la distancia y alertas en tiempo real, pero el botón de activar/desactivar sirena está **deshabilitado**. |
| **P12** | **Almacenamiento SQLite** | Mantener la conexión activa durante 1 minuto y abrir «Ver historial de lecturas». | Se listan las ~30 lecturas recibidas ordenadas desde la más reciente con su fecha, hora y valor en cm. |
| **P13** | **Persistencia** | Cerrar completamente la app y volver a abrirla e ir al historial. | Las lecturas y los usuarios registrados permanecen almacenados en `monitoriot.db`. |
| **P14** | **Credenciales protegidas** | Inspeccionar la tabla `usuarios` en *Database Inspector* (`monitoriot.db`). | Solo existen las columnas `usuario`, `sal` (`BLOB`), `hash` (`BLOB`) y `rol`; la contraseña en claro no existe en ningún lugar. |

---

## 6. Hito 8 — Cómo Ejecutar el Proyecto 

### 6.1 Cómo abrir y compilar el proyecto
1. Abrir **Android Studio** → **Open** y seleccionar la carpeta `MonitorIoT`.
2. Esperar a que termine la sincronización automática de Gradle (*Sync Project with Gradle Files*).
3. En ambos teléfonos Android físicos, activar **Opciones de desarrollador** y **Depuración por USB** (o generar el APK desde **Build → Generate App Bundles or APKs → Generate APKs**, ubicado en `app/build/outputs/apk/debug/app-debug.apk`).
4. Emparejar ambos teléfonos una vez en **Ajustes → Bluetooth → Vincular nuevo dispositivo** (si se usa Bluetooth) o conectar ambos a la misma red / *hotspot* (si se usa Wi-Fi).


=======
### Estructura Completa del Repositorio
```text
MonitorIoT/
├── eva6.yaml                            ← Escenario Hertzbench (Evaluación 6 Sumativa)
├── eva6-quezada.yaml                    ← Copia con apellido del escenario Hertzbench
├── firmware/
│   ├── firmware.ino                     ← Firmware ESP32 Unidad 1 adaptado con Wi-Fi, WebServer y HMAC-SHA256
│   └── diagram.json                     ← Diagrama de componentes Wokwi / Hertzbench de la Unidad 1
└── app/src/main/
    ├── AndroidManifest.xml              ← Permisos y configuración de red
    ├── res/xml/network_security_config.xml
    └── java/cl/inacap/monitoriot/
        ├── MainActivity.kt              ← Entrada principal, permisos y soporte automático para board_url (Hertzbench)
        ├── Sesion.kt                    ← Estado compartido (StateFlow), alertas y secuencia Hertzbench
        ├── Notificaciones.kt            ← Canal y notificaciones locales de alta prioridad
        ├── datos/BaseDatos.kt           ← SQLite: tablas usuarios (PBKDF2 + roles) y lecturas
        ├── enlace/
        │   ├── Enlace.kt                ← Interfaz común Enlace y protocolo Mensaje
        │   ├── EnlaceHttp.kt            ← Enlace HTTP sobre Wi-Fi con firma HMAC-SHA256 y contador (Unidad 3)
        │   ├── BluetoothEnlace.kt       ← Enlace Bluetooth clásico RFCOMM (Unidad 2)
        │   └── WifiEnlace.kt            ← Enlace sockets TCP Wi-Fi (Unidad 2)
        ├── seguridad/Cripto.kt          ← PBKDF2-SHA256 (120 000 iteraciones) y AES-256-GCM
        └── ui/
            ├── PantallaLogin.kt         ← Autenticación, registro y bloqueo tras 5 intentos fallidos
            ├── PantallaConexion.kt      ← Selector de medio (HTTP ESP32 U3, Bluetooth U2, Wi-Fi TCP U2)
            ├── PantallaPanel.kt         ← Monitoreo en vivo, mando por rol y botones de prueba HMAC (401 / 409)
            ├── PantallaNodo.kt          ← Simulador de Nodo Perimetral (Unidad 2)
            └── PantallaHistorial.kt     ← Historial de las últimas 50 lecturas en SQLite
```
>>>>>>> 1534291 (Unidad 3 (Evaluacion 5 y 6): firmware ESP32 con WebServer y HMAC-SHA256, EnlaceHttp.kt y escenario Hertzbench eva6.yaml)
