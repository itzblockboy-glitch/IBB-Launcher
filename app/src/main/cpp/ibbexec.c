/*
 * IBB Launcher — native shim (optional).
 *
 * This file is a *template*, not a replacement for PojavLauncher's execution layer. It shows the
 * exact JNI surface our Kotlin code expects, so a reimplementation (or a trimmed downstream fork)
 * has a compile-checked starting point. Every function here must resolve the symbols declared in
 * `net/kdt/pojavlaunch/utils/JREUtils.kt`.
 *
 * See docs/NATIVE_LAYER.md for the responsibilities each entry point has (EGL surface hand-off,
 * LD_LIBRARY_PATH management, JVM preloading, exit interception).
 */
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>

#define LOG_TAG "ibbexec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_chdir(JNIEnv *env, jclass clazz, jstring path) {
    const char *native_path = (*env)->GetStringUTFChars(env, path, NULL);
    int result = chdir(native_path);
    (*env)->ReleaseStringUTFChars(env, path, native_path);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_dlopen(JNIEnv *env, jclass clazz, jstring name) {
    const char *native_name = (*env)->GetStringUTFChars(env, name, NULL);
    /* RTLD_GLOBAL: later libraries (libjvm, LWJGL natives) must see these symbols. */
    void *handle = dlopen(native_name, RTLD_GLOBAL | RTLD_LAZY);
    if (handle == NULL) {
        LOGE("dlopen failed: %s", dlerror());
    } else {
        LOGI("dlopen ok: %s", native_name);
    }
    (*env)->ReleaseStringUTFChars(env, name, native_name);
    return handle != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setLdLibraryPath(JNIEnv *env, jclass clazz, jstring path) {
    const char *native_path = (*env)->GetStringUTFChars(env, path, NULL);
    setenv("LD_LIBRARY_PATH", native_path, 1);
    (*env)->ReleaseStringUTFChars(env, path, native_path);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv *env, jclass clazz, jobject surface) {
    /* Hand the ANativeWindow to your EGL bridge here. Upstream uses
     * ANativeWindow_fromSurface + eglCreateWindowSurface. */
    (void) env; (void) clazz; (void) surface;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_initializeHooks(JNIEnv *env, jclass clazz) {
    /* GLFW/GLX dispatch table, AWT stubs and the Android resolver patch live here upstream. */
    (void) env; (void) clazz;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz, jobject context) {
    (void) env; (void) clazz; (void) context;
}

JNIEXPORT jintArray JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_renderAWTScreenFrame(JNIEnv *env, jclass clazz) {
    /* AWT software rendering path (used by the installer GUIs). Optional. */
    (void) env; (void) clazz;
    return NULL;
}
