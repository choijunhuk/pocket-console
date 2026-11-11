APP_ABI := arm64-v8a x86_64
APP_PLATFORM := android-26
APP_STL := c++_shared
APP_CPPFLAGS := -std=c++17 -fexceptions
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
APP_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
