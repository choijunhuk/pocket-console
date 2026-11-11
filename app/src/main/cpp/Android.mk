POCKET_LOCAL_PATH := $(call my-dir)

include $(POCKET_LOCAL_PATH)/../../../../third_party/melonDS/jni/Android.mk

LOCAL_PATH := $(POCKET_LOCAL_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := pocket_ds
LOCAL_SRC_FILES := DsEngine.cpp ds_jni.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../third_party/melonDS/src/libretro/libretro-common/include
LOCAL_CPPFLAGS := -std=c++17 -fexceptions
LOCAL_SHARED_LIBRARIES := retro
include $(BUILD_SHARED_LIBRARY)

include $(POCKET_LOCAL_PATH)/three_ds/Android.mk
