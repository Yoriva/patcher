#!/usr/bin/env bash
set -euo pipefail

SBIN=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SBIN/../.." && pwd)

SRC=$1
NDK=$2
OUT=$3
PLUGINS_SRC=${4:-}
ABI=${5:-arm64-v8a}
SELECTION=${6:-}

if [ -n "$SELECTION" ]; then
  NORMALIZED=""
  IFS=',' read -ra REQUESTED <<< "$(echo "$SELECTION" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  for item in "${REQUESTED[@]}"; do
    case "$item" in
      ''|all) continue ;;
      libmenu|menu) item=menu ;;
      libclient|client) item=client ;;
      libamxmodx|amxxmodx|core) item=amxxmodx ;;
      libmetadroid|metadroid|meta) item=metadroid ;;
      libcs|cs|regamedll|gamedll) item=regamedll ;;
      libyapb|yapb) item=yapb ;;
      libreapi|reapi) item=reapi ;;
      amxxpc|compiler) item=compiler ;;
      engine|fakemeta|fun|geoip|json|nvault|regex|sockets|sqlite|cstrike|csx|hamsandwich) item=modules ;;
    esac
    case ",$NORMALIZED," in
      *",$item,"*) ;;
      *) NORMALIZED=${NORMALIZED:+$NORMALIZED,}$item ;;
    esac
  done
  SELECTION=$NORMALIZED
fi

want() {
  local key=$1
  [ -z "$SELECTION" ] && return 0
  case ",$SELECTION," in
    *",$key,"*) return 0 ;;
  esac
  return 1
}

want_amxx() { want amxxmodx || want modules || want compiler || want plugins; }
want_regex() { want regex || want modules; }

echo "== lib selection: ${SELECTION:-all} =="

mkdir -p "$SRC" "$OUT/lib/$ABI" "$OUT/plugins"

fetch() {
  local name=$1 url=$2 recurse=${3:-}
  if [ ! -d "$SRC/$name/.git" ]; then
    echo "== fetching $name =="
    if [ "$recurse" = yes ]; then
      git clone -q --depth 1 --recurse-submodules --shallow-submodules "$url" "$SRC/$name"
    else
      git clone -q --depth 1 "$url" "$SRC/$name"
    fi
    if [ -f "$SRC/$name/.gitmodules" ] && [ "$recurse" != yes ]; then
      true # extra submodules (eg. hlsdk vgui_support) are not needed
    fi
  fi
}

if want reapi || want modules; then
  fetch reapi "https://github.com/rehlds/ReAPI.git" yes
fi

if want_amxx; then
  AMXX_REF=nexora
  if [ "$ABI" = "arm64-v8a" ]; then
    AMXX_REF=nexora-cell64
  fi
  echo "== amxmodx: $AMXX_REF =="
  git -C "$REPO_ROOT/3rdparty/amxmodx" fetch -q --depth 1 origin \
    "+refs/heads/$AMXX_REF:refs/remotes/origin/$AMXX_REF"
  git -C "$REPO_ROOT/3rdparty/amxmodx" checkout -q -B "$AMXX_REF" "refs/remotes/origin/$AMXX_REF"
  git -C "$REPO_ROOT/3rdparty/amxmodx" submodule update --init --recursive --depth 1 2>&1 | tail -2 || true
fi


HOST=$(uname -s | tr 'A-Z' 'a-z')
if [ "$HOST" = darwin ]; then HOST=mac; fi

TC=$NDK/toolchains/llvm/prebuilt/$HOST-x86_64/bin
case "$ABI" in
  arm64-v8a)
    TARGET=aarch64-linux-android24
    SYSROOT_ARCH=aarch64-linux-android
    PCRE_HOST=aarch64-linux-android
    RUNTIME_SUFFIX=arm64
    MOD_SUFFIX=amd64
    CELL=64
    ;;
  armeabi-v7a)
    TARGET=armv7a-linux-androideabi24
    SYSROOT_ARCH=arm-linux-androideabi
    PCRE_HOST=arm-linux-androideabi
    RUNTIME_SUFFIX=armv7l
    MOD_SUFFIX=arm
    CELL=32
    ;;
  *)
    echo "unsupported ABI: $ABI (expected arm64-v8a or armeabi-v7a)" >&2
    exit 1
    ;;
esac
CC=$TC/$TARGET-clang
CXX=$TC/$TARGET-clang++
HOSTCC=${HOSTCC:-gcc}
HOSTCXX=${HOSTCXX:-g++}
SYSROOT_LIB=$NDK/toolchains/llvm/prebuilt/$HOST-x86_64/sysroot/usr/lib/$SYSROOT_ARCH

AMXX=$REPO_ROOT/3rdparty/amxmodx
HLSDK=$REPO_ROOT/3rdparty/hlsdk
YAPB=$REPO_ROOT/3rdparty/yapb
METAMOD_R=$REPO_ROOT/3rdparty/metadroid

SCRIPT_FOLDER="${SCRIPT_FOLDER:-$AMXX/scripting}"
LOGS_DIR="$SCRIPT_FOLDER/logs"
COMPILER_LOG="$LOGS_DIR/compiler.log"
ERROR_LOG="$LOGS_DIR/error.log"

if want_amxx && [ -f "$AMXX/modules/hamsandwich/Trampolines.h" ]; then
  TH="$AMXX/modules/hamsandwich/Trampolines.h"
  if ! grep -q 'intptr_t' "$TH"; then
    echo "   patching Trampolines.h for 64-bit pointer cast"
    python3 -c "
import sys
p = sys.argv[1]
d = open(p).read()
d = d.replace('reinterpret_cast<int>(extraptr)', 'reinterpret_cast<intptr_t>(extraptr)')
open(p,'w').write(d)
print('   Trampolines.h patched for ARM64')
" "$TH"
  else
    echo "   Trampolines.h already patched"
  fi
