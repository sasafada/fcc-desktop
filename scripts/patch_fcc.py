#!/usr/bin/env python3
import sys
from pathlib import Path

rootfs = Path(sys.argv[1])
matches = list(rootfs.glob(
    "root/.local/share/uv/tools/*/lib/python*/site-packages/"
    "free_claude_code/api/admin_static/admin.js"
))

if len(matches) != 1:
    raise SystemExit(
        f"ERRO: esperado 1 admin.js; encontrados: {len(matches)}"
    )

js = matches[0]
source = js.read_text()

old = """  const modes = Array.isArray(status.supported_login_modes) ? status.supported_login_modes : [];
  const defaultMode = status.default_login_mode;
  if (!["browser", "device"].includes(defaultMode) || !modes.includes(defaultMode)) {
    actions.appendChild(authButton("Retry", () => refreshConnectedAccount(provider)));
    return;
  }
  actions.appendChild(
    authButton(
      "Connect",
      (button) => startConnectedAccountLogin(providerId, defaultMode, button),
    ),
  );"""

new = """  // FCC Android: explicit OAuth login mode selection.
  const modes = Array.isArray(status.supported_login_modes)
    ? ["device", "browser"].filter((mode) => status.supported_login_modes.includes(mode))
    : [];
  if (modes.length === 0) {
    actions.appendChild(authButton("Retry", () => refreshConnectedAccount(provider)));
    return;
  }
  modes.forEach((mode, index) => {
    const label = modes.length === 1
      ? "Connect"
      : mode === "device" ? "Connect with code" : "Connect with browser";
    actions.appendChild(authButton(
      label,
      (button) => startConnectedAccountLogin(providerId, mode, button),
      index === 0 ? "primary-button" : "secondary-button",
    ));
  });"""

if "FCC Android: explicit OAuth login mode selection." in source:
    print("Seletor OAuth já aplicado.")
elif source.count(old) != 1:
    raise SystemExit(
        f"ERRO: trecho original encontrado {source.count(old)} vezes."
    )
else:
    js.write_text(source.replace(old, new, 1))
    print(f"OK: painel atualizado em {js}")
