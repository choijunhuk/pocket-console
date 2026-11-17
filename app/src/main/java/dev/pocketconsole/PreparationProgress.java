package dev.pocketconsole;

import java.util.Locale;
import java.util.function.LongSupplier;

final class PreparationProgress {
    private static final long MIB = 1024L * 1024;
    private final LongSupplier clock;
    private final long startedAt;
    private long changedAt;
    private String stage = "파일 열기";
    private long completed;
    private long total;

    PreparationProgress() { this(System::nanoTime); }

    PreparationProgress(LongSupplier clock) {
        this.clock = clock;
        startedAt = changedAt = clock.getAsLong();
    }

    synchronized void update(String stage, long completed, long total) {
        long now = clock.getAsLong();
        long safeCompleted = Math.max(0, completed);
        if (!this.stage.equals(stage) || this.completed != safeCompleted) changedAt = now;
        this.stage = stage;
        this.completed = safeCompleted;
        this.total = total;
    }

    synchronized String message() {
        long now = clock.getAsLong();
        long seconds = Math.max(0, (now - startedAt) / 1_000_000_000L);
        StringBuilder result = new StringBuilder(stage);
        if (stage.equals("파일 복사") || stage.equals("기존 캐시 확인")) {
            if (total > 0 && completed <= total) {
                result.append(" ").append((int) ((double) completed / total * 100)).append("% · ")
                        .append(mib(completed)).append(" / ").append(mib(total)).append(" MiB");
            } else {
                result.append(" · ").append(mib(completed)).append(" MiB 처리 · 전체 크기 확인 불가");
            }
        }
        result.append(" · ").append(seconds).append("초 경과");
        if (now - changedAt >= 15_000_000_000L) {
            result.append(" · 오래 걸리고 있어요. 라이브러리에서 취소를 요청할 수 있어요. 작업 응답 후 종료됩니다.");
        }
        return result.toString();
    }

    private static String mib(long bytes) {
        return String.format(Locale.KOREA, "%.1f", (double) bytes / MIB);
    }
}
