cmake_minimum_required(VERSION 3.22.1)
project(hatchghostty C)

# libghostty-vt is built from upstream source at the commit in ghostty/src/main/cpp/ghostty/COMMIT.
set(GHOSTTY_LIB ${CMAKE_CURRENT_SOURCE_DIR}/ghostty/lib/${ANDROID_ABI}/libghostty-vt.a)
if(NOT EXISTS ${GHOSTTY_LIB})
    message(FATAL_ERROR "Missing ${GHOSTTY_LIB}. Run scripts/build-ghostty.sh first.")
endif()

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES IMPORTED_LOCATION ${GHOSTTY_LIB})

add_library(hatchghostty SHARED ghostty_jni.c)
target_include_directories(hatchghostty PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ghostty/include)
target_compile_definitions(hatchghostty PRIVATE GHOSTTY_STATIC)
target_compile_options(hatchghostty PRIVATE -Wall -Wextra -Werror -O3)
target_link_libraries(hatchghostty ghostty-vt)
# 16 KB pages: required by newer Android devices.
target_link_options(hatchghostty PRIVATE "-Wl,-z,max-page-size=16384")
