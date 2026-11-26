#!/usr/bin/env bash
# vendor-opencode.sh — vendor biner opencode RESMI (sst/opencode, MIT, versi di-pin)
# + custom patch evil (fitur evil-opencode) yang membuatnya bisa dieksekusi di
# lingkungan Android W^X, lalu mengemasnya untuk assets APK.
#
# PATCH EVIL (reproducible, 4 byte): segmen ELF PT_GNU_STACK di-flip dari RW
# ke RX (PF_X diset). GNU_STACK executable membuat kernel menandai stack proses
# executable — di Termux/app targetSdk>=29 itu ditolak W^X kecuali biner
# "dinonaktifkan" execstack-nya (itulah yang dilakukan binary-patch evil-
# opencode). Untuk coding-sachet (targetSdk 28 + proot) biner resmi TIDAK
# BUTUH patch ini agar jalan — terbukti empiris (lihat AUDIT-OPENCODE.md §1
# uji #3) — patch dipasang sebagai asuransi OEM-seccomp & kelanjutan targetSdk.
# Tidak ada byte lain yang diubah; artefak resmi diverifikasi SHA256SUMS
# SEBELUM patch, dan smoke test --version DISETELAH patch.
#
# Pemakaian:
#   scripts/vendor-opencode.sh [arm64-v8a|x86_64]
# Env:
#   OC_VERSION            (default 2.2.0 — versi yang dipatch evil-opencode)
#   SACHET_OC_TARBALL     path tarball yang sudah terunduh (cache; tetap
#                         diverifikasi terhadap SHA256SUMS)
#   SACHET_OC_SKIP_SMOKE  =1 lewati smoke test eksekusi (host non-linux-arm64)
set -euo pipefail

OC_VERSION="${OC_VERSION:-2.2.0}"
ABI="${1:-arm64-v8a}"
case "$ABI" in
  arm64-v8a) OC_TARGET="linux-arm64" ;;
  x86_64)    OC_TARGET="linux-x64" ;;
  *) echo "FATAL: opencode resmi hanya menyediakan linux-arm64/linux-x64 —" \
          "ABI '$ABI' (mis. armeabi-v7a) tidak tersedia; APK ABI itu tetap" \
          "pakai engine legacy/Pi." >&2; exit 1 ;;
esac

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${REPO}/app/src/main/assets/opencode"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

TAR_NAME="opencode-${OC_TARGET}.tar.gz"
TAR_URL="https://github.com/sst/opencode/releases/download/v${OC_VERSION}/${TAR_NAME}"
SUMS_URL="https://github.com/sst/opencode/releases/download/v${OC_VERSION}/SHA256SUMS"

echo "[vendor] mengunduh manifest SHA256SUMS (v${OC_VERSION})"
if [ -n "${SACHET_OC_TARBALL:-}" ] && [ -f "${SACHET_OC_TARBALL}" ]; then
  cp "${SACHET_OC_TARBALL}" "${WORK}/${TAR_NAME}"
  echo "[vendor] pakai cache: ${SACHET_OC_TARBALL}"
  node -e '
const fs=require("fs");
(async()=>{
  let txt=null;
  for(let a=1;a<=3;a++){
    try{const r=await fetch(process.argv[1],{redirect:"follow",signal:AbortSignal.timeout(180000)});
      if(!r.ok)throw new Error("HTTP "+r.status); txt=await r.text(); break;}
    catch(e){console.error("[vendor] manifest attempt "+a+" gagal: "+String(e).slice(0,80));}
  }
  if(!txt){console.error("FATAL: manifest gagal diunduh");process.exit(1);}
  fs.writeFileSync(process.argv[2], txt);
})();' "$SUMS_URL" "${WORK}/SHA256SUMS"
else
  node -e '
const fs=require("fs");
(async()=>{
  const [r1,r2]=await Promise.all([
    fetch(process.argv[1],{redirect:"follow",signal:AbortSignal.timeout(600000)}),
    fetch(process.argv[2],{redirect:"follow",signal:AbortSignal.timeout(180000)}),
  ]);
  if(!r1.ok||!r2.ok){console.error("FATAL: HTTP",r1.status,r2.status);process.exit(1);}
  const out=fs.createWriteStream(process.argv[3]);
  for await(const ch of r1.body) out.write(ch);
  out.end();
  fs.writeFileSync(process.argv[4], await r2.text());
})();' "$TAR_URL" "$SUMS_URL" "${WORK}/${TAR_NAME}" "${WORK}/SHA256SUMS"
fi

echo "[vendor] verifikasi checksum artefak resmi"
EXPECT=$(grep " ${TAR_NAME}\$" "${WORK}/SHA256SUMS" | awk '{print $1}')
ACTUAL=$(sha256sum "${WORK}/${TAR_NAME}" | awk '{print $1}')
[ -n "$EXPECT" ] || { echo "FATAL: $TAR_NAME tidak ada di SHA256SUMS" >&2; exit 1; }
[ "$EXPECT" = "$ACTUAL" ] || { echo "FATAL: checksum tidak cocok ($ACTUAL != $EXPECT)" >&2; exit 1; }
echo "[vendor] checksum cocok: $EXPECT"

