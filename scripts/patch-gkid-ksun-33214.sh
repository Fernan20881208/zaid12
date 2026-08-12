#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-gkid}"
BUILD_SH="$ROOT/build.sh"
KSUN_COMMIT="e7536f02c4e5bb247239264b99c00d21d6923b2f"
KSUN_SUSFS_COMMIT="ecb649019c19fa884f3b293fe3e847aaf804d0c2"
SPOOF_VERSION="33214"
BUILD_REVISION="3"

if [[ ! -f "$BUILD_SH" ]]; then
  echo "ERROR: no se encontró $BUILD_SH" >&2
  exit 1
fi

python3 - "$BUILD_SH" "$KSUN_COMMIT" "$KSUN_SUSFS_COMMIT" "$SPOOF_VERSION" <<'PY'
from pathlib import Path
import sys

build_path = Path(sys.argv[1])
ksun_commit = sys.argv[2]
ksun_susfs_commit = sys.argv[3]
spoof_version = sys.argv[4]
text = build_path.read_text(encoding="utf-8")

old_normal = 'install_ksu "KernelSU-Next/KernelSU-Next" "dev"'
new_normal = f'install_ksu "KernelSU-Next/KernelSU-Next" "{ksun_commit}"'
old_susfs = 'install_ksu "pershoot/KernelSU-Next" "dev-susfs"'
new_susfs = f'install_ksu "pershoot/KernelSU-Next" "{ksun_susfs_commit}"'

if old_normal not in text:
    raise SystemExit("No se encontró la llamada normal de KernelSU Next esperada")
if old_susfs not in text:
    raise SystemExit("No se encontró la llamada KernelSU Next + SUSFS esperada")

text = text.replace(old_normal, new_normal, 1)
text = text.replace(old_susfs, new_susfs, 1)

block_start = text.index('if [ "$KSU" = "KSUN" ]; then')
block_end = text.index('if [ "$KSU_COMPAT" = "true" ]; then', block_start)
block = text[block_start:block_end]

# GKID-NH has an extra trace-hook include in fs/namespace.c. The wrapper's
# compatibility patch places the SUSFS declarations correctly around it. The
# full upstream SUSFS patch contains the same declaration hunk with incompatible
# context, so remove only that one hunk and apply every remaining hunk strictly.
namespace_patch = '    patch -p1 --fuzz=3 < "$KERNEL_PATCHES/susfs/fs_namespace.patch"\n'
compat_prelude = '''    patch -p1 --fuzz=3 < "$KERNEL_PATCHES/susfs/fs_namespace.patch"
    SUSFS_FULL_PATCH="$SUSFS_PATCHES/50_add_susfs_in_${SUSFS_PATCH}.patch"
    SUSFS_COMPAT_PATCH="$WORKDIR/susfs-kernel-gkid-nh.patch"
    python3 - "$SUSFS_FULL_PATCH" "$SUSFS_COMPAT_PATCH" <<'PYSUSFS'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
lines = source.read_text(encoding="utf-8").splitlines(keepends=True)
out = []
removed = []
in_namespace = False
skipping = False

for line in lines:
    if line.startswith("diff --git "):
        if skipping:
            raise SystemExit("El primer hunk de namespace.c terminó de forma inesperada")
        in_namespace = line.startswith("diff --git a/fs/namespace.c b/fs/namespace.c")

    if in_namespace and line.startswith("@@ -32,10 +32,21 @@"):
        if removed:
            raise SystemExit("Se encontró más de un hunk de compatibilidad namespace.c")
        skipping = True
        removed.append(line)
        continue

    if skipping:
        if line.startswith("@@ "):
            skipping = False
            out.append(line)
        elif line.startswith("diff --git "):
            raise SystemExit("No se encontró el siguiente hunk de namespace.c")
        else:
            removed.append(line)
        continue

    out.append(line)

removed_text = "".join(removed)
required = (
    "#include <linux/susfs_def.h>",
    "extern bool susfs_is_current_ksu_domain(void);",
    "#define CL_COPY_MNT_NS BIT(25)",
)
if not removed or not all(marker in removed_text for marker in required):
    raise SystemExit("El hunk namespace.c de SUSFS no coincide con la compatibilidad esperada")

result = "".join(out)
if result == source.read_text(encoding="utf-8"):
    raise SystemExit("No se modificó el parche SUSFS")
target.write_text(result, encoding="utf-8")
print("Removed one incompatible duplicate namespace.c hunk from SUSFS patch")
PYSUSFS
'''
if block.count(namespace_patch) != 1:
    raise SystemExit("No se encontró exactamente un parche namespace de compatibilidad en KSUN")
