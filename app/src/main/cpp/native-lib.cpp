#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_moon_aiphone_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello M叽";
    return env->NewStringUTF(hello.c_str());
}