cmake_minimum_required(VERSION 3.22.1)
project("nativegl")

add_library(nativegl SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(nativegl
    ${log-lib}
    GLESv2
    jnigraphics
    android)
