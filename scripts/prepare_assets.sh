#!/usr/bin/env bash
# Roda no GitHub Actions (Ubuntu x86_64 + QEMU arm64).
# 1) baixa proot do Termux -> app/src/main/jniLibs
# 2) monta Ubuntu arm64, roda o instalador OFICIAL do FCC (só Claude Code) -> app/src/main/assets/rootfs.tar.gz
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.work"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS="$ROOT/app/src/main/assets"
R="$WORK/rootfs"
rm -rf "$WORK"; mkdir -p "$WORK" "$JNI" "$ASSETS"

sudo apt-get update -y
sudo apt-get install -y patchelf binutils xz-utils curl qemu-user-static binfmt-support

############################ 1) proot (Termux) ############################
BASE=https://packages.termux.dev/apt/termux-main
curl -fsSL "$BASE/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"

deb_path() {
  awk -v p="$1" 'BEGIN{RS="";FS="\n"}
    { n="";f=""; for(i=1;i<=NF;i++){ if($i ~ /^Package: /)n=substr($i,10); if($i ~ /^Filename: /)f=substr($i,11) }
      if(n==p){print f; exit} }' "$WORK/Packages"
}
for pkg in proot libtalloc libandroid-shmem; do
  f="$(deb_path "$pkg")"; [ -n "$f" ] || { echo "Pacote Termux não encontrado: $pkg"; exit 1; }
  curl -fsSL "$BASE/$f" -o "$WORK/$pkg.deb"
  mkdir -p "$WORK/x/$pkg"
  (cd "$WORK/x/$pkg" && ar x "$WORK/$pkg.deb" && tar -xf data.tar.*)
done

P="$WORK/x/proot/data/data/com.termux/files/usr"
cp "$P/bin/proot"             "$JNI/libproot.so"
cp "$P/libexec/proot/loader"  "$JNI/libproot-loader.so"
cp "$(find "$WORK/x/libtalloc" -name 'libtalloc.so*' -type f | head -1)"             "$JNI/libtalloc.so"
cp "$(find "$WORK/x/libandroid-shmem" -name 'libandroid-shmem.so*' -type f | head -1)" "$JNI/libandroid-shmem.so"
chmod 755 "$JNI"/*.so

needed() { readelf -d "$1" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'; }
for f in libproot.so libtalloc.so libandroid-shmem.so; do patchelf --remove-rpath "$JNI/$f" || true; done
patchelf --set-soname libtalloc.so "$JNI/libtalloc.so"
for n in $(needed "$JNI/libproot.so"); do
  case "$n" in libtalloc.so.*) patchelf --replace-needed "$n" libtalloc.so "$JNI/libproot.so";; esac
done
echo "== Dependências dos binários:"
for f in libproot.so libtalloc.so libandroid-shmem.so; do echo "-- $f"; needed "$JNI/$f"; done
bad="$( (for f in libproot.so libtalloc.so libandroid-shmem.so; do needed "$JNI/$f"; done) | sort -u \
        | grep -Ev '^(libc|libm|libdl|liblog|libtalloc|libandroid-shmem)\.so$' || true)"
[ -z "$bad" ] || { echo "ERRO: dependências inesperadas no proot: $bad"; exit 1; }

############################ 2) rootfs ############################
IDX=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/
TARBALL="$(curl -fsSL "$IDX" | grep -o 'ubuntu-base-24\.04[0-9.]*-base-arm64\.tar\.gz' | sort -uV | tail -1)"
[ -n "$TARBALL" ] || { echo "ubuntu-base arm64 não encontrado"; exit 1; }
curl -fsSL "$IDX$TARBALL" -o "$WORK/base.tgz"
sudo mkdir -p "$R"
sudo tar -xpf "$WORK/base.tgz" -C "$R"

cleanup() {
  for m in dev/pts dev proc sys; do sudo umount -lf "$R/$m" 2>/dev/null || true; done
}
trap cleanup EXIT
sudo rm -f "$R/etc/resolv.conf"
echo "nameserver 8.8.8.8" | sudo tee "$R/etc/resolv.conf" >/dev/null
if [ -x /usr/bin/qemu-aarch64-static ]; then sudo cp /usr/bin/qemu-aarch64-static "$R/usr/bin/"; fi
sudo mount --bind /proc "$R/proc"
sudo mount --bind /sys  "$R/sys"
sudo mount --bind /dev  "$R/dev"

CH() { sudo chroot "$R" /usr/bin/env -i HOME=/root LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive \
        PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "$@"; }

echo "== Verificando emulação arm64"
CH /bin/uname -m

CH apt-get update
CH apt-get install -y --no-install-recommends ca-certificates curl bash git ripgrep procps \
   xz-utils unzip less nano locales openssh-client

# Instalador OFICIAL do FCC, na versão mais recente, com as perguntas removidas:
# somente Claude Code (fcc-claude) = sim; todos os outros agentes = não.
VER="$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/Alishahryar1/free-claude-code/releases/latest | sed 's#.*/tag/v##')"
echo "== FCC versão: $VER"
curl -fsSL "https://raw.githubusercontent.com/Alishahryar1/free-claude-code/v$VER/scripts/install.sh" -o "$WORK/install.sh"
sed -i -E 's/^install_(codex|pi|opencode|hermes|dsh|grok|muse|aider|agy|cline)=1$/install_\1=0/' "$WORK/install.sh"
left="$(grep -E '^install_[a-z]+=1$' "$WORK/install.sh" || true)"
[ "$left" = "install_claude=1" ] || { echo "ERRO: patch do instalador falhou. Restou: $left"; exit 1; }
sudo cp "$WORK/install.sh" "$R/tmp/install.sh"

