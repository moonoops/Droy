#define _GNU_SOURCE
#include <node_api.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <unistd.h>

static napi_value try_lock(napi_env env, napi_callback_info info) {
    size_t count = 2;
    napi_value args[2], result, global;
    int32_t fd;
    napi_get_cb_info(env, info, &count, args, NULL, NULL);
    if (count != 2 || napi_get_value_int32(env, args[0], &fd) != napi_ok || fd < 0) {
        napi_throw_type_error(env, NULL, "tryLock requires a file descriptor and callback");
        return NULL;
    }
    // LOCK_NB makes the syscall bounded; the descriptor stays owned by Node.
    int error = flock(fd, LOCK_EX | LOCK_NB) == 0 ? 0 : errno;
    napi_create_int32(env, error, &result);
    napi_get_global(env, &global);
    napi_call_function(env, global, args[1], 1, &result, NULL);
    napi_get_undefined(env, &result);
    return result;
}

static char *get_path(napi_env env, napi_value value) {
    size_t length;
    if (napi_get_value_string_utf8(env, value, NULL, 0, &length) != napi_ok) return NULL;
    char *path = malloc(length + 1);
    if (!path) return NULL;
    napi_get_value_string_utf8(env, value, path, length + 1, NULL);
    return path;
}

static napi_value rename_no_replace(napi_env env, napi_callback_info info) {
    size_t count = 2;
    napi_value args[2], result;
    napi_get_cb_info(env, info, &count, args, NULL, NULL);
    char *source = count == 2 ? get_path(env, args[0]) : NULL;
    char *target = count == 2 ? get_path(env, args[1]) : NULL;
    if (!source || !target) {
        free(source); free(target);
        napi_throw_type_error(env, NULL, "renameNoReplace requires two paths");
        return NULL;
    }
    // Android app data disallows hard links. RENAME_NOREPLACE preserves the
    // session publisher's atomic visibility and no-overwrite contract.
    int error = syscall(SYS_renameat2, AT_FDCWD, source, AT_FDCWD, target, 1) == 0 ? 0 : errno;
    free(source); free(target);
    napi_create_int32(env, error, &result);
    return result;
}

static napi_value initialize(napi_env env, napi_value exports) {
    napi_property_descriptor descriptors[] = {
        {"tryLock", NULL, try_lock, NULL, NULL, NULL, napi_default, NULL},
        {"renameNoReplace", NULL, rename_no_replace, NULL, NULL, NULL, napi_default, NULL},
    };
    napi_define_properties(env, exports, 2, descriptors);
    return exports;
}
NAPI_MODULE(NODE_GYP_MODULE_NAME, initialize)

