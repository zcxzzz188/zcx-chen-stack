package com.zcx.chenstack.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "chen.knowledge.simple", name = "enabled", havingValue = "true")
public class KnowledgeFiles {
    private final Path root;
    private final Path curated;

    public KnowledgeFiles(@Value("${chen.knowledge.simple.uploads:/data/knowledge/uploads}") Path root) throws IOException {
        this.root = Files.createDirectories(root.toAbsolutePath().normalize());
        this.curated = Files.createDirectories(this.root.resolveSibling("curated"));
        verifyRoot(this.root);
        verifyRoot(this.curated);
    }

    public String save(byte[] bytes, String extension) {
        return save(bytes, extension, false);
    }

    public String saveCurated(byte[] bytes) {
        return save(bytes, "md", true);
    }

    private String save(byte[] bytes, String extension, boolean module) {
        String key = (module ? "curated/" : "") + HexFormat.of().formatHex(sha256(bytes)) + "." + extension;
        Path target = resolve(key);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                read(key, bytes.length);
                return key;
            }
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return key;
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    public byte[] read(String key, int size) {
        Path file = resolve(key);
        if (size <= 0 || size > KnowledgeTextParser.MAX_FILE_BYTES || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(size + 1);
            if (bytes.length != size || !file.getFileName().toString().startsWith(HexFormat.of().formatHex(sha256(bytes)) + ".")) {
                throw unavailable();
            }
            return bytes;
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("(?:[a-f0-9]{64}\\.(?:md|txt)|curated/[a-f0-9]{64}\\.md)")) {
            throw unavailable();
        }
        Path directory = isCurated(key) ? curated : root;
        try {
            verifyRoot(directory);
        } catch (IOException failure) {
            throw unavailable();
        }
        return directory.resolve(key.substring(key.lastIndexOf('/') + 1));
    }

    static boolean isCurated(String key) {
        return key != null && key.startsWith("curated/");
    }

    private static void verifyRoot(Path directory) throws IOException {
        if (!directory.equals(directory.toRealPath())) {
            throw new IOException("知识文件目录不允许使用符号链接");
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static KnowledgeDocException unavailable() {
        return KnowledgeDocException.unavailable("KNOWLEDGE_FILE_UNAVAILABLE", "知识原文件暂不可用");
    }
}
