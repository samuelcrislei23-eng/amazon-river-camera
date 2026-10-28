# AMAZON RIVER · Camera

App de câmera para o smartwatch **Horizon Pro** (câmera rotativa lateral de 4 MP), no estilo Amazon River / Zeta Division: monocromático, tipografia limpa em caixa-alta, cantos retos, foto como protagonista.

## Tecnologias de qualidade (software)
- Resolução máxima do sensor (4 MP), `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG 100
- Pipeline HQ do Camera2: redução de ruído, nitidez de borda, correção de pixels quentes, aberração cromática, tone-map e lens shading em *HIGH_QUALITY* (só ativa o que o hardware declara suportar)
- Estabilização óptica (foto) / eletrônica (vídeo), quando disponível
- Extensões do fabricante via CameraX: **HDR, NIGHT (multi-frame), Auto, Face Retouch**
- Vídeo em UHD → FHD → HD (usa a maior qualidade suportada)
- Toque para focar, pinça para zoom, timer 3/5/10 s, flash, grade, troca de lente (câmera rotativa)

## Correção: "câmera frontal não abre"
O Horizon Pro tem só uma câmera física (a de 4 MP no braço lateral que gira) — não existe sensor frontal dedicado. O app nunca tenta abrir uma câmera frontal inexistente (isso é o que travava antes); em vez disso:
- Detecta automaticamente quantas câmeras o hardware tem.
- Se houver só a traseira rotativa (caso do Horizon Pro), o botão **LENS** alterna para um modo **FRONT** virtual: espelha a prévia e a foto, mostra a dica "ROTATE THE SIDE CAMERA TOWARD YOU" e usa a mesma câmera — ideal para selfies e videochamadas.
- Se o relógio tiver mesmo 2 câmeras, o app troca entre elas normalmente.
- Qualquer falha ao abrir a câmera é tratada com nova tentativa automática (até 6x no início, e recuo progressivo durante o uso), em vez de deixar a tela preta ou travar.

## IA (100% no relógio, sem internet)
- **AI Auto Scene** (ML Kit Image Labeling + análise de luz): detecta retrato, paisagem, comida, texto e pouca luz e troca sozinho entre HDR / Night / Retouch
- **Face Focus** (ML Kit Face Detection): foco e exposição seguem o rosto
- **Smile Shutter**: dispara automaticamente quando detecta sorriso
- **AI Enhance**: auto-níveis por histograma + vibração de cor + nitidez adaptativa em cada foto salva

Fotos ficam em `Pictures/AmazonRiverCamera`, vídeos em `Movies/AmazonRiverCamera`.

## Gerar o APK pelo GitHub
1. Crie um repositório e envie estes arquivos (`git init`, `git add .`, `git commit`, `git push` na branch `main`).
2. Aba **Actions → Build APK** roda sozinha a cada push (ou clique em *Run workflow*).
3. Baixe `AmazonRiverCamera.apk` em **Artifacts**. Para uma release pública: `git tag v1.0 && git push --tags`.
4. Instale no relógio: `adb install AmazonRiverCamera.apk` (ou pelo seu launcher/instalador).

Requisitos: Android 10+ (minSdk 29).
