//
// Created by canyie on 2020/2/9.
//

#include <elf.h>
#include "jni_bridge.h"
#include "art/art_method.h"
#include "utils/macros.h"
#include "utils/scoped_local_ref.h"
#include "utils/log.h"
#include "utils/jni_helper.h"
#include "trampoline/extras.h"
#include "trampoline/memory.h"

#ifdef __LP64__
#include "trampoline/arch/arm64.h"
#else
#include "trampoline/arch/thumb2.h"
#include "trampoline/arch/arm32.h"
#endif

#include "utils/well_known_classes.h"

using namespace pine;

TrampolineInstaller *trampoline_installer = nullptr;
bool debuggable = false;

void Pine_init0(JNIEnv *env, jclass Pine, jint androidVersion, jobject javaM1, jobject javaM2,
                jint accessFlags, jboolean isDebuggable) {
    LOGI("Pine native init...");
    Android::Init(env, androidVersion);
    auto m1 = art::ArtMethod::FromReflectedMethod(env, javaM1);
    auto m2 = art::ArtMethod::FromReflectedMethod(env, javaM2);
    art::ArtMethod::InitMembers(m1, m2, static_cast<uint32_t>(accessFlags));
    debuggable = static_cast<bool>(isDebuggable);

#if defined(__aarch64__)
    trampoline_installer = new Arm64TrampolineInstaller;
#elif defined(__arm__)
    {
        ScopedLocalRef<jclass> java_lang_String(env, env->FindClass("java/lang/String"));
        art::ArtMethod *hashCode = art::ArtMethod::FromMethodID(
                env->GetMethodID(java_lang_String.Get(), "hashCode", "()I"));
        if (LIKELY(hashCode->IsThumb())) {
            trampoline_installer = new Thumb2TrampolineInstaller;
        } else {
            LOGW("arm32 (non thumb-2) mode, supported but not tested.");
            trampoline_installer = new Arm32TrampolineInstaller;
        }
    }
#else
#error unsupported architecture
#endif

    trampoline_installer->Init();

    env->SetStaticBooleanField(Pine, env->GetStaticFieldID(Pine, "is64Bit", "Z"),
                               static_cast<jboolean>(Android::Is64Bit()));
}

jobject Pine_hook0(JNIEnv *env, jclass, jlong threadAddress, jclass declaring, jobject javaTarget,
                   jobject javaBridge, jboolean isInlineHook, jboolean isNativeOrProxy) {
    auto thread = reinterpret_cast<art::Thread *> (threadAddress);
    auto target = art::ArtMethod::FromReflectedMethod(env, javaTarget);
    auto bridge = art::ArtMethod::FromReflectedMethod(env, javaBridge);

    // The bridge method entry will be hardcoded in the trampoline, subsequent optimization
    // operations that require modification of the bridge method entry will not take effect.
    // Try to do JIT compilation first to get the best performance.
    bridge->Compile(thread);

    bool is_inline_hook = static_cast<bool>(isInlineHook);
    bool is_native_or_proxy = static_cast<bool>(isNativeOrProxy);

    if (is_inline_hook && UNLIKELY(trampoline_installer->CannotSafeInlineHook(target))) {
        LOGW("Cannot safe inline hook the target method, force replacement mode.");
        is_inline_hook = false;
    }

    ScopedLocalRef<jobject> java_new_art_method(env);
    art::ArtMethod *backup;
    if (WellKnownClasses::java_lang_reflect_ArtMethod) {
        // If ArtMethod has mirror class in java, we cannot use malloc to direct
        // allocate a instance because it must has a record in Runtime.
        java_new_art_method.Reset(env->AllocObject(WellKnownClasses::java_lang_reflect_ArtMethod));
        if (UNLIKELY(env->ExceptionCheck())) {
            LOGE("Cannot allocate backup ArtMethod object!");
            return nullptr;
        }
        backup = static_cast<art::ArtMethod *>(thread->DecodeJObject(java_new_art_method.Get()));
    } else {
        backup = art::ArtMethod::New();
        if (UNLIKELY(!backup)) {
            int local_errno = errno;
            LOGE("Cannot allocate backup ArtMethod, errno %d(%s)", errno, strerror(errno));
            if (local_errno == ENOMEM) {
                JNIHelper::ThrowNewException(env, "java/lang/OutOfMemoryError",
                        "No memory for allocate backup method");
            } else {
                JNIHelper::ThrowNewException(env, "java/lang/RuntimeException",
                        "hook failed: cannot allocate backup method");
            }
            return nullptr;
        }
    }

    bool success;

    {
        // An ArtMethod is a very important object. Many threads depend on their values,
        // so we need to suspend other threads to avoid errors when hooking.
        art::ScopedSuspendVM suspend_vm;

        void *call_origin = is_inline_hook
                            ? trampoline_installer->InstallInlineTrampoline(target, bridge)
                            : trampoline_installer->InstallReplacementTrampoline(target, bridge);

        if (LIKELY(call_origin)) {
            backup->BackupFrom(target, call_origin, is_inline_hook, is_native_or_proxy);
            target->AfterHook(is_inline_hook, debuggable, is_native_or_proxy);
            success = true;
        } else {
            LOGE("Failed to hook the method!");
            success = false;
        }
    }

    if (LIKELY(success)) {
        return env->ToReflectedMethod(declaring, backup->ToMethodID(),
                                      static_cast<jboolean>(backup->IsStatic()));
    } else {
        // TODO Throw exception has detailed error message
        JNIHelper::ThrowNewException(env, "java/lang/RuntimeException", "hook failed");
        return nullptr;
    }
}

