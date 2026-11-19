#!/usr/bin/env bash
# vendor-pi-bundle.sh — T-ML45 Fase 0 (PoC Pi Coding Agent ke APK)
# Mengemas ulang bundle RPC Pi (@earendil-works/pi-coding-agent, PINNED) menjadi
# app/src/main/assets/pi/pi-bundle.tar.xz — arsip arch-neutral (murni JS) yang
# diekstrak RuntimeController ke /opt/sachet/pi-bundle di rootfs.
#
# Isi tarball: bundle/ (rpc-entry.js + cli-runtime.js + chunks/, hasil build
# resmi upstream — self-contained, TANPA node_modules; smoke test
# scripts/pi-rpc-smoke.js membuktikan loop penuh prompt→tool→jawaban) +
# package.json (identitas versi untuk `pi --version`).
#
# Pembaruan versi: ganti PI_VERSION di bawah, jalankan skrip ini, commit
# tarball + PI-BUNDLE.sha256 hasilnya. JANGAN ambil versi "latest" tanpa pin.
set -euo pipefail

PI_VERSION="1.5.2"
PI_PACKAGE="@earendil-works/pi-coding-agent@${PI_VERSION}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"          # coding-sachet/
OUT_DIR="${REPO}/app/src/main/assets/pi"
OUT_TAR="${OUT_DIR}/pi-bundle.tar.xz"
OUT_SHA="${OUT_DIR}/PI-BUNDLE.sha256"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "[vendor] mengunduh ${PI_PACKAGE} ke ${WORK}"
cd "${WORK}"
npm pack "${PI_PACKAGE}" --silent > /dev/null
tar -xzf earendil-works-pi-coding-agent-*.tgz

# Ambil HANYA bagian yang dipakai mode RPC headless. node_modules sengaja
# dibuang (bundle self-contained); docs/examples/schemas dibuang (bukan
# runtime); wasm (photon/quickjs) tidak ikut — hanya lazily-import untuk
# input gambar / codemode, dua fitur di luar cakupan PoC teks.
STAGE="${WORK}/stage"
mkdir -p "${STAGE}"
cp -r package/dist/bundle "${STAGE}/bundle"
cp package/package.json "${STAGE}/package.json"

echo "[vendor] memeriksa isi minimum"
test -f "${STAGE}/bundle/rpc-entry.js" || { echo "FATAL: rpc-entry.js hilang" >&2; exit 1; }
test -d "${STAGE}/bundle/chunks" || { echo "FATAL: chunks/ hilang" >&2; exit 1; }
test "$(node "${STAGE}/bundle/rpc-entry.js" --version 2>/dev/null)" = "${PI_VERSION}" \
  || { echo "FATAL: --version tidak cocok ${PI_VERSION}" >&2; exit 1; }

mkdir -p "${OUT_DIR}"
tar -C "${STAGE}" -cJf "${OUT_TAR}" bundle package.json
(cd "${OUT_DIR}" && sha256sum pi-bundle.tar.xz > PI-BUNDLE.sha256)

echo "[vendor] ${PI_PACKAGE} → ${OUT_TAR}"
ls -la "${OUT_TAR}"
echo "[vendor] sha256: $(cut -d' ' -f1 < "${OUT_SHA}")"
echo "[vendor] selesai — commit kedua file ini ke repo"
