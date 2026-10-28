cmake_minimum_required(VERSION 3.25)
project(Burrow LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_executable(Burrow WIN32
    src/main.cpp
    src/app_state.cpp
    src/certificate.cpp
    src/crypto.cpp
    src/json.cpp
    src/lm_studio.cpp
    src/server.cpp
    src/tls_connection.cpp
    src/ui.cpp
    src/app.manifest
    src/app.rc
)

target_compile_definitions(Burrow PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    SECURITY_WIN32
    SCHANNEL_USE_BLACKLISTS
)

target_compile_options(Burrow PRIVATE /W4 /permissive- /utf-8 /EHsc)

target_link_options(Burrow PRIVATE /DEPENDENTLOADFLAG:0x800)

target_link_libraries(Burrow PRIVATE
    ws2_32 secur32 crypt32 ncrypt bcrypt winhttp iphlpapi dwmapi uxtheme shell32 advapi32 user32 gdi32
)
