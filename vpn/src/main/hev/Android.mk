ADAPTIVE_PATH := $(call my-dir)
HEV_ROOT := $(ADAPTIVE_PATH)/../../../../.native/hev
ifeq ($(wildcard $(HEV_ROOT)/Android.mk),)
    $(error Run python scripts/prepare-hev.py before building)
endif

include $(HEV_ROOT)/third-part/yaml/Android.mk
include $(HEV_ROOT)/third-part/lwip/Android.mk
ENABLE_MEMALLOC_SLICE := 0
include $(HEV_ROOT)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(ADAPTIVE_PATH)
SRCDIR := $(HEV_ROOT)/src
REV_ID := fc3f900
include $(HEV_ROOT)/build.mk

include $(CLEAR_VARS)
LOCAL_MODULE := adaptive-hev
LOCAL_SRC_FILES := bridge.c ../../../../.native/hev-socks5-tunnel-atomic.c ../../../../.native/hev-tunnel-linux-diagnostic.c \
    $(patsubst $(HEV_ROOT)/%,../../../../.native/hev/%, \
        $(filter-out $(SRCDIR)/hev-jni.c $(SRCDIR)/hev-socks5-tunnel.c $(SRCDIR)/hev-tunnel-linux.c,$(SRCFILES)))
LOCAL_C_INCLUDES := $(ADAPTIVE_PATH) $(SRCDIR) $(SRCDIR)/misc $(SRCDIR)/core/include \
    $(HEV_ROOT)/third-part/yaml/include $(HEV_ROOT)/third-part/lwip/src/include \
    $(HEV_ROOT)/third-part/lwip/src/ports/include $(HEV_ROOT)/third-part/hev-task-system/include
LOCAL_CFLAGS := -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY -fno-stack-clash-protection $(VERSION_CFLAGS)
ifeq ($(ADAPTIVE_TEST_HOOKS),1)
LOCAL_CFLAGS += -DADAPTIVE_TEST_HOOKS
# Эксперимент 1.6: операции над своим сокетом только в debug, в release их нет (docs/ROADMAP.md, 1.6).
LOCAL_SRC_FILES += adaptive_ops.c
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
