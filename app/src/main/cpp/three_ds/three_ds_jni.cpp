#include "ThreeDsEngine.h"

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
    if (!value) throw std::runtime_error("Missing 3DS path");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read 3DS path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pocketconsole_ThreeDsNative_open(JNIEnv* env, jclass, jstring rom, jstring userRoot,
                                           jstring systemRoot) {
    try {
        return static_cast<jlong>(pocket3ds::open(stringValue(env, rom), stringValue(env, userRoot),
                                                  stringValue(env, systemRoot)));
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    } catch (...) {
        throwIo(env, "3DS core failed to open");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pocketconsole_ThreeDsNative_configuration(JNIEnv* env, jclass, jlong token,
                                                    jintArray metrics) {
    if (!metrics || env->GetArrayLength(metrics) < 4) {
        throwIo(env, "3DS metrics array is too small");
        return;
    }
    int values[4];
    try {
        pocket3ds::configuration(static_cast<uint64_t>(token), values);
        const jint result[4] = {values[0], values[1], values[2], values[3]};
        env->SetIntArrayRegion(metrics, 0, 4, result);
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    } catch (...) {
        throwIo(env, "3DS configuration failed");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketconsole_ThreeDsNative_frame(JNIEnv* env, jclass, jlong token, jintArray pixels,
                                            jshortArray audio, jint buttons, jint touchX, jint touchY,
                                            jboolean down, jint circleX, jint circleY) {
    if (!pixels || !audio || env->GetArrayLength(pixels) < 400 * 480 ||
        env->GetArrayLength(audio) < 16384) {
        throwIo(env, "3DS output array is too small");
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
        const int frames = pocket3ds::frame(static_cast<uint64_t>(token), buttons, touchX, touchY,
                                             down == JNI_TRUE, circleX, circleY,
                                             reinterpret_cast<uint32_t*>(pixelData), 400 * 480,
                                             reinterpret_cast<int16_t*>(audioData), 16384);
        env->ReleaseIntArrayElements(pixels, pixelData, frames < 0 ? JNI_ABORT : 0);
        env->ReleaseShortArrayElements(audio, audioData, frames < 0 ? JNI_ABORT : 0);
        return frames;
    } catch (const std::exception& error) {
        env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);
        env->ReleaseShortArrayElements(audio, audioData, JNI_ABORT);
        throwIo(env, error.what());
    } catch (...) {
        env->ReleaseIntArrayElements(pixels, pixelData, JNI_ABORT);
        env->ReleaseShortArrayElements(audio, audioData, JNI_ABORT);
        throwIo(env, "3DS core failed to render");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pocketconsole_ThreeDsNative_close(JNIEnv* env, jclass, jlong token) {
    try {
        pocket3ds::close(static_cast<uint64_t>(token));
    } catch (const std::exception& error) {
        throwIo(env, error.what());
    } catch (...) {
        throwIo(env, "3DS core failed to close");
    }
}
