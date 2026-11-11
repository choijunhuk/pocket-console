LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := pocket_3ds
LOCAL_SRC_FILES := ThreeDsEngine.cpp three_ds_jni.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../../third_party/melonDS/src/libretro/libretro-common/include
LOCAL_CPPFLAGS := -std=c++17 -fexceptions
LOCAL_LDLIBS := -lEGL -lGLESv3 -ldl
include $(BUILD_SHARED_LIBRARY)
