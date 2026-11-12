#include "stop_gate.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <mutex>
#include <thread>

int main() {
    PocketStopGate gate;
    const auto first = gate.Reset();
    std::mutex initialization;
    std::promise<void> entered;
    std::promise<void> release;
    auto allow_finish = release.get_future();
    std::atomic_bool started{false};
    std::thread worker([&] {
        std::lock_guard lock(initialization);
        entered.set_value();
        allow_finish.wait();
        started = !gate.Requested();
    });

    entered.get_future().wait();
    gate.Request(first); // Must complete while initialization still holds its mutex.
    assert(gate.Requested());
    release.set_value();
    worker.join();
    assert(!started);
    const auto second = gate.Reset();
    assert(!gate.Requested());
    assert(!gate.Request(first));
    assert(!gate.ApplyIfCurrent(first, [] { assert(false); }));

    std::promise<void> halt_entered;
    std::promise<void> release_halt;
    auto allow_halt = release_halt.get_future();
    std::thread old_halt([&] {
        gate.ApplyIfCurrent(second, [&] {
            halt_entered.set_value();
            allow_halt.wait();
        });
    });
    halt_entered.get_future().wait();
    assert(gate.Request(second)); // UI signal does not wait for the in-flight halt.
    auto next_reset = std::async(std::launch::async, [&] { return gate.Reset(); });
    assert(next_reset.wait_for(std::chrono::milliseconds(100)) == std::future_status::timeout);
    release_halt.set_value();
    old_halt.join();
    const auto third = next_reset.get();
    assert(third > second);
    assert(!gate.Request(second));
    assert(!gate.ApplyIfCurrent(second, [] { assert(false); }));
    assert(gate.Request(third));
    assert(gate.Requested());
}
