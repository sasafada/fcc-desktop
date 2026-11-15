#!/bin/bash
# Lançador do FCC Claude (Android).
# 1) garante que o FCC Server esteja rodando e SAUDÁVEL (sobe um novo se precisar, em porta livre)
# 2) só depois abre o Claude Code via fcc-claude, apontando para essa mesma porta.
export HOME=/root LANG=C.UTF-8
export PATH=/root/.local/bin:/root/.claude/local:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOST=127.0.0.1                      # só local: não expõe o proxy na rede Wi‑Fi
BASE="${FCC_PORT:-8082}"
LOG=/tmp/fcc-server.log
PORTFILE=/tmp/fcc-port
P='\033[38;5;141m'; G='\033[38;5;84m'; R='\033[38;5;203m'; D='\033[38;5;245m'; N='\033[0m'

health()      { curl -fsS -m 3 -o /dev/null "http://127.0.0.1:$1/health" 2>/dev/null; }
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

fail_shell() {
  printf "\n${R}✖ %s${N}\n" "$1"
  if [ -s "$LOG" ]; then printf "${D}— saída do servidor (%s):${N}\n" "$LOG"; tail -n 25 "$LOG" | cut -c1-220; fi
  if [ -s /root/.fcc/logs/server.log ]; then printf "${D}— log interno (~/.fcc/logs/server.log):${N}\n"; tail -n 6 /root/.fcc/logs/server.log | cut -c1-220; fi
  printf "\n${D}Você está num shell. Úteis: fcc-doctor · cat %s${N}\n\n" "$LOG"
  exec bash -l
}

# 1) já existe um servidor saudável? reutiliza.
PORT_OK=""
for p in $(seq "$BASE" $((BASE+8))); do
  if health "$p"; then PORT_OK="$p"; break; fi
done

# 2) senão, sobe um novo numa porta livre.
if [ -z "$PORT_OK" ]; then
  pkill -f 'bin/fcc-server' 2>/dev/null; sleep 1       # restos de uma sessão anterior
  CHOSEN=""
  for p in $(seq "$BASE" $((BASE+8))); do
    if ! port_in_use "$p"; then CHOSEN="$p"; break; fi
  done
  [ -n "$CHOSEN" ] || fail_shell "Nenhuma porta livre entre $BASE e $((BASE+8))."
  [ "$CHOSEN" != "$BASE" ] && printf "${D}  porta %s ocupada; usando %s${N}\n" "$BASE" "$CHOSEN"

  printf "${P}●${N} Iniciando o servidor FCC ${D}(porta %s — na 1ª vez pode levar alguns minutos)${N}\n" "$CHOSEN"
  : > "$LOG"
  PORT="$CHOSEN" nohup fcc-server >>"$LOG" 2>&1 &
  SRV=$!
  start=$(date +%s)
  until health "$CHOSEN"; do
    kill -0 "$SRV" 2>/dev/null || fail_shell "O servidor FCC encerrou antes de ficar pronto."
    el=$(( $(date +%s) - start ))
    [ "$el" -ge 420 ] && fail_shell "O servidor FCC não respondeu na porta $CHOSEN em 7 minutos."
    printf "\r${D}  carregando… %ss${N}" "$el"
    sleep 1
  done
  PORT_OK="$CHOSEN"
  printf "\r\033[K"
fi

echo "$PORT_OK" > "$PORTFILE"
printf "${G}✓${N} Servidor FCC pronto ${D}(porta %s)${N}\n" "$PORT_OK"
printf "${D}  Dica: menu ⋮ → \"Configurar provedores\" abre o painel /admin do FCC.${N}\n\n"

export PORT="$PORT_OK"
exec fcc-claude "$@"
