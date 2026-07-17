#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <cstring>

#define LOG_TAG "Pixelify"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// NOTE: Do NOT probe/write ArtField memory via jfieldID heuristics.
// On modern ART layouts that easily corrupts neighboring members (dex idx / offset)
// and can crash the host (Google Photos). JNI SetStatic*Field already bypasses
// Java reflection final checks; success is judged by post-write readback.

static bool clearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

static jboolean setString(JNIEnv* env, jclass targetClass, const char* name, jstring value) {
    jfieldID fid = env->GetStaticFieldID(targetClass, name, "Ljava/lang/String;");
    if (fid == nullptr || clearException(env)) {
        ALOGW("GetStaticFieldID String %s failed", name);
        return JNI_FALSE;
    }
    env->SetStaticObjectField(targetClass, fid, value);
    if (clearException(env)) {
        ALOGW("SetStaticObjectField %s threw", name);
        return JNI_FALSE;
    }
    jobject actual = env->GetStaticObjectField(targetClass, fid);
    if (clearException(env)) return JNI_FALSE;
    if (value == nullptr && actual == nullptr) return JNI_TRUE;
    if (value == nullptr || actual == nullptr) return JNI_FALSE;
    const char* exp = env->GetStringUTFChars(value, nullptr);
    const char* act = env->GetStringUTFChars(static_cast<jstring>(actual), nullptr);
    if (exp == nullptr || act == nullptr) {
        if (exp) env->ReleaseStringUTFChars(value, exp);
        if (act) env->ReleaseStringUTFChars(static_cast<jstring>(actual), act);
        clearException(env);
        return JNI_FALSE;
    }
    const bool ok = std::strcmp(exp, act) == 0;
    env->ReleaseStringUTFChars(value, exp);
    env->ReleaseStringUTFChars(static_cast<jstring>(actual), act);
    if (!ok) ALOGW("JNI String readback mismatch for %s", name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean setInt(JNIEnv* env, jclass targetClass, const char* name, jint value) {
    jfieldID fid = env->GetStaticFieldID(targetClass, name, "I");
    if (fid == nullptr || clearException(env)) return JNI_FALSE;
    env->SetStaticIntField(targetClass, fid, value);
    if (clearException(env)) return JNI_FALSE;
    jint actual = env->GetStaticIntField(targetClass, fid);
    if (clearException(env)) return JNI_FALSE;
    return actual == value ? JNI_TRUE : JNI_FALSE;
}

static jboolean setLong(JNIEnv* env, jclass targetClass, const char* name, jlong value) {
    jfieldID fid = env->GetStaticFieldID(targetClass, name, "J");
    if (fid == nullptr || clearException(env)) return JNI_FALSE;
    env->SetStaticLongField(targetClass, fid, value);
    if (clearException(env)) return JNI_FALSE;
    jlong actual = env->GetStaticLongField(targetClass, fid);
    if (clearException(env)) return JNI_FALSE;
    return actual == value ? JNI_TRUE : JNI_FALSE;
}

static jboolean setBoolean(JNIEnv* env, jclass targetClass, const char* name, jboolean value) {
    jfieldID fid = env->GetStaticFieldID(targetClass, name, "Z");
    if (fid == nullptr || clearException(env)) return JNI_FALSE;
    env->SetStaticBooleanField(targetClass, fid, value);
    if (clearException(env)) return JNI_FALSE;
    jboolean actual = env->GetStaticBooleanField(targetClass, fid);
    if (clearException(env)) return JNI_FALSE;
    return actual == value ? JNI_TRUE : JNI_FALSE;
}

// Kotlin: DeviceSpoofer.BuildFieldNative.nativeSetStatic
// JNI: Java_io_github_samson910022_pixelifyphotos_DeviceSpoofer_00024BuildFieldNative_nativeSetStatic
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_samson910022_pixelifyphotos_DeviceSpoofer_00024BuildFieldNative_nativeSetStatic(
        JNIEnv* env,
        jclass /*clazz*/,
        jclass targetClass,
        jstring fieldName,
        jobject value) {
    if (targetClass == nullptr || fieldName == nullptr) return JNI_FALSE;

    const char* name = env->GetStringUTFChars(fieldName, nullptr);
    if (name == nullptr) {
        clearException(env);
        return JNI_FALSE;
    }

    jboolean result = JNI_FALSE;

    // Resolve field type via reflection on the Class object.
    jclass classClass = env->FindClass("java/lang/Class");
    jclass fieldClass = env->FindClass("java/lang/reflect/Field");
    if (classClass == nullptr || fieldClass == nullptr || clearException(env)) {
        env->ReleaseStringUTFChars(fieldName, name);
        return JNI_FALSE;
    }
    jmethodID getDeclaredField = env->GetMethodID(
            classClass, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
    jmethodID getType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
    if (getDeclaredField == nullptr || getType == nullptr || clearException(env)) {
        env->ReleaseStringUTFChars(fieldName, name);
        return JNI_FALSE;
    }

    jobject fieldObj = env->CallObjectMethod(targetClass, getDeclaredField, fieldName);
    if (fieldObj == nullptr || clearException(env)) {
        ALOGW("getDeclaredField failed for %s", name);
        env->ReleaseStringUTFChars(fieldName, name);
        return JNI_FALSE;
    }
    jobject typeObj = env->CallObjectMethod(fieldObj, getType);
    if (typeObj == nullptr || clearException(env)) {
        env->ReleaseStringUTFChars(fieldName, name);
        return JNI_FALSE;
    }

    // Primitive TYPE classes
    jclass integerClass = env->FindClass("java/lang/Integer");
    jclass longClass = env->FindClass("java/lang/Long");
    jclass booleanClass = env->FindClass("java/lang/Boolean");
    jclass stringClass = env->FindClass("java/lang/String");
    if (clearException(env) || integerClass == nullptr || longClass == nullptr ||
        booleanClass == nullptr || stringClass == nullptr) {
        env->ReleaseStringUTFChars(fieldName, name);
        return JNI_FALSE;
    }

    jfieldID intTypeId = env->GetStaticFieldID(integerClass, "TYPE", "Ljava/lang/Class;");
    jfieldID longTypeId = env->GetStaticFieldID(longClass, "TYPE", "Ljava/lang/Class;");
    jfieldID boolTypeId = env->GetStaticFieldID(booleanClass, "TYPE", "Ljava/lang/Class;");
    jobject intType = env->GetStaticObjectField(integerClass, intTypeId);
    jobject longType = env->GetStaticObjectField(longClass, longTypeId);
    jobject boolType = env->GetStaticObjectField(booleanClass, boolTypeId);
    if (clearException(env)) {
        env->ReleaseStringUTFChars(fieldName, name);
        return JNI_FALSE;
    }

    if (env->IsSameObject(typeObj, intType)) {
        if (value == nullptr) {
            result = JNI_FALSE;
        } else {
            jmethodID intValue = env->GetMethodID(integerClass, "intValue", "()I");
            // value may be Integer or other Number — try Integer path first
            jint v;
            if (env->IsInstanceOf(value, integerClass) && intValue != nullptr) {
                v = env->CallIntMethod(value, intValue);
            } else {
                jclass numberClass = env->FindClass("java/lang/Number");
                jmethodID nInt = numberClass
                        ? env->GetMethodID(numberClass, "intValue", "()I")
                        : nullptr;
                if (numberClass == nullptr || nInt == nullptr || !env->IsInstanceOf(value, numberClass) ||
                    clearException(env)) {
                    env->ReleaseStringUTFChars(fieldName, name);
                    return JNI_FALSE;
                }
                v = env->CallIntMethod(value, nInt);
            }
            if (clearException(env)) {
                env->ReleaseStringUTFChars(fieldName, name);
                return JNI_FALSE;
            }
            result = setInt(env, targetClass, name, v);
        }
    } else if (env->IsSameObject(typeObj, longType)) {
        if (value == nullptr) {
            result = JNI_FALSE;
        } else {
            jclass numberClass = env->FindClass("java/lang/Number");
            jmethodID nLong = numberClass
                    ? env->GetMethodID(numberClass, "longValue", "()J")
                    : nullptr;
            if (numberClass == nullptr || nLong == nullptr || !env->IsInstanceOf(value, numberClass) ||
                clearException(env)) {
                env->ReleaseStringUTFChars(fieldName, name);
                return JNI_FALSE;
            }
            jlong v = env->CallLongMethod(value, nLong);
            if (clearException(env)) {
                env->ReleaseStringUTFChars(fieldName, name);
                return JNI_FALSE;
            }
            result = setLong(env, targetClass, name, v);
        }
    } else if (env->IsSameObject(typeObj, boolType)) {
        if (value == nullptr) {
            result = JNI_FALSE;
        } else {
            jmethodID boolValue = env->GetMethodID(booleanClass, "booleanValue", "()Z");
            if (boolValue == nullptr || !env->IsInstanceOf(value, booleanClass) || clearException(env)) {
                env->ReleaseStringUTFChars(fieldName, name);
                return JNI_FALSE;
            }
            jboolean v = env->CallBooleanMethod(value, boolValue);
            if (clearException(env)) {
                env->ReleaseStringUTFChars(fieldName, name);
                return JNI_FALSE;
            }
            result = setBoolean(env, targetClass, name, v);
        }
    } else if (env->IsSameObject(typeObj, stringClass) ||
               (value != nullptr && env->IsInstanceOf(value, stringClass)) ||
               value == nullptr) {
        // Default / String fields used by Build spoof.
        jstring str = nullptr;
        if (value != nullptr) {
            if (!env->IsInstanceOf(value, stringClass)) {
                // Call toString()
                jclass objClass = env->FindClass("java/lang/Object");
                jmethodID toString = objClass
                        ? env->GetMethodID(objClass, "toString", "()Ljava/lang/String;")
                        : nullptr;
                if (toString == nullptr || clearException(env)) {
                    env->ReleaseStringUTFChars(fieldName, name);
                    return JNI_FALSE;
                }
                str = static_cast<jstring>(env->CallObjectMethod(value, toString));
                if (clearException(env)) {
                    env->ReleaseStringUTFChars(fieldName, name);
                    return JNI_FALSE;
                }
            } else {
                str = static_cast<jstring>(value);
            }
        }
        result = setString(env, targetClass, name, str);
    } else {
        ALOGW("Unsupported static field type for %s", name);
        result = JNI_FALSE;
    }

    env->ReleaseStringUTFChars(fieldName, name);
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    ALOGD("libpixelify_build loaded (JNI Build spoof helpers)");
    return JNI_VERSION_1_6;
}
