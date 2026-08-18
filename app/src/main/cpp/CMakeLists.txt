cmake_minimum_required(VERSION 3.16)
project(komorebi-native)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ARIBCC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ARIBCC_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(ARIBCC_NO_RENDERER OFF CACHE BOOL "" FORCE)
set(ARIBCC_USE_EMBEDDED_FREETYPE ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)

add_subdirectory(third_party/libaribcaption EXCLUDE_FROM_ALL)

# tlvdemux は playback helper を提供し、同じ libaribtlv source checkout を
# 内部でビルドする。libaribcaption は静的リンクのまま維持したいため、
# 追加後に BUILD_SHARED_LIBS を切り替える。
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(TLVDEMUX_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(TLVDEMUX_BUILD_WASM_BINDINGS OFF CACHE BOOL "" FORCE)
set(TLVDEMUX_LIBARIBTLV_SOURCE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libaribtlv"
    CACHE PATH "" FORCE)
add_subdirectory(third_party/tlvdemux EXCLUDE_FROM_ALL)
# The Android integration consumes tlvdemux's playback state/damage helpers.
# Its optional MSE remux sources still target the pre-0.5.0 video-color header,
# so keep that browser output slice out of this native elementary-stream path.
set_property(TARGET tlvdemux PROPERTY SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/tlvdemux/src/playback_damage.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/tlvdemux/src/playback_timing.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/tlvdemux/src/playback.cpp"
)

# 1. tsreadex のコアロジック（ソースファイル群）を定義
set(TSREADEX_CORE_SRC
    util.cpp
    id3conv.cpp
    servicefilter.cpp
    aac.cpp
    huffman.cpp
    traceb24.cpp
)

# 2. JNI の橋渡し役となる native-lib.cpp とコアロジックをまとめてライブラリにする
add_library(
    komorebi-native
    SHARED
    native-lib.cpp
    ${TSREADEX_CORE_SRC}
)

# 3. ログ出力用のライブラリをリンク（Android の Logcat 用）
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    komorebi-native
    ${log-lib}
    ${jnigraphics-lib}
    aribcaption::aribcaption
    tlvdemux::tlvdemux
)

# インクルードディレクトリの設定（カレントディレクトリを参照）
target_include_directories(komorebi-native PRIVATE .)
