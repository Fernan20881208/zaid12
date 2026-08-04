#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-gkid}"
BUILD_SH="$ROOT/build.sh"
KSUN_COMMIT="e7536f02c4e5bb247239264b99c00d21d6923b2f"
SPOOF_VERSION="33214"

if [[ ! -f "$BUILD_SH" ]]; then
  echo "ERROR: no se encontró $BUILD_SH" >&2
  exit 1
fi

python3 - "$BUILD_SH" "$KSUN_COMMIT" "$SPOOF_VERSION" <<'PY'
from pathlib import Path
import sys

build_path = Path(sys.argv[1])
ksun_commit = sys.argv[2]
spoof_version = sys.argv[3]
text = build_path.read_text(encoding="utf-8")

old_install = 'install_ksu "KernelSU-Next/KernelSU-Next" "dev"'
new_install = f'install_ksu "KernelSU-Next/KernelSU-Next" "{ksun_commit}"'
if old_install not in text:
    raise SystemExit("No se encontró la llamada KSUN esperada en build.sh; upstream cambió")
text = text.replace(old_install, new_install, 1)

anchor = f'''    {new_install}
  fi

  if susfs_included; then
'''
insert = f'''    {new_install}
  fi

  log "Forcing KernelSU-Next reported version to {spoof_version}"
  KSU_KBUILD="$KSRC/KernelSU-Next/kernel/Kbuild"
  test -f "$KSU_KBUILD" || error "KernelSU-Next Kbuild not found: $KSU_KBUILD"
  sed -i 's|$(eval KSU_VERSION=$(shell expr 30000 + $(KSU_GIT_VERSION)))|KSU_VERSION := {spoof_version}|' "$KSU_KBUILD"
  sed -i 's|KSU_VERSION_FALLBACK := 1|KSU_VERSION_FALLBACK := {spoof_version}|' "$KSU_KBUILD"
  grep -Fq 'KSU_VERSION := {spoof_version}' "$KSU_KBUILD" || error "Failed to spoof normal KSUN version"
  grep -Fq 'KSU_VERSION_FALLBACK := {spoof_version}' "$KSU_KBUILD" || error "Failed to spoof fallback KSUN version"

  if susfs_included; then
'''
if anchor not in text:
    raise SystemExit("No se encontró el punto de inserción KSUN esperado en build.sh")
text = text.replace(anchor, insert, 1)
build_path.write_text(text, encoding="utf-8")
PY

echo "GKID patched: KernelSU-Next commit=$KSUN_COMMIT, reported version=$SPOOF_VERSION"
grep -nE 'KernelSU-Next/KernelSU-Next|KSU_VERSION := 33214|KSU_VERSION_FALLBACK := 33214' "$BUILD_SH"
