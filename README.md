# FCC Claude (APK)

App Android com visual próprio que roda o Free Claude Code + Claude Code num Linux (proot) embutido.

## Gerar o APK
Actions → Build APK → Run workflow. O 1º build leva 30–60 min (o resto vem do cache).
Baixe o artefato `fcc-claude-apk`.

## Instalar
O APK agora é assinado com uma chave FIXA (`fcc.p12`). Se você tem uma versão antiga instalada
(assinada com outra chave), **desinstale uma vez**; depois todas as atualizações instalam por cima.

## Diagnóstico no app
Menu ⋮ → "Diagnóstico (fcc-doctor)", "Ver log do servidor" ou "Abrir shell".
