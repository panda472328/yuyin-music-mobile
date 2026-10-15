package com.yuyin.music.mobile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;

/** Document streams only; the caller obtains access through Android's system picker. */
final class BackupFiles {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static String read(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("BACKUP_UNREADABLE");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
        while ((count = input.read(block)) != -1) { if (bytes.size() + count > MAX_BYTES) throw new IllegalArgumentException("BACKUP_TOO_LARGE"); bytes.write(block, 0, count); }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    static void write(OutputStream output, String data) throws Exception {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (output == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("BACKUP_UNWRITABLE");
        output.write(bytes); output.flush();
    }
}
