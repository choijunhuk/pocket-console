.SUFFIXES:

ifeq ($(strip $(DEVKITPRO)),)
$(error DEVKITPRO is required)
endif

include $(DEVKITPRO)/libnx/switch_rules

ARCH := -march=armv8-a+crc+crypto -mtune=cortex-a57 -mtp=soft -fPIE
CFLAGS := -O2 -Wall -Wextra -Werror -ffunction-sections $(ARCH) -D__SWITCH__ -I$(DEVKITPRO)/libnx/include
LDFLAGS := -specs=$(DEVKITPRO)/libnx/switch.specs $(ARCH) -Wl,--gc-sections

.PHONY: all
all: smoke.nro

smoke.o: smoke.c
	$(CC) $(CFLAGS) -c $< -o $@

smoke.elf: smoke.o
	$(CC) $(LDFLAGS) -o $@ $< -L$(DEVKITPRO)/libnx/lib -lnx

smoke.nro: smoke.elf
	elf2nro $< $@
