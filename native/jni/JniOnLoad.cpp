// JNI_OnLoad — explicit registration of the native seam (spec §6.5 rule 4).
//
// WHY RegisterNatives AND NOT NAME MANGLING (Java_com_redcut_..._nativeAdd):
//
//  1. It fails LOUDLY at the right time. A name-mangled symbol that stops matching
//     (a class renamed, an R8-obfuscated class, a signature change) throws
//     UnsatisfiedLinkError at the first call — which in this product is inside a
//     render loop, minutes into a session. RegisterNatives runs at library load
//     and reports the failure there, before any editor state exists.
//  2. It survives R8. Name mangling ties a C symbol to a Java class NAME; the
//     obfuscator renames the class. Spec §11's R8 keep rules exist only for JNI
//     entry points, and this is how that list stays empty.
//  3. No per-call symbol lookup cost.
//
// Returning JNI_ERR from JNI_OnLoad makes the JVM refuse to load the library, so
// the failure is a crash at startup with the class name in the message rather than
// a latent null.
#include <jni.h>

#include "redcut/seam.h"

namespace {

constexpr const char* kBridgeClass = "com/redcut/engine/nativecore/NativeBridge";

// The receiver is `jobject` (an instance method) because NativeBridge is a Kotlin
// `object`: its members are instance members of the singleton. Static registration
// would need @JvmStatic on the Kotlin side, which is a Kotlin implementation
// detail leaking into this file.
jint SeamAdd(JNIEnv*, jobject, jint a, jint b) { return redcut::seam::add(a, b); }

jint SeamVersionCode(JNIEnv*, jobject) { return redcut::seam::kVersionCode; }

// JNINativeMethod's name/signature fields are `char*`, not `const char*` — a
// historical wart in the JNI header, and a hard error under -Werror in C++ (which
// forbids converting a string literal to a writable pointer). The const_cast is the
// standard way out and it is safe here: RegisterNatives does not write to either
// string, and both literals have static storage duration, so nothing can dangle.
const JNINativeMethod kSeamMethods[] = {
    {const_cast<char*>("nativeAdd"), const_cast<char*>("(II)I"), reinterpret_cast<void*>(SeamAdd)},
    {const_cast<char*>("nativeVersionCode"), const_cast<char*>("()I"),
     reinterpret_cast<void*>(SeamVersionCode)},
};

bool RegisterSeam(JNIEnv* env) {
    jclass bridge = env->FindClass(kBridgeClass);
    if (bridge == nullptr) {
        // FindClass leaves a pending exception (NoClassDefFoundError); the JVM
        // prints it when we return JNI_ERR, which names the class we expected.
        return false;
    }
    const jint result = env->RegisterNatives(
        bridge, kSeamMethods, static_cast<jint>(sizeof(kSeamMethods) / sizeof(kSeamMethods[0])));
    env->DeleteLocalRef(bridge);
    return result == JNI_OK;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (!RegisterSeam(env)) {
        return JNI_ERR;
    }
    // The version the JVM should use. Returning JNI_ERR instead would make the load
    // fail, which is what we want for a broken registration.
    return JNI_VERSION_1_6;
}
