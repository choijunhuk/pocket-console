// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <utility>

class PocketStopGate {
public:
    std::uint64_t Reset() {
        std::lock_guard lock(lifecycle);
        const auto next = (state.load(std::memory_order_relaxed) >> 1) + 1;
        state.store(next << 1, std::memory_order_release);
        return next;
    }

    bool Request(std::uint64_t generation) noexcept {
        if (generation == 0) return false;
        auto expected = generation << 1;
        return state.compare_exchange_strong(expected, expected | 1,
                                             std::memory_order_acq_rel,
                                             std::memory_order_acquire)
               || expected == ((generation << 1) | 1);
    }

    bool Requested() const noexcept {
        return (state.load(std::memory_order_acquire) & 1) != 0;
    }

    template <typename Stop>
    bool ApplyIfCurrent(std::uint64_t generation, Stop&& action) {
        std::lock_guard lock(lifecycle);
        if (generation == 0 || (state.load(std::memory_order_acquire) >> 1) != generation)
            return false;
        std::forward<Stop>(action)();
        return true;
    }

private:
    std::atomic<std::uint64_t> state{0}; // High bits: session generation; low bit: stop.
    std::mutex lifecycle; // Prevents a stale Halt from crossing a new-session reset.
};

extern PocketStopGate pocket_stop_gate;
