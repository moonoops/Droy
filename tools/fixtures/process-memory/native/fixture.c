#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>

typedef struct {
    _Atomic int32_t gold;
    _Atomic int32_t decoy_a;
    _Atomic int32_t decoy_b;
    _Atomic int32_t energy;
} Values;

static void *allocation;
static size_t page_size;
static unsigned char *data_page;
static Values *values;

static void reset_values(void) {
    atomic_store_explicit(&values->gold, 1200, memory_order_relaxed);
    atomic_store_explicit(&values->decoy_a, 1200, memory_order_relaxed);
    atomic_store_explicit(&values->decoy_b, 1200, memory_order_relaxed);
    atomic_store_explicit(&values->energy, 77, memory_order_relaxed);
}

static void initialize(void) {
    if (values) return;
    long queried = sysconf(_SC_PAGESIZE);
    if (queried <= 0) abort();
    page_size = (size_t) queried;
    allocation = mmap(NULL, page_size * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (allocation == MAP_FAILED) abort();
    data_page = (unsigned char *) allocation + page_size;
    if (mprotect(data_page, page_size, PROT_READ | PROT_WRITE) != 0) abort();
    // Guard pages keep this small data mapping distinct from adjacent heap mappings.
    values = (Values *) (data_page + 128);
    reset_values();
}

JNIEXPORT void JNICALL Java_io_droyagent_memoryfixture_FixtureMemory_initialize(JNIEnv *env, jclass klass) {
    (void) env; (void) klass;
    initialize();
}

JNIEXPORT void JNICALL Java_io_droyagent_memoryfixture_FixtureMemory_reset(JNIEnv *env, jclass klass) {
    (void) env; (void) klass;
    initialize(); reset_values();
}

JNIEXPORT void JNICALL Java_io_droyagent_memoryfixture_FixtureMemory_spend(JNIEnv *env, jclass klass) {
    (void) env; (void) klass;
    initialize();
    atomic_fetch_sub_explicit(&values->gold, 100, memory_order_relaxed);
}

JNIEXPORT jstring JNICALL Java_io_droyagent_memoryfixture_FixtureMemory_state(JNIEnv *env, jclass klass) {
    (void) klass; initialize();
    char json[256];
    snprintf(json, sizeof(json), "{\"gold\":%d,\"decoyA\":%d,\"decoyB\":%d,\"energy\":%d}",
        atomic_load_explicit(&values->gold, memory_order_relaxed),
        atomic_load_explicit(&values->decoy_a, memory_order_relaxed),
        atomic_load_explicit(&values->decoy_b, memory_order_relaxed),
        atomic_load_explicit(&values->energy, memory_order_relaxed));
    return (*env)->NewStringUTF(env, json);
}

JNIEXPORT jstring JNICALL Java_io_droyagent_memoryfixture_FixtureMemory_metadata(JNIEnv *env, jclass klass) {
    (void) klass; initialize();
    char json[256];
    snprintf(json, sizeof(json), "{\"pid\":%d,\"pageSize\":%zu,\"regionStart\":\"0x%llx\",\"regionEnd\":\"0x%llx\",\"goldAddress\":\"0x%llx\"}",
        getpid(), page_size, (unsigned long long) (uintptr_t) data_page,
        (unsigned long long) ((uintptr_t) data_page + page_size),
        (unsigned long long) (uintptr_t) &values->gold);
    return (*env)->NewStringUTF(env, json);
}
