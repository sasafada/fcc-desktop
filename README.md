# FCC Claude (APK)

App Android com visual próprio que roda o **Free Claude Code** + **Claude Code** dentro de um Linux (proot) embutido.
Nada para o usuário instalar além do APK.

## Gerar o APK
1. Crie um repositório no GitHub e envie esta pasta (branch `main`).
2. Aba **Actions → Build APK → Run workflow** (demora ~30–60 min; o arm64 é emulado).
3. Baixe o artefato `fcc-claude-apk`, extraia o `.apk` e instale no celular (arm64, Android 8+).

## O que o build faz
- Baixa o `proot` do Termux e o coloca em `jniLibs` (único lugar executável no Android 10+).
- Monta Ubuntu arm64 e roda o `install.sh` oficial do FCC (versão mais recente) **com o patch**:
  só `install_claude=1`; todos os demais agentes = 0 (sem perguntas).
- Empacota tudo em `assets/rootfs.tar.gz`. No 1º uso o app extrai e abre `fcc-claude`,
  cujo menu de login (conta paga / Anthropic por link / por API) é o original.

## Se o build falhar
O log do Actions mostra o passo. Pontos mais prováveis: assinatura de interfaces do `terminal-view`
(Kotlin), nome dos arquivos nos .deb do Termux, ou o instalador do Claude Code sob emulação.
