# ipage C++ 版构建脚本
#
# 用法：
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
#   ctest --test-dir build --output-on-failure
#
# 只依赖标准库与内嵌的 SQLite amalgamation（sqlite/sqlite3.c），无外部依赖。

cmake_minimum_required(VERSION 3.16)
project(ipage LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 99)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "构建类型" FORCE)
endif()

# ---------------------------------------------------------------------------
#  SQLite（内嵌）
# ---------------------------------------------------------------------------

add_library(sqlite3_amalgamation STATIC sqlite/sqlite3.c)
target_include_directories(sqlite3_amalgamation PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/sqlite)
target_compile_definitions(sqlite3_amalgamation PRIVATE
    SQLITE_THREADSAFE=1
    SQLITE_OMIT_LOAD_EXTENSION
)
if(MSVC)
    target_compile_options(sqlite3_amalgamation PRIVATE /W0)
else()
    target_compile_options(sqlite3_amalgamation PRIVATE -w)
endif()

# ---------------------------------------------------------------------------
#  ipage
# ---------------------------------------------------------------------------

add_library(ipage STATIC
    ipage/dialect.cpp
    ipage/sql.cpp
    ipage/connection.cpp
)
target_include_directories(ipage PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(ipage PUBLIC sqlite3_amalgamation)

if(MSVC)
    target_compile_options(ipage PRIVATE /W4)
else()
    target_compile_options(ipage PRIVATE -Wall -Wextra -Wpedantic)
endif()

# ---------------------------------------------------------------------------
#  示例与测试
# ---------------------------------------------------------------------------

add_executable(ipage_example examples/main.cpp)
target_link_libraries(ipage_example PRIVATE ipage)

enable_testing()
add_executable(test_ipage tests/test_ipage.cpp)
target_link_libraries(test_ipage PRIVATE ipage)
add_test(NAME ipage_e2e COMMAND test_ipage)
