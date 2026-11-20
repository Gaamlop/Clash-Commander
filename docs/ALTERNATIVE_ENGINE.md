# Motor rápido: alternativa para un único Nothing Phone

## Decisión técnica

Conservar Clash Commander para captura automática, permisos, mazos, voz, parada
y gestos. Separar la percepción y la política táctica de las consultas generativas
a Gemini. El bloqueo de cuota documentado demuestra que un LLM remoto por jugada
no ofrece continuidad defensiva. Cambiar la clave o aumentar el timeout no corrige
esta dependencia ni la lectura incierta de la partida.

El propietario solo dispone de un Nothing Phone 1, Android 15. No se presupone
un PC disponible. La primera evaluación del motor se realiza fuera de la app;
ejecutarlo en el teléfono exige exportación e integración Android y mediciones
de latencia sostenida/temperatura. Un servidor de inferencia es una alternativa
si el teléfono no cumple el presupuesto, pero aún no está desplegado ni existe
una conexión de administración a un proveedor de cómputo en esta sesión.

## Bases comprobadas por código/documentación

| Base | Qué ofrece | Qué impide usarla directamente |
| --- | --- | --- |
| [sacha9214/clash-ai](https://github.com/sacha9214/clash-ai) | MIT; detector YOLO11s, seguimiento, lectura de HUD, reglas tácticas, modelo de colocación y pruebas CPU. El checkout incluye pesos reales, no solo enlaces. | Captura/control por ADB y scrcpy desde ordenador; geometría REDMAGIC y mazo inicial de Giant. No es APK ni política validada para nuestros tres mazos. |
| [FirstLight_CR](https://github.com/Jaasssoooonnnnn/FirstLight_CR) | Apache-2.0; modelos entrenados incluidos y servicio local de política por JSON-lines. | Recibe ObservationV1 estructurado de un entorno offline. No convierte capturas del juego original en observaciones. Su configuración de ejemplo requiere Windows/MuMu. |
| [ClashRoyaleBuildABot](https://github.com/Pbatch/ClashRoyaleBuildABot) | Base de percepción y generación de estado. | Flujo de escritorio/emulador; documentación móvil aún pendiente en el README consultado. No acredita calidad de una política para nuestros mazos. |
| [clash-jev](https://github.com/bytelabs-oss/clash-jev) | Pantalla de Android, decisiones por opciones y registro de partidas. | Requiere ordenador y acceso a otra API; no elimina por sí mismo la dependencia de disponibilidad de proveedor. |
| [ClashAI](https://github.com/vegetableleaf/ClashAI) | Investigación de imitación y PPO, especialmente Ice Bow. | README indica que no distribuye checkpoints/datasets. No hay vínculo verificado con la cuenta «Clasher». |

Fuente de la evaluación aislada: `sacha9214/clash-ai` commit
`093604e9a844d1032204f866bb76f9be8458bb87`. No se ha copiado su código ni sus
pesos a Clash Commander. El detector distribuido ocupa aproximadamente 19 MB y
el modelo de colocación 1,1 MB. Eso permite experimentar sin iniciar entrenamiento.
Incorporarlos requerirá conservar atribución y revisar las licencias concretas
de las dependencias/modelos, además de validar los resultados sobre nuestras imágenes.

## Evaluación aislada ejecutada

El checkout fijado pasó sus 192 pruebas CPU, sin omisiones, en 84,80 segundos.
Incluyen escenarios tácticos, seguimiento, registros de partidas del proyecto
externo y un smoke test que carga el detector real sobre imágenes sintéticas.
Entorno aislado Python 3.12, torch 2.15.0+cpu, torchvision 0.29.2+cpu,
ultralytics 8.4.215, OMP/MKL limitados a dos hilos. Se produjo un aviso de
deprecación de pytest/anyio. No se ejecutó setup.sh, entrenamiento ni control ADB.
El detector declaró 393 clases, incluidas Valkyrie normal, evolución y hero,
además de Dart Goblin. En tres inferencias tras calentamiento, con una imagen
sintética 576×1280 e imgsz1024, tardó 568,0 / 658,8 / 711,3 ms (mediana 658,8 ms,
CPU de este entorno y dos hilos). No se incluye red ni captura; no es una medida
del Nothing ni una prueba de precisión. Resultados en
[evidence/alternative-engine-cpu.json](evidence/alternative-engine-cpu.json).
Esto verifica ejecutabilidad de esa revisión, no precisión en nuestras capturas
ni victorias ni funcionamiento en Android. No justifica aún integrar este modelo
sin optimización o elegirlo como motor definitivo.

## Siguiente objetivo y condiciones de aceptación

1. Evaluar el detector con capturas independientes del Nothing: unidades, bando,
   posición, falsos positivos y HUD. Una prueba con imagen sintética solo verifica
   que el modelo carga y devuelve resultados con el contrato esperado.
2. Exportar y medir el modelo en Android. Mantener siempre edad y sesión de
   captura; descartar acciones si la mano cambia. No transferir la calibración
   REDMAGIC directamente al Nothing. Usar el mismo fotograma para mano y estado.
3. Construir primero un perfil completo de Hypno con las ocho cartas exactas del
   usuario; validar defensa terrestre/aérea, elixir, reserva y despliegue. Ampliar
   a Ice Bow y Miner Control tras superar esos escenarios. El mazo de Giant del
   repositorio externo no demuestra nada sobre esos perfiles.
4. Si el dispositivo no sostiene la latencia, ejecutar percepción/política en
   servidor con observaciones identificadas por fotograma y acciones caducables.
   Mantener parada y validación final de mano/coste/posición en Android. Un Worker
   que retransmite Gemini no equivale a ese servidor de inferencia.
5. Cerrar el objetivo únicamente con partidas completas registradas automáticamente:
   observación, decisión, intento de gesto y resultado visual. No exigir Img entre
   jugadas. Separar decisiones rechazadas, esperas justificadas y fallos de percepción.

Gemini puede conservarse para entrenamiento/análisis estratégico ocasional. Una
pausa de cuota en la versión actual implica ausencia de decisiones IA; no es un
motor local de respaldo. El motor alternativo aún no está integrado y no se ha
demostrado un Autopilot competitivo ni una tasa de victorias.
