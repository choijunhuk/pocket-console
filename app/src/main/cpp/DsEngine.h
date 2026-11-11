#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace pocket {

uint64_t open(const std::string& rom, const std::string& system, const std::string& saves);
size_t frame(uint64_t token, int mask, int x, int y, bool down,
             uint32_t* pixels, size_t pixelCapacity,
             int16_t* audio, size_t audioCapacity);
void close(uint64_t token);

}  // namespace pocket
