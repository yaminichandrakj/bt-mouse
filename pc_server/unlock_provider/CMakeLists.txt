cmake_minimum_required(VERSION 3.20)
project(LanyardUnlock CXX)

# LogonUI loads this DLL as SYSTEM on machines that may not have the VC++ runtime,
# so link the C runtime statically.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(LanyardUnlock SHARED provider.cpp LanyardUnlock.def)
target_compile_features(LanyardUnlock PRIVATE cxx_std_17)
target_compile_definitions(LanyardUnlock PRIVATE UNICODE _UNICODE)
target_compile_options(LanyardUnlock PRIVATE /W4 /permissive- /guard:cf)
target_link_options(LanyardUnlock PRIVATE /guard:cf)
target_link_libraries(LanyardUnlock PRIVATE secur32 credui shlwapi advapi32 ole32 gdi32 user32 uuid propsys)
