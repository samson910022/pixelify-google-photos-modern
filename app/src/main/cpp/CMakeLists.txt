cmake_minimum_required(VERSION 3.22.1)
project(pixelify_build)

add_library(pixelify_build SHARED build_fields.cpp)

find_library(log-lib log)
target_link_libraries(pixelify_build android ${log-lib})

target_compile_options(pixelify_build PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti)