jlong Pine_getArtMethod(JNIEnv *env, jclass, jobject javaMethod) {
    return reinterpret_cast<jlong> (env->FromReflectedMethod(javaMethod));
}

jboolean Pine_compile0(JNIEnv *env, jclass, jlong thread, jobject javaMethod) {
    return static_cast<jboolean>(art::ArtMethod::FromReflectedMethod(env, javaMethod)->Compile(
            reinterpret_cast<art::Thread *>(thread)));
}

jboolean Pine_decompile0(JNIEnv *env, jclass, jobject javaMethod, jboolean disableJit) {
    return static_cast<jboolean>(art::ArtMethod::FromReflectedMethod(env, javaMethod)->Decompile(
            disableJit));
}

jboolean Pine_disableJitInline0(JNIEnv *, jclass) {
    return static_cast<jboolean>(art::Jit::DisableInline());
}

jobject Pine_getObject0(JNIEnv *env, jclass, jlong thread, jlong address) {
    return reinterpret_cast<art::Thread *>(thread)->AddLocalRef(env,
                                                                reinterpret_cast<void *> (address));
}

jlong Pine_getAddress0(JNIEnv *, jclass, jlong thread, jobject o) {
    return reinterpret_cast<jlong>(reinterpret_cast<art::Thread *>(thread)->DecodeJObject(o));
}

#ifdef __LP64__
void Pine_getArgs64(JNIEnv *env, jclass, jlong javaExtras, jlongArray javaArray, jlong sp) {
    auto extras = reinterpret_cast<Extras *> (javaExtras);
    jint length = env->GetArrayLength(javaArray);
    if (LIKELY(length > 0)) {
        jlong *array = static_cast<jlong *>(env->GetPrimitiveArrayCritical(javaArray, nullptr));
        if (UNLIKELY(!array)) {
            constexpr const char *error_msg = "GetPrimitiveArrayCritical returned nullptr! javaArray is invalid?";
            LOGF(error_msg);
            env->FatalError(error_msg);
            abort(); // Unreachable
        }

        do {
            array[0] = reinterpret_cast<jlong>(extras->r1);
            if (length == 1) break;
            array[1] = reinterpret_cast<jlong>(extras->r2);
            if (length == 2) break;
            array[2] = reinterpret_cast<jlong>(extras->r3);
            if (length < 8) break; // x4-x7 will be restored in java

            // get args from stack
            for (int i = 7; i < length; i++) {
                array[i] = *reinterpret_cast<jlong *>(sp + 8 /*callee*/ + 8 * i);
            }
        } while (false);

        env->ReleasePrimitiveArrayCritical(javaArray, array, JNI_ABORT);
    }
    extras->ReleaseLock();
}
#else
void Pine_getArgs32(JNIEnv *env, jclass, jint javaExtras, jintArray javaArray, jint sp, jboolean skipR1) {
    auto extras = reinterpret_cast<Extras *> (javaExtras);
    jint length = env->GetArrayLength(javaArray);
    if (LIKELY(length > 0)) {
        jint *array = static_cast<jint *>(env->GetPrimitiveArrayCritical(javaArray, nullptr));
        if (UNLIKELY(!array)) {
            constexpr const char *error_msg = "GetPrimitiveArrayCritical returned nullptr! javaArray is invalid?";
            LOGF(error_msg);
            env->FatalError(error_msg);
            abort(); // Unreachable
        }

#pragma clang diagnostic push
#pragma ide diagnostic ignored "OCSimplifyInspection"
        do {
            if (skipR1 == JNI_TRUE) {
                // Skip r1 register: use r2, r3, sp + 12.
                array[0] = reinterpret_cast<jint>(extras->r2);
                if (length == 1) break;
                array[1] = reinterpret_cast<jint>(extras->r3);
                if (length == 2) break;
                array[2] = *reinterpret_cast<jint *>(sp + 12);
            } else {
                // Normal: use r1, r2, r3.
                array[0] = reinterpret_cast<jint>(extras->r1);
                if (length == 1) break;
                array[1] = reinterpret_cast<jint>(extras->r2);
                if (length == 2) break;
                array[2] = reinterpret_cast<jint>(extras->r3);
            }
            if (length == 3) break;

            // get args from stack
            for (int i = 3; i < length; i++) {
                array[i] = *reinterpret_cast<jint *> (sp + 4 /*callee*/ + 4 * i);
            }
        } while (false);
#pragma clang diagnostic pop

        env->ReleasePrimitiveArrayCritical(javaArray, array, JNI_ABORT);
    }
    extras->ReleaseLock();
}
#endif

