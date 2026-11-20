# Atribución y código fuente del motor local

La aplicación 1.5 con el detector Ultralytics se distribuye conforme a
AGPL-3.0. Se conserva el aviso MIT y la atribución del código original de Yoyo
Studios; ese código original sigue disponible bajo su licencia MIT. El texto
AGPL se incluye en [licenses/AGPL-3.0.txt](licenses/AGPL-3.0.txt).

El detector entrenado procede de [sacha9214/clash-ai](https://github.com/sacha9214/clash-ai),
revisión `093604e9a844d1032204f866bb76f9be8458bb87`, cuyo código está bajo MIT,
Copyright 2026 Sacha. Su arquitectura YOLO11 y el exportador proceden de
[Ultralytics](https://github.com/ultralytics/ultralytics), AGPL-3.0. Se conserva
esa licencia para el detector convertido; no se atribuye su entrenamiento a
Clash Commander. La política de Hypno y el puente Android de esta revisión
son nuevos. El repositorio público proporciona el código de integración y el
script reproducible `scripts/local_engine/export_arena.py`, las versiones y
el hash del checkpoint original. No se distribuye el APK de Clash Royale ni
se modifica su cliente.

Los usuarios pueden obtener el código fuente correspondiente desde el mismo
commit de GitHub que generó el APK. Los modelos de voz conservan sus licencias
y atribuciones previas. ONNX Runtime se usa bajo MIT.