tar -xzf "${WORK}/${TAR_NAME}" -C "${WORK}"
BIN="${WORK}/opencode"
test -f "$BIN" || { echo "FATAL: biner 'opencode' tidak ada di tarball" >&2; exit 1; }

echo "[vendor] patch evil (PT_GNU_STACK RW → RX)"
node -e '
const fs=require("fs");
const p=process.argv[1];
const b=fs.readFileSync(p);
const dv=new DataView(b.buffer,b.byteOffset,b.byteLength);
if(dv.getUint32(0,true)!==0x464c457f){console.error("FATAL: bukan ELF");process.exit(1);}
if(dv.getUint8(4)!==2||dv.getUint16(18,true)!==183){console.error("FATAL: bukan ELF64 aarch64");process.exit(1);}
const phoff=Number(dv.getBigUint64(32,true));
const phentsize=dv.getUint16(54,true), phnum=dv.getUint16(56,true);
const PF_X=1,PF_W=2,PF_R=4;
let idx=-1;
for(let i=0;i<phnum;i++){
  const o=phoff+i*phentsize;
  if(dv.getUint32(o,true)===0x6474e551){idx=o;break;} // PT_GNU_STACK
}
if(idx<0){console.error("FATAL: PT_GNU_STACK tidak ditemukan");process.exit(1);}
const f0=dv.getUint32(idx+4,true);
if(f0&PF_X){console.log("[patch] GNU_STACK sudah RX (flags="+f0+") — tanpa perubahan");process.exit(0);}
if(f0!==(PF_W|PF_R)){console.error("FATAL: GNU_STACK flags tak terduga: "+f0);process.exit(1);}
dv.setUint32(idx+4,PF_X|PF_R,true); // RW → RX: satu-satunya byte yang berubah
fs.writeFileSync(p,b);
console.log("[patch] GNU_STACK: RW(6) → RX(5) @ phdr offset "+idx+" — selesai");
' "$BIN"

echo "[vendor] smoke test --version (biner hasil patch)"
if [ "${SACHET_OC_SKIP_SMOKE:-0}" = "1" ]; then
  echo "[vendor] smoke dilewati (SACHET_OC_SKIP_SMOKE=1)"
elif "$BIN" --version 2>/dev/null | grep -qx "opencode v${OC_VERSION}"; then
  echo "[vendor] smoke OK: opencode v${OC_VERSION} jalan setelah patch"
else
  echo "FATAL: biner hasil patch tidak menjawab --version dengan benar" >&2
  exit 1
fi

echo "[vendor] hapus guardrails: tulis opencode-config.json (permission=allow)\n"
# evil-opencode 'Hapus Guardrails': biner v2.2.0 TIDAK membaca env OPENCODE_PERMISSION
# (string tidak ada di biner); guardrail dikendalikan field config `permission`.
# Config ini membuat semua tool auto-allow (tanpa prompt/deny) dan diverifikasi
# boot bersih + POST /api/session -> 200 (AUDIT-OPENCODE.md §9). Runtime inject
# via OPENCODE_CONFIG_DIR=<dir berisi file ini>.
cat > "${OUT_DIR}/opencode-config.json" <<'JSON'
{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "bash": "allow", "edit": "allow", "write": "allow", "read": "allow",
    "list": "allow", "glob": "allow", "grep": "allow", "task": "allow",
    "webfetch": "allow", "todowrite": "allow", "todoread": "allow"
  }
}
JSON
echo "[vendor] guardrails removed: permission=allow (11 tool) -> assets/opencode/opencode-config.json"

mkdir -p "${OUT_DIR}"
# Kompres: xz bila tersedia (dekode xz sudah ditangani app untuk rootfs),
# fallback gzip -9. Format tercantum eksplisit di nama file.
STAMP="$(date -u +%Y-%m-%d)"
if command -v xz >/dev/null 2>&1; then
  OUT_TAR="${OUT_DIR}/opencode-${OC_TARGET}-${OC_VERSION}.xz"
  tar -C "${WORK}" -cf - opencode | xz -9e -T0 > "${OUT_TAR}"
else
  OUT_TAR="${OUT_DIR}/opencode-${OC_TARGET}-${OC_VERSION}.gz"
  tar -C "${WORK}" -czf "${OUT_TAR}" opencode
fi
(cat <<EOF
# opencode vendor (custom-patch evil GNU_STACK RX)
source: https://github.com/sst/opencode/releases/tag/v${OC_VERSION}
tarball: ${TAR_NAME}
sha256 (tarball resmi, pra-patch): ${ACTUAL}
patch: PT_GNU_STACK PF_W|PF_R -> PF_X|PF_R (evil-opencode W^X fitur, 4 byte)
smoke: opencode --version == "opencode v${OC_VERSION}" PASCA-patch
archived: ${STAMP}
EOF
) > "${OUT_DIR}/OPENCODE-PATCH.sha256"
(cd "${OUT_DIR}" && sha256sum "$(basename "${OUT_TAR}")") >> "${OUT_DIR}/OPENCODE-PATCH.sha256"

echo "[vendor] selesai: ${OUT_TAR} ($(du -h "${OUT_TAR}" | cut -f1))"
