cmake_minimum_required(VERSION 3.15)
project(purl-grammar-tests-cpp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Locate nlohmann/json
#   1. Try CMake package (installed via nlohmann-json3-dev or vcpkg/conan)
#   2. Fall back to bare header search (e.g. /usr/include/nlohmann/json.hpp)
#   3. Fall back to FetchContent (downloads from GitHub at configure time)
# ---------------------------------------------------------------------------
find_package(nlohmann_json QUIET)

if(nlohmann_json_FOUND)
    message(STATUS "nlohmann/json: found via CMake package")
else()
    find_path(NLOHMANN_JSON_INCLUDE_DIR nlohmann/json.hpp
              HINTS /usr/include /usr/local/include)

    if(NLOHMANN_JSON_INCLUDE_DIR)
        message(STATUS "nlohmann/json: found header at ${NLOHMANN_JSON_INCLUDE_DIR}")
        add_library(nlohmann_json::nlohmann_json INTERFACE IMPORTED)
        set_target_properties(nlohmann_json::nlohmann_json PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${NLOHMANN_JSON_INCLUDE_DIR}")
    else()
        message(STATUS "nlohmann/json: not found locally, downloading via FetchContent")
        include(FetchContent)
        FetchContent_Declare(
            nlohmann_json
            GIT_REPOSITORY https://github.com/nlohmann/json.git
            GIT_TAG        v3.11.3
            GIT_SHALLOW    TRUE
        )
        FetchContent_MakeAvailable(nlohmann_json)
    endif()
endif()

# ---------------------------------------------------------------------------
# Executable
# ---------------------------------------------------------------------------
add_executable(purl-grammar-tests src/main.cpp)

target_link_libraries(purl-grammar-tests
    PRIVATE nlohmann_json::nlohmann_json)

target_compile_options(purl-grammar-tests PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wall -Wextra -Wpedantic>)

install(TARGETS purl-grammar-tests DESTINATION bin)
