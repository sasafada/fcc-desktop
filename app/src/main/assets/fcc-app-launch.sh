#!/bin/bash
# Lançador do FCC Claude (Android) — roda dentro de cada conversa.
# O FCC Server é UM só e é mantido pelo app. Aqui apenas esperamos ele ficar saudável
# (se o app não o tiver iniciado, subimos um com trava, sem nunca matar processos) e abrimos o Claude Code.
export HOME=/root LANG=C.UTF-8
export PATH=/root/.local/bin:/root/.claude/local:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOST=127.0.0.1                      # só local: não expõe o proxy na rede Wi‑Fi
BASE="${FCC_PORT:-8082}"
LOG=/tmp/fcc-server.log
PORTFILE=/tmp/fcc-port
P='\033[38;5;141m'; G='\033[38;5;84m'; R='\033[38;5;203m'; D='\033[38;5;245m'; N='\033[0m'

health()      { curl -fsS -m 3 -o /dev/null "http://127.0.0.1:$1/health" 2>/dev/null; }
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
find_healthy() { local p; for p in $(seq "$BASE" $((BASE+8))); do if health "$p"; then echo "$p"; return 0; fi; done; return 1; }

fail_shell() {
  printf "\n${R}✖ %s${N}\n" "$1"
  if [ -s "$LOG" ]; then printf "${D}— saída do servidor (%s):${N}\n" "$LOG"; tail -n 25 "$LOG" | cut -c1-220; fi
  if [ -s /root/.fcc/logs/server.log ]; then printf "${D}— log interno (~/.fcc/logs/server.log):${N}\n"; tail -n 6 /root/.fcc/logs/server.log | cut -c1-220; fi
  printf "\n${D}Você está num shell. Úteis: fcc-ctl status · fcc-ctl logs · fcc-doctor${N}\n\n"
  exec bash -l
}

# Servidor de reserva: só se ninguém (nem o app) estiver subindo um. flock evita duas conversas
# iniciando ao mesmo tempo; nada é morto aqui.
start_fallback() {
  (
    flock -w 30 9 || exit 1
    find_healthy >/dev/null && exit 0
    pgrep -f '[b]in/fcc-server' >/dev/null 2>&1 && exit 0
    local p c=""
    for p in $(seq "$BASE" $((BASE+8))); do if ! port_in_use "$p"; then c="$p"; break; fi; done
    [ -n "$c" ] || exit 2
    printf "${D}  iniciando servidor de reserva na porta %s${N}\n" "$c"
    PORT="$c" nohup fcc-server 9>&- >>"$LOG" 2>&1 &   # fecha o fd da trava: o servidor não a segura
  ) 9>/tmp/fcc-start.lock
}

printf "${P}●${N} Conectando ao servidor FCC ${D}(na 1ª vez pode levar alguns minutos)${N}\n"
start=$(date +%s); PORT_OK=""; fallback_tried=0
while :; do
  PORT_OK="$(find_healthy)" && break
  el=$(( $(date +%s) - start ))
  if [ "$el" -ge 25 ] && [ "$fallback_tried" = 0 ]; then
    fallback_tried=1
    if ! pgrep -f '[b]in/fcc-server' >/dev/null 2>&1; then start_fallback; fi
  fi
  [ "$el" -ge 420 ] && fail_shell "O servidor FCC não respondeu em 7 minutos."
  printf "\r${D}  carregando… %ss${N}" "$el"
  sleep 1
done
printf "\r\033[K"

echo "$PORT_OK" > "$PORTFILE"
printf "${G}✓${N} Servidor FCC pronto ${D}(porta %s)${N}\n" "$PORT_OK"
printf "${D}  Pasta de trabalho: %s${N}\n" "$(pwd)"
printf "${D}  Dica: ⚙ Admin (ou menu ⋮) abre o painel de provedores do FCC.${N}\n\n"

export PORT="$PORT_OK"
exec fcc-claude "$@"