fi

DEFS=(
  -Dstricmp=strcasecmp
  -Dstrnicmp=strncasecmp
  -DAMX_NOPROPLIST
  -DPAWN_CELL_SIZE=$CELL
  -DAMXX_USE_VERSIONLIB
  -DHAVE_STDINT_H
  -DHAVE_I64
  -D_snprintf=snprintf
  -D__BYTE_ORDER=__LITTLE_ENDIAN
  -D_GNU_SOURCE
)
FLAGS=(
  -fPIC -O2 -g -fno-strict-aliasing -Wall -Wno-uninitialized -Wno-unused
  -Wno-switch -Wno-format -Wno-format-security -fsigned-char -fvisibility=hidden
)
CXXFLAGS=(
  -Wno-narrowing -Wno-invalid-offsetof -std=c++14 -fvisibility-inlines-hidden
  -Wno-delete-non-virtual-dtor -Wno-implicit-exception-spec-mismatch
  -Wno-tautological-compare -Wno-deprecated-register -fno-exceptions -fno-rtti
)
INC=(
  -I"$AMXX/public" -I"$AMXX/public/sdk" -I"$AMXX/public/amtl"
  -I"$AMXX/public/memtools" -I"$AMXX/public/resdk"
  -I"$AMXX/third_party" -I"$AMXX/third_party/hashing" -I"$AMXX/third_party/zlib"
  -I"$AMXX/third_party/sqlite"   -I"$AMXX/third_party/utf8rewind"
  -I"$AMXX/amxmodx"
  -I"$METAMOD_R/metamod/src"
  -I"$HLSDK/common" -I"$HLSDK/dlls" -I"$HLSDK/engine"
  -I"$HLSDK/game_shared" -I"$HLSDK/public" -I"$HLSDK/pm_shared"
)

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cmd_shim="$TMP/assert_shim.o"
"$CC" -O2 -fPIC -c "$SBIN/assert_shim.c" -o "$cmd_shim"
if [ -n "${CCACHE:-}" ] && command -v "$CCACHE" >/dev/null 2>&1; then
  mkdir -p "$TMP/ccache-wrap"
  printf '#!/bin/sh\nexec "%s" "$REAL_CC" "$@"\n' "$CCACHE" > "$TMP/ccache-wrap/cc"
  printf '#!/bin/sh\nexec "%s" "$REAL_CXX" "$@"\n' "$CCACHE" > "$TMP/ccache-wrap/cxx"
  chmod +x "$TMP/ccache-wrap/cc" "$TMP/ccache-wrap/cxx"
  export REAL_CC=$CC REAL_CXX=$CXX
  CC=$TMP/ccache-wrap/cc
  CXX=$TMP/ccache-wrap/cxx
  echo "   ccache enabled via $CCACHE"
fi

compile_one() {
  local stage="$1" file="$2" extra_inc="$3" extra_defs="$4"
  local base obj
  base=$(basename "$file")
  obj="$TMP/$stage/$base.o"
  mkdir -p "$(dirname "$obj")"
  if [[ "$file" == *.c ]]; then
    "$CC" "${FLAGS[@]}" "${INC[@]}" $extra_inc "${DEFS[@]}" $extra_defs -c "$file" -o "$obj"
  else
    "$CXX" "${FLAGS[@]}" "${CXXFLAGS[@]}" "${INC[@]}" $extra_inc "${DEFS[@]}" $extra_defs -c "$file" -o "$obj"
  fi
}

relink() {
  local out="$1"; shift
  "$CXX" -fPIC -O2 -shared -nostdlib++ -o "$out" "$@" "$cmd_shim" \
    -Wl,--wrap=__assert2 -Wl,--wrap=__assert_fail \
    -Wl,-z,max-page-size=16384 \
    -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
    "$SYSROOT_LIB/libc++abi.a" -ldl -lm -pthread
}

if want amxxmodx; then
echo "== building core =="

NATIVES_FILE="$AMXX/amxmodx/natives-android.c"
if [ "$ABI" = arm64-v8a ]; then
cat > "$NATIVES_FILE" << 'NATIVES_EOF'
#include <stdint.h>
#include <string.h>

static void *g_gate = 0;

void amxx_DynaInit(void *ptr) {
    g_gate = ptr;
}

int amxx_DynaCodesize(void) {
    return 52;
}

typedef int (*dyna_cb_t)(int, void*, void*);

/* ARM64 trampoline (52 bytes):
 *   stp  x29, x30, [sp, #-16]!
 *   mov  x29, sp
 *   mov  x2, x1          ; params -> arg3
 *   mov  x1, x0          ; amx    -> arg2
 *   movz x0,  #id_lo16   ; patched
 *   movk x0,  #id_hi16, lsl #16
 *   movz x16, #cb_lo16   ; patched
 *   movk x16, #cb_16,  lsl #16
 *   movk x16, #cb_32,  lsl #32
 *   movk x16, #cb_48,  lsl #48
 *   blr  x16
 *   ldp  x29, x30, [sp], #16
 *   ret
 */
static const uint32_t tpl[] = {
    0xA9BE7BFD,  /* stp x29,x30,[sp,#-16]!  */
    0x910003FD,  /* mov x29, sp             */
    0xAA0103E2,  /* mov x2, x1              */
    0xAA0003E1,  /* mov x1, x0              */
    0xD2800000,  /* movz x0, #0   (id lo)   */
    0xF2A00000,  /* movk  x0, #0, lsl#16    */
    0xD2800010,  /* movz x16, #0  (cb lo)   */
    0xF2A00010,  /* movk x16,#0, lsl#16     */
    0xF2C00010,  /* movk x16,#0, lsl#32     */
    0xF2E00010,  /* movk x16,#0, lsl#48     */
    0xD63F0200,  /* blr  x16                */
    0xA8C27BFD,  /* ldp x29,x30,[sp],#16    */
    0xD65F03C0,  /* ret                     */
};

