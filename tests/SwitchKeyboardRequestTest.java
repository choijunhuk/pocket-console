package org.yuzu.yuzu_emu.applets.keyboard;

import java.util.concurrent.atomic.AtomicReference;

public final class SwitchKeyboardRequestTest {
    public static void main(String[] args) throws Exception {
        Object oldOwner = new Object();
        Object newOwner = new Object();
        SoftwareKeyboard.Pending pending = new SoftwareKeyboard.Pending(oldOwner);
        AtomicReference<SoftwareKeyboard.KeyboardData> answer = new AtomicReference<>();
        Thread waiting = new Thread(() -> {
            try { answer.set(pending.await()); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        waiting.start();
        SoftwareKeyboard.activeRequest.set(pending); // UI show is still queued.
        SoftwareKeyboard.cancelOutstanding(oldOwner);
        if (pending.mayShow() || SoftwareKeyboard.activeRequest.get() != null)
            throw new AssertionError("queued dialog remained displayable after cancellation");
        waiting.join(2000);
        if (waiting.isAlive() || answer.get() == null || answer.get().result != 1)
            throw new AssertionError("keyboard worker stayed blocked after cancellation");
        if (pending.complete(0, "late") || answer.get().result != 1)
            throw new AssertionError("late UI accept overwrote cancellation");

        SoftwareKeyboard.Pending reopened = new SoftwareKeyboard.Pending(newOwner);
        SoftwareKeyboard.activeRequest.set(reopened);
        SoftwareKeyboard.cancelOutstanding(oldOwner);
        if (SoftwareKeyboard.activeRequest.get() != reopened || !reopened.mayShow())
            throw new AssertionError("old activity cancelled reopened keyboard");
        SoftwareKeyboard.cancelOutstanding(newOwner);
        if (reopened.mayShow()) throw new AssertionError("new owner cancellation was ignored");

        SoftwareKeyboard.Pending accepted = new SoftwareKeyboard.Pending(newOwner);
        if (!accepted.complete(0, "ok") || accepted.await().result != 0)
            throw new AssertionError("normal keyboard accept failed");
        System.out.println("SWITCH_KEYBOARD_CANCEL_PASS");
    }
}
