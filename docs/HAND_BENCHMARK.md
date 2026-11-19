# Evaluación de reconocimiento de mano (offline)

El diagnóstico `Vision actual: 4/4` mide cobertura, **no precisión**. Para medir precisión, comparar los cuatro slots de cada cuadro con etiquetas manuales de una grabación autorizada. Este evaluador no lee imágenes ni envía datos a la red.

Crear un JSONL **privado, fuera del repositorio**, con una línea por cuadro:
```json
{"deck":"ice_bow","match_id":"sesion-privada-01","time_ms":1000,"source_permission":"owner","ground_truth":["X-Bow","Tesla","Ice Wizard","Knight"],"predicted":["X-Bow","Tesla",null,"Rocket"]}
```
`ground_truth` y `predicted` deben estar en orden de izquierda a derecha, cuatro entradas cada uno. `null` significa que el detector no identificó ese slot. Usar solamente `ice_bow`, `miner_control` o `hypno_wb_bait`. Evoluciones/héroes se normalizan al ID de carta base antes de etiquetar. No publicar datos de partidas, capturas ni video.

Ejecutar desde la raíz:
```bash
python3 scripts/hand_slot_benchmark.py /ruta/privada/frames.jsonl
python3 -m unittest discover -s scripts -p 'test_hand_slot_benchmark.py'
```
Métricas por arquetipo: cantidad de cuadros, aciertos de slot, falsos positivos (carta errónea detectada), omisiones (slot incorrecto o desconocido), precisión entre detecciones, recall sobre los cuatro slots y tasa de manos completamente correctas. Separar partidas de calibración y evaluación; no usar cuadros del mismo encuentro en ambos conjuntos. Los tests sintéticos no constituyen medición del teléfono. Si no hay cuadros etiquetados, las métricas se reportan como **no medido**.