void Pine_updateDeclaringClass(JNIEnv *env, jclass, jobject javaOrigin, jobject javaBackup) {
    auto origin = art::ArtMethod::FromReflectedMethod(env, javaOrigin);
    auto backup = art::ArtMethod::FromReflectedMethod(env, javaBackup);
    uint32_t declaring_class = origin->GetDeclaringClass();
    if (declaring_class != backup->GetDeclaringClass()) {
        LOGI("The declaring_class of method has moved by gc, update its reference in backup method now!");
        backup->SetDeclaringClass(declaring_class);
    }
}

static const struct {
    const char *name;
    const char *signature;
} gFastNativeMethods[] = {
        {"getArtMethod", "(Ljava/lang/reflect/Member;)J"},
        {"updateDeclaringClass", "(Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;)V"},
        {"decompile0", "(Ljava/lang/reflect/Member;Z)Z"},
        {"disableJitInline0", "()Z"},
        {"getObject0", "(JJ)Ljava/lang/Object;"},
        {"getAddress0", "(JLjava/lang/Object;)J"},
#ifdef __LP64__
        {"getArgs64", "(J[JJ)V"}
#else
        {"getArgs32", "(I[II)V"}
#endif
};

void Pine_enableFastNative(JNIEnv *env, jclass Pine) {
    LOGI("Experimental feature FastNative is enabled.");
    for (auto method_info : gFastNativeMethods) {
        auto method = art::ArtMethod::FromMethodID(env->GetStaticMethodID(
                Pine, method_info.name, method_info.signature));
        if (UNLIKELY(!method)) {
            LOGF("Cannot find native method %s%s", method_info.name, method_info.signature);
            return; // has a pending NoSuchMethodError
        }
        method->SetFastNative();
    }
}

static const JNINativeMethod gMethods[] = {
        {"init0",                     "(ILjava/lang/reflect/Method;Ljava/lang/reflect/Method;IZ)V", (void *) Pine_init0},
        {"enableFastNative",          "()V",                                                        (void *) Pine_enableFastNative},
        {"getArtMethod",              "(Ljava/lang/reflect/Member;)J",                              (void *) Pine_getArtMethod},
        {"hook0",
                                      "(JLjava/lang/Class;Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;ZZ)Ljava/lang/reflect/Method;",
                                                                                                    (void *) Pine_hook0},
        {"compile0",                  "(JLjava/lang/reflect/Member;)Z",                             (void *) Pine_compile0},
        {"decompile0",                "(Ljava/lang/reflect/Member;Z)Z",                             (void *) Pine_decompile0},
        {"disableJitInline0",         "()Z",                                                        (void *) Pine_disableJitInline0},
        {"updateDeclaringClass",      "(Ljava/lang/reflect/Member;Ljava/lang/reflect/Method;)V",
                                                                                                    (void *) Pine_updateDeclaringClass},
        {"getObject0",                "(JJ)Ljava/lang/Object;",                                     (void *) Pine_getObject0},
        {"getAddress0",               "(JLjava/lang/Object;)J",                                     (void *) Pine_getAddress0},

#ifdef __LP64__
        {"getArgs64",                 "(J[JJ)V",                                                    (void *) Pine_getArgs64}
#else
        {"getArgs32",                 "(I[IIZ)V",                                                   (void *) Pine_getArgs32}
#endif
};

bool register_Pine(JNIEnv *env, jclass Pine) {
    return LIKELY(env->RegisterNatives(Pine, gMethods, NELEM(gMethods)) == JNI_OK);
}