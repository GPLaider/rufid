#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>

#include "wimlib.h"

typedef int (*wimlib_open_wim_fn)(const char *, int, WIMStruct **);
typedef int (*wimlib_split_fn)(WIMStruct *, const char *, uint64_t, int);
typedef void (*wimlib_free_fn)(WIMStruct *);
typedef const char *(*wimlib_get_error_string_fn)(enum wimlib_error_code);
typedef void (*wimlib_register_progress_function_fn)(
        WIMStruct *, wimlib_progress_func_t, void *);

struct RufidWimLibApi {
    wimlib_open_wim_fn open_wim;
    wimlib_split_fn split;
    wimlib_free_fn free_wim;
    wimlib_get_error_string_fn get_error_string;
    wimlib_register_progress_function_fn register_progress;
};

struct RufidProgressContext {
    JNIEnv *env;
    jobject cancellation_token;
    jmethodID is_cancelled;
};

static enum wimlib_progress_status
check_cancelled(
        enum wimlib_progress_msg msg_type,
        union wimlib_progress_info *info,
        void *context_pointer) {
    (void)msg_type;
    (void)info;
    struct RufidProgressContext *context = context_pointer;
    jboolean cancelled = (*context->env)->CallBooleanMethod(
            context->env, context->cancellation_token, context->is_cancelled);
    if ((*context->env)->ExceptionCheck(context->env) || cancelled == JNI_TRUE) {
        return WIMLIB_PROGRESS_STATUS_ABORT;
    }
    return WIMLIB_PROGRESS_STATUS_CONTINUE;
}

static const char *load_wimlib_api(struct RufidWimLibApi *api) {
    void *handle = dlopen("libwimutils.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle == 0) {
        const char *error = dlerror();
        return error != 0 ? error : "Unable to load packaged wimlib.";
    }

    api->open_wim = (wimlib_open_wim_fn)dlsym(handle, "wimlib_open_wim");
    api->split = (wimlib_split_fn)dlsym(handle, "wimlib_split");
    api->free_wim = (wimlib_free_fn)dlsym(handle, "wimlib_free");
    api->get_error_string =
            (wimlib_get_error_string_fn)dlsym(handle, "wimlib_get_error_string");
    api->register_progress = (wimlib_register_progress_function_fn)dlsym(
            handle, "wimlib_register_progress_function");
    if (api->open_wim == 0 || api->split == 0 || api->free_wim == 0 ||
            api->get_error_string == 0 || api->register_progress == 0) {
        return "Packaged wimlib is missing a required split symbol.";
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_io_github_rufid_core_NativeWimLib_splitWim(
        JNIEnv *env,
        jobject self,
        jstring input_wim_path,
        jstring first_swm_path,
        jlong part_size_bytes,
        jobject cancellation_token) {
    (void)self;

    struct RufidWimLibApi api = {0};
    const char *load_error = load_wimlib_api(&api);
    if (load_error != 0) {
        return (*env)->NewStringUTF(env, load_error);
    }

    const char *input_path = (*env)->GetStringUTFChars(env, input_wim_path, 0);
    if (input_path == 0) return (*env)->NewStringUTF(env, "Unable to read input WIM path.");

    const char *output_path = (*env)->GetStringUTFChars(env, first_swm_path, 0);
    if (output_path == 0) {
        (*env)->ReleaseStringUTFChars(env, input_wim_path, input_path);
        return (*env)->NewStringUTF(env, "Unable to read output SWM path.");
    }

    jclass token_class = (*env)->GetObjectClass(env, cancellation_token);
    jmethodID is_cancelled = token_class == 0
            ? 0
            : (*env)->GetMethodID(env, token_class, "isCancelled", "()Z");
    if (is_cancelled == 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->ReleaseStringUTFChars(env, first_swm_path, output_path);
        (*env)->ReleaseStringUTFChars(env, input_wim_path, input_path);
        return (*env)->NewStringUTF(env, "Unable to observe WIM split cancellation.");
    }

    if (part_size_bytes <= 0) {
        (*env)->ReleaseStringUTFChars(env, first_swm_path, output_path);
        (*env)->ReleaseStringUTFChars(env, input_wim_path, input_path);
        return (*env)->NewStringUTF(env, "WIM split part size must be positive.");
    }

    WIMStruct *wim = 0;
    int ret = api.open_wim(input_path, 0, &wim);
    if (ret == 0 && wim != 0) {
        struct RufidProgressContext progress_context = {
                .env = env,
                .cancellation_token = cancellation_token,
                .is_cancelled = is_cancelled,
        };
        api.register_progress(wim, check_cancelled, &progress_context);
        ret = api.split(wim, output_path, (uint64_t)part_size_bytes, 0);
    }
    if (wim != 0) {
        api.free_wim(wim);
    }

    (*env)->ReleaseStringUTFChars(env, first_swm_path, output_path);
    (*env)->ReleaseStringUTFChars(env, input_wim_path, input_path);

    if (ret == 0) return 0;
    return (*env)->NewStringUTF(env, api.get_error_string((enum wimlib_error_code)ret));
}
