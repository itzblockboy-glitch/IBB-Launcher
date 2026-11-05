# NDK configuration for the vendored PojavLauncher execution layer.
#
# APP_PLATFORM 21 matches the upstream bridge (and is below our minSdk of 26).
# APP_STL system: the bridge is plain C and must not pull in libc++.
#
# The -Wno-* flags mirror VENDOR_CFLAGS in Android.mk. They are repeated here because ndk-build
# applies APP_CFLAGS to every module, which keeps the build working even if a future source file is
# added outside a module that sets VENDOR_CFLAGS. NDK 27 turned several of these into hard errors.
APP_PLATFORM := android-21
APP_STL := system
APP_CFLAGS := -Wno-int-conversion -Wno-incompatible-pointer-types \
              -Wno-implicit-function-declaration -Wno-int-to-pointer-cast \
              -Wno-pointer-to-int-cast -Wno-deprecated-non-prototype
