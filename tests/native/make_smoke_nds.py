#!/usr/bin/env python3
"""Generate an original, minimal DS homebrew for real-core smoke tests."""

import argparse
import struct
from pathlib import Path


class Arm:
    def __init__(self):
        self.words = []
        self.labels = {}
        self.fixups = []

    def emit(self, word):
        self.words.append(word)

    def label(self, name):
        self.labels[name] = len(self.words)

    def literal(self, register, value):
        index = len(self.words)
        self.emit(0)
        self.fixups.append((index, register, value))

    def branch(self, label, condition=0xE):
        index = len(self.words)
        self.emit(0)
        self.fixups.append((index, condition, label))

    def build(self):
        constants = {}
        for index, field, target in self.fixups:
            if isinstance(target, str):
                delta = self.labels[target] - index - 2
                assert -(1 << 23) <= delta < (1 << 23)
                self.words[index] = (field << 28) | 0x0A000000 | (delta & 0xFFFFFF)
            else:
                if target not in constants:
                    constants[target] = len(self.words)
                    self.words.append(target)
                offset = (constants[target] - index - 2) * 4
                assert 0 <= offset <= 4095
                self.words[index] = 0xE59F0000 | (field << 12) | offset
        return struct.pack('<%dI' % len(self.words), *self.words)


def arm9():
    a = Arm()
    a.literal(0, 0x04000304)  # POWCNT1: LCD and main 2D engine
    a.literal(1, 0x00000203)
    a.emit(0xE1C010B0)       # strh r1, [r0]
    a.literal(0, 0x04000240)  # VRAM A in LCDC mode
    a.emit(0xE3A01080)       # mov r1, #0x80
    a.emit(0xE5C01000)       # strb r1, [r0]
    a.literal(0, 0x04000000)  # main display reads VRAM A
    a.literal(1, 0x00020000)
    a.emit(0xE5801000)       # str r1, [r0]
    a.literal(4, 0x06800000)
    a.literal(5, 0x06818000)  # 256 * 192 * 2 bytes
    a.literal(6, 0x04000130)  # KEYINPUT, active low
    a.label('poll')
    a.emit(0xE1D600B0)       # ldrh r0, [r6]
    a.emit(0xE3100001)       # tst r0, #1 (A)
    a.branch('pressed', 0x0)
    a.literal(1, 0x00007C00)  # blue, BGR555
    a.branch('fill')
    a.label('pressed')
    a.literal(1, 0x000003E0)  # green, BGR555
    a.label('fill')
    a.emit(0xE1A02004)       # mov r2, r4
    a.label('pixel')
    a.emit(0xE1C210B0)       # strh r1, [r2]
    a.emit(0xE2822002)       # add r2, r2, #2
    a.emit(0xE1520005)       # cmp r2, r5
    a.branch('pixel', 0x1)
    a.branch('poll')
    return a.build()


def arm7():
    a = Arm()
    a.literal(0, 0x04000304)  # POWCNT2 sound power
    a.emit(0xE3A01001)
    a.emit(0xE1C010B0)
    a.literal(0, 0x04000500)  # SOUNDCNT master enable
    a.literal(1, 0x0000807F)  # master volume 127 and enable
    a.emit(0xE1C010B0)
    a.literal(0, 0x04000488)  # channel timer
    a.literal(1, 0x0000F800)
    a.emit(0xE1C010B0)
    a.literal(0, 0x04000480)  # channel 8 PSG
    a.literal(1, 0xE340007F)  # enable, PSG, duty, center pan, max volume
    a.emit(0xE5801000)
    a.label('idle')
    a.branch('idle')
    return a.build()


def crc16(data):
    value = 0xFFFF
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ (0xA001 if value & 1 else 0)
    return value


def make_rom():
    nine, seven = arm9(), arm7()
    seven_offset = (0x200 + len(nine) + 0x1FF) & ~0x1FF
    # Upstream loader copies a full 4 KiB header and 9152-byte banner.
    rom = bytearray(32768)
    assert seven_offset + len(seven) <= len(rom)
    rom[:12] = b'Pocket Smoke'
    rom[12:16] = b'PSMK'
    rom[16:18] = b'00'
    struct.pack_into('<4I', rom, 0x20, 0x200, 0x02000000, 0x02000000, len(nine))
    struct.pack_into('<4I', rom, 0x30, seven_offset, 0x03800000, 0x03800000, len(seven))
    struct.pack_into('<I', rom, 0x80, len(rom))
    struct.pack_into('<I', rom, 0x84, 0x200)
    struct.pack_into('<H', rom, 0x15E, crc16(rom[:0x15E]))
    rom[0x200:0x200 + len(nine)] = nine
    rom[seven_offset:seven_offset + len(seven)] = seven
    return bytes(rom)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(make_rom())