block = block.replace(namespace_patch, compat_prelude, 1)

soft_patch = '    patch -p1 --fuzz=3 < $SUSFS_PATCHES/50_add_susfs_in_${SUSFS_PATCH}.patch || echo "Common kernel SUSFS patch failed."\n'
strict_patch = '''    if ! patch -p1 --fuzz=3 < "$SUSFS_COMPAT_PATCH"; then
      echo "ERROR: el parche SUSFS compatible con GKID-NH no se aplicó limpiamente" >&2
      find . -type f -name '*.rej' -print >&2
      exit 1
    fi
    if find . -type f -name '*.rej' -print -quit | grep -q .; then
      echo "ERROR: quedaron archivos .rej después de aplicar SUSFS" >&2
      find . -type f -name '*.rej' -print >&2
      exit 1
    fi
'''
if block.count(soft_patch) != 1:
    raise SystemExit("No se encontró exactamente una aplicación suave del parche SUSFS en KSUN")
block = block.replace(soft_patch, strict_patch, 1)

text = text[:block_start] + block + text[block_end:]

anchor = f'''    {new_normal}
  fi

  if susfs_included; then
'''
insert = f'''    {new_normal}
  fi

  log "Forcing KernelSU-Next reported version to {spoof_version}"
  KSU_KBUILD="$KSRC/KernelSU-Next/kernel/Kbuild"
  test -f "$KSU_KBUILD" || error "KernelSU-Next Kbuild not found: $KSU_KBUILD"
  python3 - "$KSU_KBUILD" <<'PYKSUN'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
normal = '$(eval KSU_VERSION=$(shell expr 30000 + $(KSU_GIT_VERSION)))'
fallback = 'KSU_VERSION_FALLBACK := 1'
if normal not in text:
    raise SystemExit('No se encontró el cálculo normal de KSU_VERSION')
if fallback not in text:
    raise SystemExit('No se encontró KSU_VERSION_FALLBACK')
text = text.replace(normal, 'KSU_VERSION := {spoof_version}', 1)
text = text.replace(fallback, 'KSU_VERSION_FALLBACK := {spoof_version}', 1)
path.write_text(text, encoding='utf-8')
PYKSUN
  grep -Fq 'KSU_VERSION := {spoof_version}' "$KSU_KBUILD" || error "Failed to spoof normal KSUN version"
  grep -Fq 'KSU_VERSION_FALLBACK := {spoof_version}' "$KSU_KBUILD" || error "Failed to spoof fallback KSUN version"
  git -C "$KSRC/KernelSU-Next" rev-parse HEAD | sed 's/^/KernelSU-Next source commit: /'

  if susfs_included; then
'''

if anchor not in text:
    raise SystemExit("No se encontró el punto de inserción KSUN esperado en build.sh")
text = text.replace(anchor, insert, 1)
build_path.write_text(text, encoding="utf-8")
PY

echo "GKID patch revision $BUILD_REVISION: KSUN normal=$KSUN_COMMIT, KSUN+SUSFS=$KSUN_SUSFS_COMMIT, reported=$SPOOF_VERSION"
grep -nE 'KernelSU-Next/KernelSU-Next|pershoot/KernelSU-Next|Forcing KernelSU-Next|SUSFS_COMPAT_PATCH' "$BUILD_SH"
