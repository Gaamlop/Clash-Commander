# Clash Commander: objetivo de entrenamiento y validación

## Alcance acordado

Concentrar la investigación y las pruebas en **tres mazos**, identificados por las ocho cartas base del enlace de Clash Royale. Una evolución o héroe conserva la identidad de la carta base para emparejar el mazo, pero la lógica debe conocer sus diferencias de comportamiento cuando haya datos observables.

| Perfil | Cartas base |
| --- | --- |
| Ice Bow | X-Bow, Tesla, Ice Wizard, Knight, Skeletons, Rocket, Tornado, The Log |
| Miner Control | Bats, Knight, Wall Breakers, Spear Goblins, Bomb Tower, The Log, Miner, Poison |
| Hypno WB Log Bait 2.8 | Cannon, Dart Goblin, Goblin Barrel, Ice Spirit, Princess, Skeleton Army, Valkyrie, Wall Breakers |

Hypno usa *Wall Breakers Log Bait*, **sin The Log** en la lista consultada. No sustituirlo por el Log Bait clásico sin evidencia.

Fuentes: [Ice Bow](https://deckroyale.gg/deck/x-bow%2Ctesla%2Cice_wizard%2Cknight%2Cskeletons%2Crocket%2Ctornado%2Cthe_log), [Miner Control](https://trackerroyale.com/es/archetype/miner-control), [WB Bait de Hypno en RoyaleAPI](https://royaleapi.com/decks/stats/cannon%2Cdart-goblin%2Cgoblin-barrel-ev1%2Cice-spirit%2Cprincess%2Cskeleton-army-ev1%2Cvalkyrie-hero%2Cwall-breakers/players/ratings).

## Diagnóstico de partida real ya observado

En la partida y capturas compartidas por el usuario, Android sí capturó la arena, pero el modelo visual reconoció entre dos y tres de las cuatro cartas de la mano. Autopilot esperó aun con elixir máximo. Las referencias de imagen de v1.4 son una solución experimental adaptada a esas capturas; no constituyen un detector competitivo validado. La conexión de Gemini y el análisis textual pueden funcionar mientras falla la ejecución.

### Prioridad P0: observación y reproducibilidad

1. Registrar estado por cuadro con timestamp: tamaño de captura, confianza por slot, carta siguiente, elixir visible, torres, enemigos y por qué se descartó una decisión.
2. Crear un banco **offline** de capturas o videos autorizados, etiquetado manualmente por carta y por momento de partida.
3. Medir precisión por carta, por mazo, cuatro slots simultáneos, latencia p50/p95, confusiones entre cartas y tasa de falsas detecciones.
4. Probar sensibilidad a resolución 1080×2400, barras del sistema, zoom y distintos estados de brillo/gris.

### Prioridad P1: seguridad y ejecución

1. No tocar la pantalla con captura obsoleta, carta no verificada, slot equivocado o zona no válida.
2. Identificar elixir propio de forma fiable, **no inventar** elixir rival o estado del ciclo.
3. Verificar la jugada tras el gesto: elixir gastado, cambio de mano y unidad/edificio desplegado. Distinguir «se solicitó» de «se ejecutó».
4. Medir fallos de taps y recalibrar coordenadas en cada resolución.

### Prioridad P2: tácticas por mazo

1. Ice Bow: defensa y control de ciclo; X-Bow solo con ventana favorable; proteger Tesla y Rocket.
2. Miner Control: elixir positivo, variación de colocación de Miner, valor de Poison y presión de Wall Breakers.
3. Hypno WB Bait: seguimiento de hechizos pequeños **solo cuando se observaron**, presión opuesta y conservación de Cannon/Valkyrie.

Combinar reglas rápidas verificables con Gemini para decisiones de alto nivel, no pedir a la API en cada frame. La respuesta del modelo siempre pasa por validación determinista de carta, legalidad, frescura, elixir y zona.

### Prioridad P3: evaluación y entrenamiento

- Establecer un **baseline** medido antes de proclamar mejora.
- Comparar decisiones sobre el mismo conjunto fijo de estados de prueba; guardar etiqueta correcta, propuesta, latencia y resultado.
- Entrenar un clasificador con datos propios solo cuando exista conjunto de validación independiente.
- Evaluar partidas permitidas por arquetipo y por tipo de emparejamiento; medir resultado, daño recibido, elixir desperdiciado, tiempo sin respuesta y razón de fallos.
- Nunca interpretar «Build APK: success» como garantía de que Autopilot juega o gana.

## Criterios sugeridos antes de llamar a una versión «competitiva»

Son **metas de ingeniería, no resultados logrados**: lecturas correctas de la mano y del elixir en conjuntos de validación variados, errores de ejecución excepcionales, latencia de decisión bajo presión medida y resultados replicados en un volumen de pruebas significativo. No se puede prometer una tasa de victorias sin datos reales.

## Seguridad de cuenta y entorno de pruebas

Supercell clasifica bots, scripts y servicios que automatizan jugadas como comportamiento inaceptable. El desarrollo y las pruebas deberían realizarse con replays, análisis offline o entornos explícitamente permitidos; evitar exponer la cuenta principal a sanciones. No crear ni almacenar en GitHub claves de Gemini o tokens de Cloudflare.

## Disciplina de desarrollo

- Cada cambio debe incluir hipótesis, prueba que podría falsarla, resultado y limitación.
- PR acotado + test automatizado + revisión de compilación antes de fusionar.
- No lanzar una nueva APK como «solución definitiva» sin validación en dispositivo.
- Pedir intervención del usuario solo cuando haga falta una evidencia que GitHub no puede suministrar: video/captura y resultado de prueba en Android.