void amxx_DynaMake(char *buf, int id) {
    uint32_t code[13];
    memcpy(code, tpl, sizeof(code));
    uintptr_t cb = (uintptr_t)g_gate;
    code[4] |= ((uint32_t)(id & 0xFFFF)) << 5;
    code[5] |= ((uint32_t)((id >> 16) & 0xFFFF)) << 5;
    code[6]  |= ((uint32_t)(cb & 0xFFFF)) << 5;
    code[7]  |= ((uint32_t)((cb >> 16) & 0xFFFF)) << 5;
    code[8]  |= ((uint32_t)((cb >> 32) & 0xFFFF)) << 5;
    code[9]  |= ((uint32_t)((cb >> 48) & 0xFFFF)) << 5;
    memcpy(buf, code, sizeof(code));
    /* ARM64 has a non-coherent instruction cache: flush it, otherwise the
     * CPU may execute stale cache lines (SIGILL) instead of this code. */
    __builtin___clear_cache(buf, buf + sizeof(code));
}

int amxx_CpuSupport(void) {
    return 1;
}
NATIVES_EOF
else
cat > "$NATIVES_FILE" << 'NATIVES_EOF'
#include <stdint.h>
#include <string.h>

static void *g_gate = 0;

void amxx_DynaInit(void *ptr) {
    g_gate = ptr;
}

int amxx_DynaCodesize(void) {
    return 52;
}

/* ARM (A32) trampoline (13 words = 52 bytes, same size as the arm64 one so
 * DynaCodesize stays single-valued). Executed from a Thumb-2 caller via
 * interworking (trampoline address is even -> ARM state):
 *   push {r7, lr}
 *   add  r7, sp, #0
 *   mov  r2, r1            ; params -> arg3
 *   mov  r1, r0            ; amx    -> arg2
 *   movw r0,  #id_lo16     ; patched
 *   movt r0,  #id_hi16
 *   movw r3,  #cb_lo16     ; patched
 *   movt r3,  #cb_hi16
 *   blx  r3                ; gate(id, amx, params)
 *   pop  {r7, pc}
 *   nop x3 (pad to 52 bytes)
 */
static const uint32_t tpl[] = {
    0xE92D4080,  /* push {r7, lr}     */
    0xE28D7000,  /* add  r7, sp, #0   */
    0xE1A02001,  /* mov  r2, r1       */
    0xE1A01000,  /* mov  r1, r0       */
    0xE3000000,  /* movw r0, #0 (id lo) */
    0xE3400000,  /* movt r0, #0 (id hi) */
    0xE3030000,  /* movw r3, #0 (cb lo) */
    0xE3430000,  /* movt r3, #0 (cb hi) */
    0xE12FFF33,  /* blx  r3           */
    0xE8BD8080,  /* pop  {r7, pc}     */
    0xE320F000,  /* nop               */
    0xE320F000,  /* nop               */
    0xE320F000,  /* nop               */
};

/* movw/movt imm16 -> opcode bits [19:16]=imm4, [11:0]=imm12. */
static inline uint32_t movimm(uint32_t opcode, uint32_t imm) {
    return opcode | ((imm & 0xF000u) << 4) | (imm & 0x0FFFu);
}

void amxx_DynaMake(char *buf, int id) {
    uint32_t code[13];
    memcpy(code, tpl, sizeof(code));
    uintptr_t cb = (uintptr_t)g_gate;
    code[4] = movimm(0xE3000000, (uint32_t)(id & 0xFFFF));
    code[5] = movimm(0xE3400000, (uint32_t)((id >> 16) & 0xFFFF));
    code[6] = movimm(0xE3030000, (uint32_t)(cb & 0xFFFF));
    code[7] = movimm(0xE3430000, (uint32_t)((cb >> 16) & 0xFFFF));
    memcpy(buf, code, sizeof(code));
    __builtin___clear_cache(buf, buf + sizeof(code));
}

int amxx_CpuSupport(void) {
    return 1;
}
NATIVES_EOF
fi
echo "   created natives-android.c ($ABI)"

