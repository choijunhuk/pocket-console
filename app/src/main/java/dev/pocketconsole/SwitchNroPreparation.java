package dev.pocketconsole;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Cancellation belongs to one private copy, never the shared document import queue. */
final class SwitchNroPreparation {
    private volatile boolean cancelled;

    void cancel() { cancelled = true; }
    boolean isCancelled() { return cancelled; }

    void checkCancelled() throws IOException {
        if (cancelled) throw new IOException("Switch 파일 준비를 취소했어요.");
    }

    void copy(InputStream source, OutputStream output, PreparationProgress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        progress.update("파일 복사", 0, -1);
        while (true) {
            checkCancelled();
            int count = source.read(buffer);
            if (count == -1) break;
            if (count == 0) {
                int one = source.read();
                if (one == -1) break;
                buffer[0] = (byte) one;
                count = 1;
            }
            checkCancelled();
            if (count > 2L * 1024 * 1024 * 1024 - size)
                throw new IOException("NRO 파일이 2 GiB를 넘습니다.");
            output.write(buffer, 0, count);
            size += count;
            progress.update("파일 복사", size, -1);
        }
        checkCancelled();
    }
}
