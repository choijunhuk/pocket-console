if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR AND
   CMAKE_SOURCE_DIR STREQUAL POCKET_HOKKAIDO_SOURCE_DIR)
    if(NOT IS_DIRECTORY "${POCKET_SDK_SOURCE_DIR}")
        message(FATAL_ERROR "Pocket Console SDK source directory is missing")
    endif()
    cmake_language(DEFER CALL include "${POCKET_SDK_SOURCE_DIR}/CMakeLists.txt")
endif()
