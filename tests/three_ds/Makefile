.SUFFIXES:
.DEFAULT_GOAL := all

ifeq ($(strip $(DEVKITARM)),)
$(error Set DEVKITARM to the official devkitPro devkitARM toolchain)
endif

include $(DEVKITARM)/3ds_rules

ARCH := -march=armv6k -mtune=mpcore -mfloat-abi=hard -mtp=soft
CFLAGS := -O2 -Wall -Wextra -mword-relocations -ffunction-sections $(ARCH) -D__3DS__ -I$(DEVKITPRO)/libctru/include
LDFLAGS := -specs=3dsx.specs $(ARCH) -Wl,--gc-sections

all: smoke.3dsx

smoke.o: smoke.c
	$(CC) $(CFLAGS) -c $< -o $@

smoke.elf: smoke.o
	$(CC) $(LDFLAGS) $< -L$(DEVKITPRO)/libctru/lib -lctru -lm -o $@

smoke.3dsx: smoke.elf
	3dsxtool $< $@

.PHONY: all
