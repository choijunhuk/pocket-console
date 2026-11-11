#include "DsEngine.h"

#include <jni.h>

#include <exception>
#include <stdexcept>
#include <string>

namespace {

void throwIo(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type) env->ThrowNew(type, message);
}

std::string stringValue(JNIEnv* env, jstring value) {
    if (!value) throw std::runtime_error("Missing DS path");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read DS path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pocketconsole_DsNative_open(JNIEnv* env, jclass, jstring rom, jstring system, jstring saves) {
    try {
        return static_cast<jlong>(pocket::open(stringValue(env, rom), stringValue(env, system),
                                               stringValue(env, saves)));
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    } catch (...) {
        throwIo(env, "DS core failed to open");
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketconsole_DsNative_frame(JNIEnv* env, jclass, jlong token, jintArray pixels,
                                       jshortArray audio, jint mask, jint x, jint y, jboolean down) {
    if (!pixels || !audio || env->GetArrayLength(pixels) < 256 * 384 ||
        env->GetArrayLength(audio) < 8192) {
        throwIo(env, "DS output buffer is too small");
        return 0;
    }
    jint* pixelData = env->GetIntArrayElements(pixels, nullptr);
    if (!pixelData) return 0;
    jshort* audioData = env->GetShortArrayElements(audio, nullptr);
    if (!audioData) {
        env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);
        return 0;
    }
    try {
        const size_t frames = pocket::frame(static_cast<uint64_t>(token), mask, x, y, down == JNI_TRUE,
                                            reinterpret_cast<uint32_t*>(pixelData), 256 * 384,
                                            reinterpret_cast<int16_t*>(audioData), 8192);
        env->ReleaseIntArrayElements(pixels, pixelData, 0);
        env->ReleaseShortArrayElements(audio, audioData, 0);
        return static_cast<jint>(frames);
    } catch (const std::exception& error) {
        env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);
        env->ReleaseShortArrayElements(audio, audioData, JNI_ABORT);
        throwIo(env, error.what());
    } catch (...) {
        env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);
        env->ReleaseShortArrayElements(audio, audioData, JNI_ABORT);
        throwIo(env, "DS core failed to render");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pocketconsole_DsNative_close(JNIEnv* env, jclass, jlong token) {
    try {
        pocket::close(static_cast<uint64_t>(token));
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    } catch (...) {
        throwIo(env, "DS core failed to close");
    }
}
