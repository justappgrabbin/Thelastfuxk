package app.lastfuck;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class RuntimeFiles {
    static String read(InputStream stream, int limit) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (out.size() < limit) {
                int count = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
                if (count < 0) break;
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }

    static void write(File file, String text) throws IOException {
        Path target = file.toPath();
        Path stage = Files.createTempFile(target.getParent(), ".writing-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(stage.toFile())) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    static void deleteTree(File path) throws IOException {
        if (Files.isDirectory(path.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] children = path.listFiles();
            if (children == null) throw new IOException("Cannot list " + path);
            for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(path.toPath());
    }
}
