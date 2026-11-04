# ================================================================================================
# IBB Launcher — optional native shim
# ================================================================================================
# The shim lands in app/src/main/jniLibs/<abi>/ so it is packaged exactly like the
# prebuilt bridge objects.
#
# By default the execution layer is consumed as *prebuilt* shared objects (PojavLauncher's GPLv3
# `libpojavexec.so`, `libpojavexec_awt.so`, `libexithook.so`) dropped into
# `app/src/main/jniLibs/<abi>/`. Those export the JNI symbols that
# `net.kdt.pojavlaunch.utils.JREUtils` declares.
#
# Build the shim with:  ./gradlew assembleDebug -PwithNative=true
# It produces `libibbexec.so`, a tiny library that only needs to exist when you replace the
# prebuilt bridge with your own port (see docs/NATIVE_LAYER.md).
cmake_minimum_required(VERSION 3.22.1)
project(ibbexec C)

add_library(ibbexec SHARED ibbexec.c)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(ibbexec ${log-lib} ${android-lib} dl)

set_target_properties(ibbexec PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
