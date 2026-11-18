package dev.pocketconsole;

import java.util.concurrent.atomic.AtomicLong;

public final class PreparationProgressTest {
    public static void main(String[] args) {
        AtomicLong now = new AtomicLong();
        PreparationProgress progress = new PreparationProgress(now::get);
        check(progress.message().contains("파일 열기"), "initial phase");
        progress.update("파일 복사", 512 * 1024L, 1024 * 1024L);
        check(progress.message().contains("50%"), "known size percentage");
        check(progress.message().contains("0.5 / 1.0 MiB"), "known byte counts");
        progress.update("파일 복사", 1024 * 1024L, -1);
        check(!progress.message().contains("%"), "unknown size cannot show percentage");
        check(progress.message().contains("전체 크기 확인 불가"), "unknown size explanation");
        progress.update("파일 복사", 2 * 1024 * 1024L, 1024 * 1024L);
        check(!progress.message().contains("%"), "stale size cannot show percentage");
        progress.update("파일 복사", 4L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024);
        check(progress.message().contains("50%"), "8 GiB progress does not overflow");
        progress.update("코어 초기화", 0, 0);
        now.set(16_000_000_000L);
        check(progress.message().contains("16초"), "elapsed seconds");
        check(progress.message().contains("라이브러리"), "stall guidance");
        check(!progress.message(false).contains("취소"), "copy-only preparation must not promise cancellation");
        progress.update("첫 화면 대기", 0, 0);
        check(!progress.message().contains("라이브러리"), "phase change clears stall");
        progress.update("파일 복사", 0, 0);
        check(!progress.message().contains("%"), "zero total cannot show percentage");
        System.out.println("PASS: preparation progress formatting");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
