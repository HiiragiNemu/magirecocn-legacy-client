#pragma once
#include <cstdint>
#include <cstdarg>
typedef int jint; typedef long long jlong; typedef unsigned char jboolean;
typedef float jfloat; typedef struct _jobject* jobject; typedef jobject jclass;
typedef jobject jstring; typedef jobject jbyteArray; typedef jobject jobjectArray; typedef jint jsize; typedef jobject jthrowable;
typedef struct _jmethodID* jmethodID; typedef struct _jfieldID* jfieldID;
struct JNINativeMethod { const char* name; const char* signature; void* fnPtr; };
#define JNIEXPORT
#define JNICALL
#define JNI_VERSION_1_6 0x00010006
#define JNI_OK 0
#define JNI_ERR (-1)
#define JNI_TRUE 1
#define JNI_FALSE 0
struct JNIEnv {
  jclass FindClass(const char*); jclass GetObjectClass(jobject);
  jmethodID GetStaticMethodID(jclass,const char*,const char*);
  jmethodID GetMethodID(jclass,const char*,const char*);
  jobject NewGlobalRef(jobject); void DeleteGlobalRef(jobject);
  void DeleteLocalRef(jobject);
  jstring NewStringUTF(const char*); const char* GetStringUTFChars(jstring,jboolean*);
  void ReleaseStringUTFChars(jstring,const char*); jint GetStringUTFLength(jstring);
  jboolean ExceptionCheck(); void ExceptionClear(); void ExceptionDescribe();
  void CallStaticVoidMethod(jclass,jmethodID,...);
  jobject CallStaticObjectMethod(jclass,jmethodID,...);
  jint CallStaticIntMethod(jclass,jmethodID,...);
  jboolean CallStaticBooleanMethod(jclass,jmethodID,...);
  void CallVoidMethod(jobject,jmethodID,...);
  jobject CallObjectMethod(jobject,jmethodID,...);
  jsize GetArrayLength(jobjectArray);
  jobject GetObjectArrayElement(jobjectArray, jsize);
  jint RegisterNatives(jclass,const JNINativeMethod*,jint);
};
struct JavaVM { jint GetEnv(void**,jint); jint AttachCurrentThread(JNIEnv**,void*); jint DetachCurrentThread(); };
