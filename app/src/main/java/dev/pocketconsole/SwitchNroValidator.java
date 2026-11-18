package dev.pocketconsole;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Checks the private NRO copy before the pinned native loader reads it. */
final class SwitchNroValidator {
    private static final long PAGE = 0x1000;
    private static final long MAX_IMAGE = 2L * 1024 * 1024 * 1024;

    private SwitchNroValidator() {}

    static void validate(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length < 0x80) throw new IOException("NRO 파일이 너무 짧아요.");
            byte[] header = new byte[0x80];
            input.readFully(header);
            if (header[0x10] != 'N' || header[0x11] != 'R'
                    || header[0x12] != 'O' || header[0x13] != '0')
                throw new IOException("NRO 헤더를 확인할 수 없어요.");

            long size = word(header, 0x18);
            // The pinned loader copies PageAlignSize(file_size) bytes from the file.
            if (size < 0x80 || size > MAX_IMAGE || pageSize(size, "선언 크기") > length)
                throw new IOException("NRO 선언 크기가 실제 파일 크기와 맞지 않아요.");
            long imageSize = pageSize(size, "선언 크기");

            String[] names = {"텍스트", "읽기 전용", "데이터"};
            long previousEnd = 0;
            for (int i = 0; i < names.length; i++) {
                long offset = word(header, 0x20 + i * 8);
                long bytes = word(header, 0x24 + i * 8);
                long alignedBytes = pageSize(bytes, "세그먼트");
                if ((i == 0 && (offset != 0 || bytes == 0)) || offset < previousEnd
                        || offset % PAGE != 0 || offset > size || bytes > size - offset
                        || alignedBytes > imageSize - offset)
                    throw new IOException("NRO " + names[i] + " 세그먼트 범위 또는 정렬이 잘못됐어요.");
                previousEnd = offset + alignedBytes;
            }

            long moduleOffset = word(header, 4);
            if (moduleOffset > imageSize - 0x1c)
                throw new IOException("NRO MOD 헤더 위치가 파일 범위를 벗어났어요.");
            byte[] module = new byte[0x1c];
            input.seek(moduleOffset);
            input.readFully(module);

            long bss = pageSize(word(header, 0x38), "BSS");
            if (module[0] == 'M' && module[1] == 'O' && module[2] == 'D' && module[3] == '0') {
                long start = word(module, 8);
                long end = word(module, 12);
                if (end < start) throw new IOException("NRO MOD BSS 범위가 잘못됐어요.");
                bss = pageSize(end - start, "BSS");
            }
            if (bss > MAX_IMAGE - imageSize)
                throw new IOException("NRO BSS 크기가 실행 이미지 한도를 넘어요.");

            // An ASET trailer may trigger native icon allocation from its declared size.
            if (length >= size + 0x38) {
                byte[] asset = new byte[0x38];
                input.seek(size);
                input.readFully(asset);
                if (asset[0] == 'A' && asset[1] == 'S' && asset[2] == 'E' && asset[3] == 'T') {
                    long available = length - size;
                    for (int offset : new int[]{8, 24, 40}) {
                        long sectionOffset = longWord(asset, offset);
                        long sectionSize = longWord(asset, offset + 8);
                        if (sectionSize != 0 && (sectionOffset < 0 || sectionSize < 0
                                || sectionOffset > available || sectionSize > available - sectionOffset))
                            throw new IOException("NRO 부록 섹션 범위가 실제 파일을 벗어났어요.");
                    }
                }
            }
        }
    }

    private static long pageSize(long size, String field) throws IOException {
        if (size > 0xffff_ffffL - (PAGE - 1))
            throw new IOException("NRO " + field + " 크기 정렬이 32비트 범위를 넘어요.");
        return (size + PAGE - 1) & -PAGE;
    }

    private static long word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static long longWord(byte[] bytes, int offset) {
        return word(bytes, offset) | (word(bytes, offset + 4) << 32);
    }
}
