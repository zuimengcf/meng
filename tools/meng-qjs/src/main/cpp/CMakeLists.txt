cmake_minimum_required(VERSION 3.22)
project(mengqjs C)
add_library(mengqjs SHARED qjs_jni.c quickjs/quickjs-amalgam.c)
target_include_directories(mengqjs PRIVATE quickjs)
# no QJS_BUILD_LIBC: scripts get no std/os module
target_compile_definitions(mengqjs PRIVATE _GNU_SOURCE _FORTIFY_SOURCE=2)
target_compile_options(mengqjs PRIVATE -Oz -w -fvisibility=hidden -ffunction-sections -fdata-sections -fstack-protector-strong)
target_link_options(mengqjs PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -s)
target_link_libraries(mengqjs m)
