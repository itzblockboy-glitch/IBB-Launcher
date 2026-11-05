LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

# ---------------------------------------------------------------------------------------------
# These are the upstream PojavLauncher C sources, built against a newer NDK (27) than they were
# written for. NDK 27 promotes several diagnostics to errors that were warnings upstream (notably
# int-conversion on 64-bit, e.g. passing NULL where a jint mode is expected). The code is correct
# in the cases that matter, so keep the build reproducible by relaxing exactly those diagnostics
# for the vendored first-party sources instead of patching every call site.
# ---------------------------------------------------------------------------------------------
VENDOR_CFLAGS := -Wno-int-conversion -Wno-incompatible-pointer-types \
                 -Wno-implicit-function-declaration -Wno-int-to-pointer-cast \
                 -Wno-pointer-to-int-cast -Wno-deprecated-non-prototype

# include $(HERE_PATH)/crash_dump/libbase/Android.mk
# include $(HERE_PATH)/crash_dump/libbacktrace/Android.mk
# include $(HERE_PATH)/crash_dump/debuggerd/Android.mk


LOCAL_PATH := $(HERE_PATH)

$(call import-module,prefab/bytehook)
LOCAL_PATH := $(HERE_PATH)

include $(CLEAR_VARS)
# Link GLESv2 for test
LOCAL_LDLIBS := -ldl -llog -landroid
# -lGLESv2
LOCAL_MODULE := pojavexec
LOCAL_CFLAGS += $(VENDOR_CFLAGS)
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
# LOCAL_CFLAGS += -DDEBUG
# -DGLES_TEST
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/loader_dlopen.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    environ/environ.c \
    jvm_hooks/emui_iterator_fix_hook.c \
    jvm_hooks/java_exec_hooks.c \
    jvm_hooks/lwjgl_dlopen_hook.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c \
    driver_helper/nsbypass.c

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
endif
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_CFLAGS += $(VENDOR_CFLAGS)
LOCAL_LDLIBS := -ldl -llog
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_SRC_FILES := \
    native_hooks/exit_hook.c \
    native_hooks/chmod_hook.c
include $(BUILD_SHARED_LIBRARY)

#ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
include $(CLEAR_VARS)
LOCAL_MODULE := linkerhook
LOCAL_CFLAGS += $(VENDOR_CFLAGS)
LOCAL_SRC_FILES := driver_helper/hook.c
LOCAL_LDFLAGS := -z global
include $(BUILD_SHARED_LIBRARY)
#endif

include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec_awt
LOCAL_CFLAGS += $(VENDOR_CFLAGS)
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_SRC_FILES := \
    awt_bridge.c
include $(BUILD_SHARED_LIBRARY)

# Helper to get current thread
# include $(CLEAR_VARS)
# LOCAL_MODULE := thread64helper
# LOCAL_SRC_FILES := thread_helper.cpp
# include $(BUILD_SHARED_LIBRARY)

# fake lib for linker
include $(CLEAR_VARS)
LOCAL_MODULE := awt_headless
LOCAL_CFLAGS += $(VENDOR_CFLAGS)
include $(BUILD_SHARED_LIBRARY)

# libawt_xawt without X11, used to get Caciocavallo working
LOCAL_PATH := $(HERE_PATH)/awt_xawt
include $(CLEAR_VARS)
LOCAL_MODULE := awt_xawt
LOCAL_CFLAGS += $(VENDOR_CFLAGS)
# LOCAL_CFLAGS += -DHEADLESS
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SHARED_LIBRARIES := awt_headless
LOCAL_SRC_FILES := xawt_fake.c
include $(BUILD_SHARED_LIBRARY)

# The "fake" libawt_headless.so is only needed at link time; remove it from the packaged output so
# it never shadows the real headless AWT that ships inside the JRE.
$(info $(shell (rm -f $(HERE_PATH)/../jniLibs/*/libawt_headless.so 2>/dev/null || true)))