for f in "$AMXX/amxmodx"/*.c "$AMXX/amxmodx"/*.cpp; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done
compile_one core "$AMXX/public/memtools/MemoryUtils.cpp" "" ""
compile_one core "$AMXX/public/memtools/CDetour/detours.cpp" "" ""
compile_one core "$AMXX/public/memtools/CDetour/asm/asm.c" "" ""
compile_one core "$AMXX/public/resdk/mod_rehlds_api.cpp" "" ""
for f in "$AMXX/third_party/hashing/"*.cpp "$AMXX/third_party/hashing/hashers/"*.cpp; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done
for f in "$AMXX/third_party/zlib/"*.c; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done
for f in "$AMXX/third_party/utf8rewind/"*.c "$AMXX/third_party/utf8rewind/internal/"*.c; do
  [ -e "$f" ] || continue
  compile_one core "$f" "" ""
done

relink "$OUT/lib/$ABI/libamxmodx.so" "$TMP"/core/*.o
echo "   core -> $(ls -l "$OUT/lib/$ABI/libamxmodx.so" | awk '{print $5}') bytes"
fi

if want_regex; then
PCRE_VER=8.45
if [[ ! -f "$TMP/libpcre.a" ]]; then
  echo "== building pcre $PCRE_VER ($ABI) =="
  curl -fsSL "https://downloads.sourceforge.net/project/pcre/pcre/$PCRE_VER/pcre-$PCRE_VER.tar.gz" -o "$TMP/pcre.tar.gz"
  tar -xzf "$TMP/pcre.tar.gz" -C "$TMP"
  (
    cd "$TMP/pcre-$PCRE_VER"
    CC="$CC" CXX="$CXX" CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC" \
      ./configure --host=$PCRE_HOST --disable-shared --enable-static \
      --enable-utf8 --enable-unicode-properties --disable-cpp --prefix="$TMP/pcre-inst" \
      >/dev/null
    make -j"$(nproc)" >/dev/null
    make install >/dev/null
  )
fi
PCRE_A="$TMP/pcre-inst/lib/libpcre.a"
fi

if want metadroid; then
echo "== building metadroid ($ABI) =="
MMBUILD=$TMP/metadroid-build
cmake -S "$METAMOD_R" -B "$MMBUILD" \
  -GNinja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$MMBUILD" -j"$(nproc)"
if [ "$ABI" = "arm64-v8a" ]; then
  MM_SO=$(find "$MMBUILD" -name "libmetadroid_android_arm64.so" | head -1)
else
  MM_SO=$(find "$MMBUILD" -name "libmetadroid_android_armv7l.so" | head -1)
fi
if [ -n "$MM_SO" ]; then
  cp "$MM_SO" "$OUT/lib/$ABI/libmetadroid.so"
  echo "   metadroid -> $(ls -l "$OUT/lib/$ABI/libmetadroid.so" | awk '{print $5}') bytes"
else
  echo "WARN: metadroid lib not found, skipping"
fi
fi

build_module() {
  local name="$1" modsub="$2" extra_inc="$3" extra_defs="$4"
  shift 4
  if ! want "$name" && ! want modules; then
    echo "   skipping module $name"
    return 0
  fi
  echo "== building module $name =="
  local M="$AMXX/modules/$modsub"
  local MOD_INC="-I$M"
  for f in "$@"; do
    if [[ "$f" == /* ]]; then
      compile_one "mod-$name" "$f" "$MOD_INC $extra_inc" "$extra_defs"
    else
      compile_one "mod-$name" "$M/$f" "$MOD_INC $extra_inc" "$extra_defs"
    fi
  done
  relink "$OUT/lib/$ABI/lib$name"_amxx_$MOD_SUFFIX.so "$TMP/mod-$name"/*.o
  echo "   $name -> $(ls -l "$OUT/lib/$ABI/lib$name"_amxx_$MOD_SUFFIX.so | awk '{print $5}') bytes"
}

P="$AMXX/public"
SDK="$AMXX/public/sdk"

build_module engine engine "" "" \
  "$SDK/amxxmodule.cpp" "amxxapi.cpp" "engine.cpp" "entity.cpp" "globals.cpp" \
  "forwards.cpp" "$P/memtools/MemoryUtils.cpp" "$P/memtools/CDetour/detours.cpp" \
  "$P/memtools/CDetour/asm/asm.c"

build_module fakemeta fakemeta "" "" \
  "$SDK/amxxmodule.cpp" "$P/memtools/MemoryUtils.cpp" "$P/resdk/mod_regamedll_api.cpp" \
  "dllfunc.cpp" "engfunc.cpp" "fakemeta_amxx.cpp" "pdata.cpp" "pdata_entities.cpp" \
  "pdata_gamerules.cpp" "forward.cpp" "fm_tr.cpp" "pev.cpp" "glb.cpp" "fm_tr2.cpp" "misc.cpp"

build_module fun fun "" "" \
  "$SDK/amxxmodule.cpp" "$P/memtools/MemoryUtils.cpp" "fun.cpp"

build_module geoip geoip \
  "-I$AMXX/amxmodx -I$AMXX/third_party/libmaxminddb" "" \
  "$SDK/amxxmodule.cpp" "$AMXX/third_party/libmaxminddb/data-pool.c" \
  "$AMXX/third_party/libmaxminddb/maxminddb.c" "geoip_main.cpp" "geoip_natives.cpp" \
  "geoip_util.cpp"

build_module json json "-I$AMXX/third_party/parson" "" \
  "$SDK/amxxmodule.cpp" "$AMXX/third_party/parson/parson.c" "JsonMngr.cpp" "JsonNatives.cpp"

build_module nvault nvault "" "" \
  "$SDK/amxxmodule.cpp" "amxxapi.cpp" "Binary.cpp" "Journal.cpp" "NVault.cpp"

build_module regex regex "" "-DPCRE_STATIC" \
  "$SDK/amxxmodule.cpp" "module.cpp" "CRegEx.cpp" "utils.cpp"

build_module sockets sockets "" "" \
  "$SDK/amxxmodule.cpp" "sockets.cpp"

build_module sqlite sqlite \
  "-I$AMXX/modules/sqlite/sqlitepp -I$AMXX/modules/sqlite/thread -I$AMXX/third_party/sqlite" \
  "-DSM_DEFAULT_THREADER" \
  "basic_sql.cpp" "handles.cpp" "module.cpp" "threading.cpp" "$SDK/amxxmodule.cpp" \
  "oldcompat_sql.cpp" "thread/BaseWorker.cpp" "thread/ThreadWorker.cpp" \
  "sqlitepp/SqliteQuery.cpp" "sqlitepp/SqliteResultSet.cpp" \
  "sqlitepp/SqliteDatabase.cpp" "sqlitepp/SqliteDriver.cpp" \
  "$AMXX/third_party/sqlite/sqlite3.c"

build_module cstrike cstrike/cstrike "" "" \
  "$SDK/amxxmodule.cpp" "CstrikeMain.cpp" "CstrikePlayer.cpp" "CstrikeNatives.cpp" \
  "CstrikeHacks.cpp" "CstrikeUtils.cpp" "CstrikeUserMessages.cpp" "CstrikeItemsInfos.cpp" \
  "$P/memtools/MemoryUtils.cpp" "$P/memtools/CDetour/detours.cpp" \
  "$P/memtools/CDetour/asm/asm.c" "$P/resdk/mod_rehlds_api.cpp" "$P/resdk/mod_regamedll_api.cpp"

build_module csx cstrike/csx "" "" \
  "$SDK/amxxmodule.cpp" "CRank.cpp" "CMisc.cpp" "meta_api.cpp" "rank.cpp" "usermsg.cpp"

build_module hamsandwich hamsandwich "" "-DHAVE_STDINT_H" \
  "$SDK/amxxmodule.cpp" "$P/memtools/MemoryUtils.cpp" \
  "amxx_api.cpp" "config_parser.cpp" "hook_callbacks.cpp" "hook_native.cpp" \
  "srvcmd.cpp" "call_funcs.cpp" "hook_create.cpp" "DataHandler.cpp" \
  "pdata.cpp" "hook_specialbot.cpp"

if want_regex; then
"$CXX" -fPIC -O2 -shared -nostdlib++ -o "$OUT/lib/$ABI/libregex_amxx_$MOD_SUFFIX.so" \
  "$TMP"/mod-regex/*.o "$cmd_shim" "$PCRE_A" \
  -Wl,--wrap=__assert2 -Wl,--wrap=__assert_fail \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
  "$SYSROOT_LIB/libc++abi.a" -ldl -lm -pthread
fi

if want reapi || want modules; then
echo "== building reapi module =="
REAPI="$SRC/reapi/reapi"
mkdir -p "$TMP/mod-reapi"
if [ -f "$REAPI/include/cssdk/engine/osconfig.h" ]; then
  python3 -c "
p='$REAPI/include/cssdk/engine/osconfig.h'
d=open(p).read()
if '#ifdef __arm__' not in d:
    d=d.replace('#include <smmintrin.h>\n#include <xmmintrin.h>',
                 '#if defined(__i386__) || defined(__x86_64__)\n#include <smmintrin.h>\n#include <xmmintrin.h>\n#endif')
    open(p,'w').write(d)
    print('   patched osconfig.h SSE includes for ARM64')
"
fi
if [ -f "$REAPI/include/cssdk/common/mathlib.h" ]; then
  python3 -c "
p='$REAPI/include/cssdk/common/mathlib.h'
d=open(p).read()
old='inline float M_sqrt(float value) {\n\treturn _mm_cvtss_f32(_mm_sqrt_ss(_mm_load_ss(&value)));\n}\n\ninline double M_sqrt(double value) {\n\tdouble ret;\n\tauto v = _mm_load_sd(&value);\n\t_mm_store_sd(&ret, _mm_sqrt_sd(v, v));\n\treturn ret;\n}'
new='inline float M_sqrt(float value) {\n#if defined(__i386__) || defined(__x86_64__)\n\treturn _mm_cvtss_f32(_mm_sqrt_ss(_mm_load_ss(&value)));\n#else\n\treturn sqrtf(value);\n#endif\n}\n\ninline double M_sqrt(double value) {\n#if defined(__i386__) || defined(__x86_64__)\n\tdouble ret;\n\tauto v = _mm_load_sd(&value);\n\t_mm_store_sd(&ret, _mm_sqrt_sd(v, v));\n\treturn ret;\n#else\n\treturn sqrt(value);\n#endif\n}'
if old in d:
    d=d.replace(old,new)
    open(p,'w').write(d)
    print('   patched mathlib.h M_sqrt for ARM64')
else:
    print('   mathlib.h already patched or target not found')
"
fi

rm -f "$REAPI/version/reapi_version.inc" "$REAPI/version/appversion.h"
bash "$REAPI/version/appversion.sh" "$SRC/reapi" reapi
REAPI_PATCHED="$REAPI/.reapi-abi-patched"
if [ ! -f "$REAPI_PATCHED" ]; then
python3 -c "
import os, sys, re
reapi = sys.argv[1]
p = os.path.join(reapi, 'src', 'hook_callback.h')
if os.path.exists(p):
    d=open(p).read()
    d=re.sub(r'(?<![:\w])min\(', 'std::min<static_cast<size_t>>(', d) if False else d
    d=d.replace('args_count = min(arg_count, MAX_HOOKCHAIN_ARGS)',
                 'args_count = std::min(static_cast<size_t>(arg_count), static_cast<size_t>(MAX_HOOKCHAIN_ARGS))')
    d=d.replace('std::min(arg_count, MAX_HOOKCHAIN_ARGS)',
                 'std::min(static_cast<size_t>(arg_count), static_cast<size_t>(MAX_HOOKCHAIN_ARGS))')
    open(p,'w').write(d)
p = os.path.join(reapi, 'src', 'hook_callback.h')
if os.path.exists(p):
    d=open(p).read()
    old_assert = 'static_assert(sizeof(T) <= sizeof(int), \"invalid hookchain argument size > sizeof(int)\");'
    new_assert = '#if defined(__LP64__) || defined(_WIN64)\\n\\t// On 64-bit, args are stored by address (size_t handle), not value\\n#else\\n\\tstatic_assert(sizeof(T) <= sizeof(int), \"invalid hookchain argument size > sizeof(int)\");\\n#endif'
    d=d.replace(old_assert, new_assert)
    open(p,'w').write(d)
p = os.path.join(reapi, 'src', 'natives', 'natives_helper.h')
if os.path.exists(p):
    d=open(p).read()
    old='operator size_t() const\n\t{\n\t\treturn size_t(m_value);\n\t}'
    new='#if ULONG_MAX != SIZE_MAX\n\toperator size_t() const\n\t{\n\t\treturn size_t(m_value);\n\t}\n#endif'
    d=d.replace(old,new)
    open(p,'w').write(d)
p = os.path.join(reapi, 'src', 'hook_list.cpp')
if os.path.exists(p):
    d=open(p).read()
    d=d.replace(
        'inline size_t getFwdParamType(void(*)(ULONG))                   { return FP_CELL;   }',
        '#if defined(_WIN64)\ninline size_t getFwdParamType(void(*)(ULONG))                   { return FP_CELL;   }\n#endif')
    d=d.replace(
        'inline size_t getFwdParamType(void(*)(float))                   { return FP_FLOAT;  }',
        'template<typename T>\ninline size_t getFwdParamType(void(*)(T))                      { return FP_CELL;   }\ninline size_t getFwdParamType(void(*)(float))                   { return FP_FLOAT;  }')
    open(p,'w').write(d)
import glob
for f in glob.glob(os.path.join(reapi, 'src', 'natives', '*.cpp')):
    d=open(f).read()
    import re as _re
    d2 = _re.sub(r'\(cell\)(get_member_direct<)', r'(cell)(uintptr_t)\1', d)
    if d2 != d:
        open(f,'w').write(d2)
        print(f'   patched {os.path.basename(f)} pointer casts')
p = os.path.join(reapi, 'src', 'hook_list.cpp')
if os.path.exists(p):
    d=open(p).read()
    d = _re.sub(r'\(cell\)(get_member_direct<)', r'(cell)(uintptr_t)\1', d)
    open(p,'w').write(d)
p = os.path.join(reapi, 'common', 'stdc++compat.cpp')
if os.path.exists(p):
    os.remove(p)
    print('   removed stdc++compat.cpp (not needed on Android)')
print('   patched reapi sources for ARM64')
" "$REAPI"
touch "$REAPI_PATCHED"
fi
REAPI_BASEFLAGS="-std=c++14 -O2 -fPIC -fpermissive -w \
  -D_LINUX -DLINUX -DNDEBUG -D_GLIBCXX_USE_CXX11_ABI=0 \
  -DHAVE_STRONG_TYPEDEF -D_stricmp=strcasecmp -D_strnicmp=strncasecmp \
  -D_vsnprintf=vsnprintf -D_snprintf=snprintf \
  -include algorithm -include cstdint -include cstddef \
  -Dmin=std::min -Dmax=std::max \
  -I$REAPI/include -I$REAPI/include/cssdk/common -I$REAPI/include/cssdk/dlls \
  -I$REAPI/include/cssdk/engine -I$REAPI/include/cssdk/game_shared \
  -I$REAPI/include/cssdk/pm_shared -I$REAPI/include/cssdk/public \
  -I$REAPI/include/metamod -I$REAPI/src -I$REAPI/src/mods -I$REAPI/src/natives \
  -I$REAPI/version -I$REAPI/common"
REAPI_SRCS=""
for f in "$REAPI"/src/*.cpp "$REAPI"/src/natives/*.cpp "$REAPI"/src/mods/*.cpp \
         "$REAPI"/common/*.cpp \
         "$REAPI"/include/cssdk/public/interface.cpp; do
  [ -e "$f" ] || continue
  bn=$(basename "$f" .cpp)
  "$CXX" $REAPI_BASEFLAGS -c "$f" -o "$TMP/mod-reapi/$bn.o"
  REAPI_SRCS="$REAPI_SRCS $TMP/mod-reapi/$bn.o"
done
"$CXX" -shared -o "$OUT/lib/$ABI/libreapi_amxx_$MOD_SUFFIX.so" $REAPI_SRCS \
  -static-libstdc++ -static-libgcc \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
  "$SYSROOT_LIB/libc++abi.a" -ldl -lm
echo "   reapi -> $(ls -l "$OUT/lib/$ABI/libreapi_amxx_$MOD_SUFFIX.so" | awk '{print $5}') bytes"
fi

if want yapb; then
echo "== building yapb =="
YAPBBUILD="$TMP/yapb-build"
cmake -S "$YAPB" -B "$YAPBBUILD" \
  -GNinja \
  -DCMAKE_C_COMPILER_LAUNCHER="${CCACHE:-}" \
  -DCMAKE_CXX_COMPILER_LAUNCHER="${CCACHE:-}" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$YAPBBUILD" -j"$(nproc)"
YAPB_SO=$(find "$YAPBBUILD" -name "libyapb*.so" -o -name "yapb*.so" | head -1)
if [ -n "$YAPB_SO" ]; then
  cp "$YAPB_SO" "$OUT/lib/$ABI/libyapb.so"
  echo "   yapb -> $(ls -l "$OUT/lib/$ABI/libyapb.so" | awk '{print $5}') bytes"
else
  echo "WARN: yapb lib not found, skipping"
fi
fi

if want client || want menu; then
echo "== building client (cs16-client, crash handler) =="
CLIENT_SRC="$REPO_ROOT/cs16-client"
CLIENT_BUILD="$TMP/client-build"
if [ -f "$CLIENT_SRC/.gitmodules" ]; then
  if [ -d "$CLIENT_SRC/.git" ]; then
    git -C "$CLIENT_SRC" submodule update --init --recursive 2>&1 | head -20 || true
  else
    echo "   cs16-client .git missing, fetching submodules manually"
    for mod in "3rdparty/mainui_cpp|https://github.com/Velaron/mainui_cpp|4922113b7477d9073e9f483f46b0e10b3be888b7" "3rdparty/miniutl|https://github.com/FWGS/MiniUTL|048a416f4c54c501dfd728fd792bfdc9f2883f51"; do
      IFS='|' read -r path url rev <<< "$mod"
      if [ ! -f "$CLIENT_SRC/$path/CMakeLists.txt" ] && [ ! -f "$CLIENT_SRC/$path/README.md" ]; then
        rm -rf "$CLIENT_SRC/$path"
        git clone --depth 1 "$url" "$CLIENT_SRC/$path" 2>&1 | tail -2 || true
        if [ -n "$rev" ]; then
          git -C "$CLIENT_SRC/$path" fetch --depth 1 origin "$rev" 2>&1 | tail -1 || true
          git -C "$CLIENT_SRC/$path" checkout "$rev" 2>&1 | tail -1 || true
        fi
        if [ -f "$CLIENT_SRC/$path/.gitmodules" ]; then
          git -C "$CLIENT_SRC/$path" submodule update --init --recursive --depth 1 2>&1 | tail -2 || true
        fi
      fi
    done
  fi
fi
if [ -d "$CLIENT_SRC/3rdparty/miniutl" ] && [ -d "$CLIENT_SRC/3rdparty/mainui_cpp/miniutl" ]; then
  echo "   mainui: replacing miniutl submodule with client-compatible pin (048a416f)"
  rm -rf "$CLIENT_SRC/3rdparty/mainui_cpp/miniutl"
  cp -a "$CLIENT_SRC/3rdparty/miniutl/." "$CLIENT_SRC/3rdparty/mainui_cpp/miniutl/"
fi
cmake -S "$CLIENT_SRC" -B "$CLIENT_BUILD" \
  -GNinja \
  -DCMAKE_C_COMPILER_LAUNCHER="${CCACHE:-}" \
  -DCMAKE_CXX_COMPILER_LAUNCHER="${CCACHE:-}" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS_RELEASE="-O2 -g" \
  -DCMAKE_CXX_FLAGS_RELEASE="-O2 -g" \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCS16_PATCHER_VERSION="${RELEASE_VERSION:-dev}" \
  -DBUILD_CLIENT=ON -DBUILD_SERVER=OFF -DBUILD_MAINUI=ON -DMAINUI_NAME=menu -DMAINUI_USE_STB=ON -DMAINUI_RENDER_PICBUTTON_TEXT=ON
if want client; then
cmake --build "$CLIENT_BUILD" --target client -j"$(nproc)"
CLIENT_SO=""
if [ -d "$CLIENT_BUILD" ]; then
  CLIENT_SO=$(find "$CLIENT_BUILD" -name "libclient_android_*.so" -o -name "client_android_*.so" | head -1)
fi
if [ -n "$CLIENT_SO" ]; then
  cp "$CLIENT_SO" "$OUT/lib/$ABI/libclient_android_$RUNTIME_SUFFIX.so"
  echo "   client -> $(ls -l "$OUT/lib/$ABI/libclient_android_$RUNTIME_SUFFIX.so" | awk '{print $5}') bytes"
else
  echo "WARN: client lib not found, skipping"
fi
fi
if want menu; then
cmake --build "$CLIENT_BUILD" --target menu -j"$(nproc)"
MENU_SO=""
if [ -d "$CLIENT_BUILD" ]; then
  MENU_SO=$(find "$CLIENT_BUILD" -name "libmenu*.so" | head -1)
fi
if [ -n "$MENU_SO" ]; then
  cp "$MENU_SO" "$OUT/lib/$ABI/libmenu_android_$RUNTIME_SUFFIX.so"
  echo "   menu -> $(ls -l "$OUT/lib/$ABI/libmenu_android_$RUNTIME_SUFFIX.so" | awk '{print $5}') bytes"
else
  echo "WARN: menu lib not found, skipping"
fi
fi
fi

if want plugins; then
echo "== building host pawncc (64-bit cells) =="
LIBPC="$AMXX/compiler/libpc300"
PC_BUILD="$TMP/libpc300"
mkdir -p "$PC_BUILD/obj"
PC_COMMON="-std=gnu17 -O0 -fPIC -DPAWN_CELL_SIZE=64 -DHAVE_I64 -DLINUX \
  -DHAVE_UNISTD_H -DHAVE_INTTYPES_H -DHAVE_STDINT_H -DHAVE_ALLOCA_H -I$LIBPC"
for s in sc1 sc2 sc3 sc4 sc5 sc6 sc7 scvars scmemfil scstate sclist sci18n \
         pawncc libpawnc prefix memfile sp_symhash; do
  f="$LIBPC/$s.c"
  [ -e "$f" ] || continue
  "$HOSTCC" $PC_COMMON -DNO_MAIN -DPAWNC_DLL -D_GNU_SOURCE \
    -c "$f" -o "$PC_BUILD/obj/$s.o"
done
"$HOSTCC" -shared -o "$PC_BUILD/amxxpc32.so" "$PC_BUILD"/obj/*.o -lm -lpthread
cp "$PC_BUILD/amxxpc32.so" "$PC_BUILD/amxxpc.so"

Z_DIR="$AMXX/third_party/zlib"
for f in "$Z_DIR"/*.c; do
  [ -e "$f" ] || continue
  "$HOSTCC" -O2 -fPIC -D_GNU_SOURCE -c "$f" -o "$PC_BUILD/obj/zlib-$(basename "${f%.c}").o"
done

PAWNCC=""
if command -v "$HOSTCXX" >/dev/null 2>&1 || [ -x "$HOSTCXX" ]; then
  "$HOSTCXX" -O2 -std=c++14 -DPAWN_CELL_SIZE=64 -DHAVE_I64 -DHAVE_STDINT_H \
    -DLINUX -DAMX_ANSIONLY \
    -I"$LIBPC" -I"$AMXX/public" -I"$AMXX/compiler/amxxpc" -I"$AMXX/third_party" \
    -o "$PC_BUILD/amxxpc" "$AMXX/compiler/amxxpc"/amxxpc.cpp \
    "$AMXX/compiler/amxxpc"/Binary.cpp "$AMXX/compiler/amxxpc"/amx.cpp \
    "$PC_BUILD"/obj/zlib-*.o
  PAWNCC="$PC_BUILD/amxxpc"
else
  echo "   host $HOSTCXX not found, skipping plugin compilation"
fi

if [[ -n "$PAWNCC" && -n "$PLUGINS_SRC" && -d "$PLUGINS_SRC" ]]; then
  echo "== compiling plugins (64-bit cells) =="
  mkdir -p "$LOGS_DIR"
  extra_inc=(-i"$AMXX/plugins/include")
  [ -d "$PLUGINS_SRC/include" ] && extra_inc+=(-i"$PLUGINS_SRC/include")
  for f in "$PLUGINS_SRC"/*.sma; do
    [ -e "$f" ] || continue
    local_out="$OUT/plugins/$(basename "${f%.sma}.amxx")"
    set +e
    out=$( ( cd "$PC_BUILD" && LC_ALL=C.UTF-8 LANG=C.UTF-8 stdbuf -oL -eL \
             "$PAWNCC" "${extra_inc[@]}" -o"$local_out" "$f" ) 2>&1 )
    rc=$?
    set -e
    if [ $rc -ne 0 ]; then
      echo "   FAILED: $f (rc=$rc)" >&2
      printf '%s\n' "$out" >&2
      {
        printf '\n===== %s rc=%d : %s =====\n' "$(date -Is)" "$rc" "$f"
        printf '%s\n' "$out"
      } >> "$ERROR_LOG"
      if command -v python3 >/dev/null 2>&1; then
        LC_ALL=C.UTF-8 python3 - "$f" "$local_out" "$AMXX/plugins/include" "$PC_BUILD" <<'PY' >&2 || true
import ctypes, os, sys
sma, outfile, inc, build = sys.argv[1:5]
os.chdir(build)
lib = ctypes.CDLL("./amxxpc32.so")
f = lib.Compile64
f.restype = ctypes.c_int
f.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
args = ["amxxpc", "-i" + inc, "-o" + outfile, sma]
argv = (ctypes.c_char_p * len(args))(*(a.encode() for a in args))
rc = f(len(args), argv)
print(f"[lib-only] Compile64 rc={rc} file_exists={os.path.exists(outfile)}", file=sys.stderr)
PY
      fi
      exit 1
    fi
    echo "   $(basename "$f") OK"
    { printf '%s\n' "$out"; } >> "$COMPILER_LOG"
  done
fi
fi

if want compiler; then
echo "== building amxxpc for $ABI (embedded, cell $CELL) =="
PC_DEV="$TMP/amxxpc-$ABI"
rm -rf "$PC_DEV"
mkdir -p "$PC_DEV"
PC_DEV_COMMON="-std=gnu17 -O2 -fPIC -DPAWN_CELL_SIZE=$CELL -DHAVE_I64 -DLINUX \
  -DHAVE_UNISTD_H -DHAVE_INTTYPES_H -DHAVE_STDINT_H -DHAVE_ALLOCA_H \
  -D__BYTE_ORDER=__LITTLE_ENDIAN -D__LITTLE_ENDIAN -I$LIBPC"
for s in sc1 sc2 sc3 sc4 sc5 sc6 sc7 scvars scmemfil scstate sclist sci18n \
         pawncc libpawnc prefix memfile sp_symhash; do
  f="$LIBPC/$s.c"
  [ -e "$f" ] || continue
  "$CC" $PC_DEV_COMMON -DNO_MAIN -DPAWNC_DLL -D_GNU_SOURCE -c "$f" -o "$PC_DEV/$s.o"
done
"$CXX" -shared -static-libstdc++ -o "$PC_DEV/amxxpc32.so" "$PC_DEV"/*.o -lm -ldl \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive "$SYSROOT_LIB/libc++abi.a"
mkdir -p "$PC_DEV/zobj"
for f in "$AMXX/third_party/zlib"/*.c; do
  [ -e "$f" ] || continue
  "$CC" -O2 -fPIC -c "$f" -o "$PC_DEV/zobj/$(basename "${f%.c}").o"
done
"$CXX" -O2 -std=c++14 -DPAWN_CELL_SIZE=$CELL -DHAVE_I64 -DHAVE_STDINT_H \
  -DLINUX -DAMX_ANSIONLY -D__BYTE_ORDER=__LITTLE_ENDIAN -D__LITTLE_ENDIAN \
  -I"$LIBPC" -I"$AMXX/public" -I"$AMXX/compiler/amxxpc" -I"$AMXX/third_party" \
  -o "$PC_DEV/amxxpc" "$AMXX/compiler/amxxpc"/amxxpc.cpp \
  "$AMXX/compiler/amxxpc"/Binary.cpp "$AMXX/compiler/amxxpc"/amx.cpp \
  "$PC_DEV"/zobj/*.o \
  -static-libstdc++ -static-libgcc \
  -Wl,--whole-archive "$SYSROOT_LIB/libc++_static.a" -Wl,--no-whole-archive \
  "$SYSROOT_LIB/libc++abi.a" -ldl -lm -pthread
mkdir -p "$OUT/compiler/$ABI"
cp "$PC_DEV/amxxpc" "$PC_DEV/amxxpc32.so" "$OUT/compiler/$ABI/"
echo "   amxxpc -> $(ls -l "$OUT/compiler/$ABI/amxxpc" | awk '{print $5}') bytes"
fi

echo "ALL_BUILT"
ls -l "$OUT/lib/$ABI/" "$OUT/plugins" "$OUT/compiler/$ABI" || true
