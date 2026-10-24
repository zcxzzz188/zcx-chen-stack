package com.zcx.chenstack.rag;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/** 发布只接受当前正在运行的归档，开发目录和默认 UNSET 构建均失败关闭。 */
public final class PublicationBuildIdentity {
    private static final String METADATA = "META-INF/chen-release-build.properties";
    private static final String BOOT_CLASSES = "BOOT-INF/classes/";
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;

    private PublicationBuildIdentity() { }

    public record Identity(String gitCommit, String artifactSha256, Map<String, byte[]> resources) {
        public Identity { resources = Map.copyOf(resources); }
    }

    public static Identity read(Path configuredArtifact) {
        try {
            URI source = PublicationBuildIdentity.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return inspect(runtimeJar(source), configuredArtifact);
        } catch (Exception exception) {
            throw new SecurityException("PUBLICATION_BUILD_IDENTITY_REJECTED");
        }
    }

    static Identity inspect(Path runningArtifact, Path configuredArtifact) {
        try {
            PublicationManifest.require(configuredArtifact != null && Files.isRegularFile(runningArtifact)
                    && Files.isRegularFile(configuredArtifact) && Files.isSameFile(runningArtifact, configuredArtifact),
                    "PUBLICATION_ARTIFACT_MISMATCH");
            var before = Files.readAttributes(runningArtifact, java.nio.file.attribute.BasicFileAttributes.class);
            PublicationManifest.require(before.size() > 0 && before.size() <= MAX_ARTIFACT_BYTES,
                    "PUBLICATION_ARTIFACT_SIZE_INVALID");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(runningArtifact)) {
                byte[] buffer = new byte[65_536]; long count = 0; int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    PublicationManifest.require(count <= MAX_ARTIFACT_BYTES, "PUBLICATION_ARTIFACT_SIZE_INVALID");
                    digest.update(buffer, 0, read);
                }
            }
            String artifactHash = HexFormat.of().formatHex(digest.digest());
            Map<String, byte[]> resources = new LinkedHashMap<>();
            String git;
            try (JarFile jar = new JarFile(runningArtifact.toFile())) {
                Set<String> expected = Set.of(METADATA, PublicationManifest.PLATFORM_HELP_RESOURCE,
                        PublicationManifest.CAPABILITIES_RESOURCE);
                Map<String, Integer> counts = new HashMap<>();
                jar.stream().forEach(entry -> {
                    String name = entry.getName().startsWith(BOOT_CLASSES)
                            ? entry.getName().substring(BOOT_CLASSES.length()) : entry.getName();
                    if (expected.contains(name)) counts.merge(name, 1, Integer::sum);
                });
                PublicationManifest.require(expected.stream().allMatch(name -> counts.getOrDefault(name, 0) == 1),
                        "PUBLICATION_ARTIFACT_RESOURCES_INVALID");
                String metadataPrefix = jar.getJarEntry(BOOT_CLASSES + METADATA) == null ? "" : BOOT_CLASSES;
                byte[] metadata = entry(jar, metadataPrefix + METADATA, 4_096);
                Properties properties = new Properties();
                properties.load(new StringReader(new String(metadata, StandardCharsets.UTF_8)));
                git = properties.getProperty("gitCommit");
                PublicationManifest.require(properties.stringPropertyNames().equals(Set.of("gitCommit"))
                        && git != null && git.matches("[0-9a-f]{40}"), "PUBLICATION_BUILD_GIT_UNKNOWN");
                // Boot repackage 将 META-INF 留在根目录，知识仍在 classes；知识之间必须同根。
                String knowledgePrefix = jar.getJarEntry(BOOT_CLASSES + PublicationManifest.PLATFORM_HELP_RESOURCE) == null
                        ? "" : BOOT_CLASSES;
                for (String path : PublicationManifest.RESOURCE_PATHS) {
                    resources.put(path, entry(jar, knowledgePrefix + path, 2_000_000));
                }
            }
            var after = Files.readAttributes(runningArtifact, java.nio.file.attribute.BasicFileAttributes.class);
            PublicationManifest.require(before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && java.util.Objects.equals(before.fileKey(), after.fileKey())
                    && Files.isSameFile(runningArtifact, configuredArtifact), "PUBLICATION_ARTIFACT_CHANGED");
            return new Identity(git, artifactHash, resources);
        } catch (Exception exception) {
            throw new SecurityException("PUBLICATION_BUILD_IDENTITY_REJECTED");
        }
    }

    static Path runtimeJar(URI location) {
        String value = location.toString();
        if (value.startsWith("jar:")) value = value.substring(4);
        if (value.startsWith("nested:")) {
            int marker = value.indexOf("/!BOOT-INF/classes");
            PublicationManifest.require(marker > 0 && Set.of("/!BOOT-INF/classes", "/!BOOT-INF/classes/", "/!BOOT-INF/classes/!/")
                    .contains(value.substring(marker)), "PUBLICATION_CODE_SOURCE_INVALID");
            value = "file:" + value.substring("nested:".length(), marker);
        } else {
            int marker = value.indexOf("!/");
            if (marker >= 0) {
                PublicationManifest.require(Set.of("!/", "!/BOOT-INF/classes/", "!/BOOT-INF/classes/!/")
                        .contains(value.substring(marker)), "PUBLICATION_CODE_SOURCE_INVALID");
                value = value.substring(0, marker);
            }
        }
        URI file = URI.create(value);
        PublicationManifest.require("file".equals(file.getScheme()), "PUBLICATION_CODE_SOURCE_INVALID");
        return Path.of(file);
    }

    private static byte[] entry(JarFile jar, String name, int limit) throws IOException {
        var entry = jar.getJarEntry(name);
        PublicationManifest.require(entry != null && !entry.isDirectory() && entry.getSize() <= limit,
                "PUBLICATION_ARTIFACT_RESOURCES_INVALID");
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            PublicationManifest.require(bytes.length > 0 && bytes.length <= limit, "PUBLICATION_ARTIFACT_RESOURCES_INVALID");
            return bytes;
        }
    }
}
