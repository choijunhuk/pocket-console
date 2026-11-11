#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace pocket3ds {

uint64_t open(const std::string& rom, const std::string& userRoot, const std::string& systemRoot);
void configuration(uint64_t token, int metrics[4]);
int frame(uint64_t token, int buttons, int touchX, int touchY, bool down, int circleX, int circleY,
          uint32_t* pixels, size_t pixelCapacity, int16_t* audio, size_t audioCapacity);
void close(uint64_t token);

}  // namespace pocket3ds
