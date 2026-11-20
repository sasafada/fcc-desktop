#!/bin/bash
# fcc-ctl — consulta o FCC Server (iniciado e supervisionado pelo app).
export PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin
PORTFILE=/tmp/fcc-port
case "${1:-status}" in
  status)
    port="$(cat "$PORTFILE" 2>/dev/null)"; port="${port:-8082}"
    if curl -fsS -m 3 -o /dev/null "http://127.0.0.1:$port/health" 2>/dev/null; then
      echo "FCC Server: SAUDÁVEL em http://127.0.0.1:$port  (admin: /admin)"
    else
      echo "FCC Server: SEM RESPOSTA em http://127.0.0.1:$port"
    fi
    echo "Processos:"; pgrep -af '[b]in/fcc-server' | cut -c1-160 || echo "  (nenhum)"
    ;;
  logs)
    n="${2:-60}"
    echo "=== /tmp/fcc-server.log (saída do processo) ==="; tail -n "$n" /tmp/fcc-server.log 2>/dev/null | cut -c1-220
    echo; echo "=== ~/.fcc/logs/server.log (log interno) ==="; tail -n "$n" /root/.fcc/logs/server.log 2>/dev/null | cut -c1-220
    ;;
  *) echo "uso: fcc-ctl [status|logs [linhas]]";;
esac