CH env FCC_INSTALL_VERSION="$VER" _FCC_INSTALLER_RELEASE="$VER" sh /tmp/install.sh

echo "== Verificação final"
CH /root/.local/bin/fcc-server --version
CH /root/.local/bin/fcc-claude --help >/dev/null 2>&1 || true
CH bash -lc 'command -v claude && claude --version'

# Pré-compila o bytecode Python: sem isso, a 1ª inicialização do FCC Server no celular
# (proot é lento) pode passar de 1 minuto só compilando .py.
echo "== Pré-compilando bytecode Python"
CH bash -lc 'PY=$(ls /root/.local/share/uv/tools/*/bin/python 2>/dev/null | head -1); [ -n "$PY" ] || exit 0; for t in /root/.local/share/uv/tools /root/.local/share/uv/python; do "$PY" -m compileall -q -j 0 "$t" >/dev/null 2>&1 || true; done' || true

# Teste de fumaça: o servidor sobe e responde em /health? (apenas informativo, não falha o build)
echo "== Teste do FCC Server (emulado)"
CH bash -lc 'nohup fcc-server --auto-started >/tmp/srv.log 2>&1 & pid=$!; for i in $(seq 1 300); do if curl -fsS -m 2 -o /dev/null http://127.0.0.1:8082/health; then echo "HEALTH OK apos ${i}s"; break; fi; kill -0 $pid 2>/dev/null || { echo "servidor encerrou"; break; }; sleep 1; done; tail -n 30 /tmp/srv.log; pkill -f fcc-server; pkill -f free_claude_code; sleep 2' || true

# Limpeza para reduzir o tamanho
CH apt-get clean
sudo rm -rf "$R"/var/lib/apt/lists/* "$R"/root/.cache "$R"/tmp/* "$R"/var/log/*.log "$R"/usr/bin/qemu-*-static
cleanup; trap - EXIT

sudo tar --one-file-system -C "$R" -cf - . | gzip -6 > "$ASSETS/rootfs.tar.gz"
ls -lh "$ASSETS/rootfs.tar.gz" "$JNI"
