package io.droyagent.app;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Pinned DSH storage locations; model settings and credentials live outside these paths. */
final class HistoryData {
    static void clear(Path home) throws IOException {
        if (Files.isSymbolicLink(home)) throw new IOException(io.droyagent.app.NativeLocale.text("会话目录不能是符号链接", "The session directory must not be a symbolic link"));
        for (String relative : new String[]{"sessions", "attachments", "storages/workspace.json",
                "storages/workspace", "storages/session_projcache", "storages/session_projcache.json"}) {
            Path target = home.resolve(relative);
            // Never traverse a linked storage parent into unrelated application data.
            if (Files.isSymbolicLink(target.getParent())) throw new IOException(io.droyagent.app.NativeLocale.text("会话存储目录无效", "Invalid session storage directory"));
            deleteTree(target);
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
