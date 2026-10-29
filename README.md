# AMAZON RIVER · Camera

App de câmera para o smartwatch **Horizon Pro** (câmera rotativa lateral de 4 MP), no estilo Amazon River / Zeta Division.

## Correção: "câmera frontal não abre"
O Horizon Pro tem só uma câmera física — não existe sensor frontal dedicado. O app nunca tenta abrir uma câmera frontal inexistente (isso é o que travava antes); em vez disso:
- Detecta automaticamente quantas câmeras o hardware tem.
- Se houver só a traseira rotativa, o botão **LENS** alterna para um modo **FRONT** virtual: espelha a prévia e a foto, mostra a dica "ROTATE THE SIDE CAMERA TOWARD YOU" e usa a mesma câmera — ideal para selfies e videochamadas.
- Se o relógio tiver mesmo 2 câmeras, o app troca entre elas normalmente.
- Qualquer falha ao abrir a câmera tenta de novo automaticamente, em vez de deixar a tela preta ou travar.

## Tecnologias de qualidade (software)
- Resolução máxima do sensor (4 MP), `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG 100
- Pipeline HQ do Camera2: redução de ruído, nitidez de borda, correção de pixels quentes, aberração cromática, tone-map e lens shading em HIGH_QUALITY
- Estabilização óptica/eletrônica quando disponível
- Extensões via CameraX: HDR, NIGHT, Auto, Face Retouch
- Vídeo em UHD → FHD → HD
- Toque para focar, pinça para zoom, timer, flash, grade

## IA (100% no relógio, sem internet)
- AI Auto Scene (ML Kit): troca sozinho entre HDR / Night / Retouch
- Face Focus: foco segue o rosto
- Smile Shutter: dispara ao detectar sorriso
- AI Enhance: auto-níveis + vibração de cor + nitidez adaptativa

Fotos em `Pictures/AmazonRiverCamera`, vídeos em `Movies/AmazonRiverCamera`.

## Gerar o APK pelo GitHub
1. Envie estes arquivos ao repositório, mantendo os caminhos de pasta.
2. Aba Actions → Build APK roda sozinha a cada push.
3. Baixe `AmazonRiverCamera.apk` em Artifacts.

Requisitos: Android 8.0+ (minSdk 26) — compatível com o Horizon Pro (Android 8.1).
