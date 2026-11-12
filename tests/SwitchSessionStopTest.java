package dev.pocketconsole;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SwitchSessionStopTest {
    public static void main(String[] args) throws Exception {
        AtomicBoolean nativeToken = new AtomicBoolean(true);
        AtomicLong nativeGeneration = new AtomicLong();
        ArrayDeque<Runnable> control = new ArrayDeque<>();
        AtomicInteger halts = new AtomicInteger();
        AtomicInteger controls = new AtomicInteger();
        SwitchSessionStop first = new SwitchSessionStop(control::add,
                generation -> { if (generation == nativeGeneration.get()) nativeToken.set(true); },
                generation -> { if (generation == nativeGeneration.get()) halts.incrementAndGet(); });
        if (!first.arm(() -> { nativeToken.set(false); return nativeGeneration.incrementAndGet(); })
                || nativeToken.get())
            throw new AssertionError("new session did not clear stale stop token");
        first.control(generation -> controls.incrementAndGet());
        first.request();
        first.request();
        if (!nativeToken.get() || control.size() != 2)
            throw new AssertionError("UI stop was not immediate and idempotent");
        first.finish();

        SwitchSessionStop second = new SwitchSessionStop(control::add,
                generation -> { if (generation == nativeGeneration.get()) nativeToken.set(true); },
                generation -> { if (generation == nativeGeneration.get()) halts.incrementAndGet(); });
        if (!second.arm(() -> { nativeToken.set(false); return nativeGeneration.incrementAndGet(); })
                || nativeToken.get())
            throw new AssertionError("reopened activity kept previous stop token");
        first.request();
        if (nativeToken.get() || control.size() != 2)
            throw new AssertionError("finished activity signaled new session");
        control.remove().run();
        control.remove().run();
        if (halts.get() != 0 || controls.get() != 0)
            throw new AssertionError("queued old native action reached new session");
        second.request();
        if (!nativeToken.get() || control.size() != 1)
            throw new AssertionError("new activity did not signal its own stop");
        control.remove().run();
        if (halts.get() != 1) throw new AssertionError("new session halt was not dispatched");

        Object initialization = new Object();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch resetEntered = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            SwitchSessionStop blockedReset = new SwitchSessionStop(workers,
                    generation -> nativeToken.set(true), generation -> halts.incrementAndGet());
            Future<Boolean> arming = workers.submit(() -> blockedReset.arm(() -> {
                resetEntered.countDown();
                try { releaseReset.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                nativeToken.set(false);
                return nativeGeneration.incrementAndGet();
            }));
            if (!resetEntered.await(2, TimeUnit.SECONDS))
                throw new AssertionError("native reset did not block");
            long resetBefore = System.nanoTime();
            blockedReset.request();
            if (System.nanoTime() - resetBefore > TimeUnit.SECONDS.toNanos(1))
                throw new AssertionError("UI waited for old native halt during reset");
            releaseReset.countDown();
            if (arming.get(2, TimeUnit.SECONDS) || !nativeToken.get())
                throw new AssertionError("cancelled reset armed a new native session");

            Future<?> loading = workers.submit(() -> {
                synchronized (initialization) {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
            });
            if (!entered.await(2, TimeUnit.SECONDS))
                throw new AssertionError("initialization did not block");
            SwitchSessionStop blocked = new SwitchSessionStop(workers,
                    generation -> nativeToken.set(true), generation -> {
                        synchronized (initialization) { halts.incrementAndGet(); }
                    });
            if (!blocked.arm(() -> { nativeToken.set(false); return nativeGeneration.incrementAndGet(); }))
                throw new AssertionError("blocked session did not arm");
            long before = System.nanoTime();
            blocked.request();
            if (!nativeToken.get() || System.nanoTime() - before > TimeUnit.SECONDS.toNanos(1))
                throw new AssertionError("UI waited for native initialization mutex");
            release.countDown();
            loading.get(2, TimeUnit.SECONDS);
            workers.shutdown();
            if (!workers.awaitTermination(2, TimeUnit.SECONDS) || halts.get() != 2)
                throw new AssertionError("blocking halt did not finish on control worker");
        } finally {
            releaseReset.countDown();
            release.countDown();
            workers.shutdownNow();
        }
        System.out.println("SWITCH_SESSION_STOP_PASS");
    }
}
