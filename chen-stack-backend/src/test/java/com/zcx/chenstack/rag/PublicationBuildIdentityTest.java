package com.zcx.chenstack.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PublicationBuildIdentityTest {
    @TempDir Path directory;
    private static final String META = "META-INF/chen-release-build.properties";
    private static final String GIT = "7".repeat(40);

    private Map<String, byte[]> entries(String prefix, String git) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(prefix + META, ("gitCommit=" + git + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put(prefix + PublicationManifest.PLATFORM_HELP_RESOURCE, "[\r\n]\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put(prefix + PublicationManifest.CAPABILITIES_RESOURCE, "{}\n".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private Path jar(String name, Map<String, byte[]> entries) throws Exception {
        Path path = directory.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue()); output.closeEntry();
            }
        }
        return path;
    }

    @Test void hashesActualJarAndPreservesEmbeddedRawResourcesInBothLayouts() throws Exception {
        for (String prefix : new String[]{"", "BOOT-INF/classes/"}) {
            var entries = entries(prefix, GIT); var path = jar(prefix.isEmpty() ? "plain.jar" : "boot.jar", entries);
            var identity = PublicationBuildIdentity.inspect(path, path);
            assertEquals(GIT, identity.gitCommit());
            assertEquals(PublicationManifest.sha256(Files.readAllBytes(path)), identity.artifactSha256());
            for (String resource : PublicationManifest.RESOURCE_PATHS) {
                assertArrayEquals(entries.get(prefix + resource), identity.resources().get(resource));
            }
        }
    }

    @Test void acceptsRepackagedBootRootMetadataWithBothKnowledgeResourcesUnderClasses() throws Exception {
        var entries = entries("BOOT-INF/classes/", GIT);
        entries.put(META, entries.remove("BOOT-INF/classes/" + META));
        Path path = jar("repackaged-boot.jar", entries);
        var identity = PublicationBuildIdentity.inspect(path, path);
        assertEquals(GIT, identity.gitCommit());
        assertEquals(PublicationManifest.sha256(Files.readAllBytes(path)), identity.artifactSha256());
        for (String resource : PublicationManifest.RESOURCE_PATHS) {
            assertArrayEquals(entries.get("BOOT-INF/classes/" + resource), identity.resources().get(resource));
        }
    }

    @Test void rejectsIdenticalCopyInsteadOfRunningFileAndDevelopmentDirectory() throws Exception {
        Path running = jar("running.jar", entries("", GIT));
        Path copy = directory.resolve("copy.jar"); Files.copy(running, copy);
        assertThrows(SecurityException.class, () -> PublicationBuildIdentity.inspect(running, copy));
        assertThrows(SecurityException.class, () -> PublicationBuildIdentity.inspect(directory, directory));
        assertThrows(SecurityException.class, () -> PublicationBuildIdentity.inspect(running, null));
    }

    @Test void rejectsUnsetMalformedOrMissingBuildCommit() throws Exception {
        for (String git : new String[]{"UNSET", "@chen.release.gitCommit@", "123", "A".repeat(40)}) {
            Path path = jar("invalid-" + Math.abs(git.hashCode()) + ".jar", entries("", git));
            assertThrows(SecurityException.class, () -> PublicationBuildIdentity.inspect(path, path));
        }
        var entries = entries("", GIT); entries.remove(META);
        Path path = jar("missing-meta.jar", entries);
        assertThrows(SecurityException.class, () -> PublicationBuildIdentity.inspect(path, path));
    }

    @Test void rejectsMissingOversizedOrAmbiguousResourceEntries() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            var entries = entries("", GIT);
            if (variant == 0) entries.remove(PublicationManifest.PLATFORM_HELP_RESOURCE);
            if (variant == 1) entries.put(PublicationManifest.PLATFORM_HELP_RESOURCE, new byte[2_000_001]);
            if (variant == 2) entries.put("BOOT-INF/classes/" + META, entries.get(META));
            if (variant == 3) {
                String knowledge = PublicationManifest.PLATFORM_HELP_RESOURCE;
                entries.put("BOOT-INF/classes/" + knowledge, entries.remove(knowledge));
            }
            Path path = jar("resource-" + variant + ".jar", entries);
            assertThrows(SecurityException.class, () -> PublicationBuildIdentity.inspect(path, path));
        }
    }

    @Test void resolvesEncodedBootNestedAndStandardJarCodeSources() {
        Path artifact = directory.resolve("space name.jar");
        String file = artifact.toUri().toASCIIString();
        String rawPath = artifact.toUri().getRawPath();
        for (String uri : new String[]{file, "jar:" + file + "!/", "jar:" + file + "!/BOOT-INF/classes/",
                "nested:" + rawPath + "/!BOOT-INF/classes/", "jar:nested:" + rawPath + "/!BOOT-INF/classes/!/"}) {
            assertEquals(artifact.toAbsolutePath(), PublicationBuildIdentity.runtimeJar(URI.create(uri)).toAbsolutePath());
        }
    }

    @Test void rejectsNonFileOrNestedDependencySources() {
        for (String uri : new String[]{"https://example.invalid/app.jar", "nested:/app.jar/!BOOT-INF/lib/untrusted.jar!/",
                "jar:file:/app.jar!/BOOT-INF/lib/other.jar!/"}) {
            assertThrows(SecurityException.class, () -> PublicationBuildIdentity.runtimeJar(URI.create(uri)));
        }
    }

    @Test void publicEntryPointCannotUseATestSuppliedJarToReplaceUnpackagedRuntime() throws Exception {
        Path supplied = jar("supplied.jar", entries("", GIT));
        assertThrows(SecurityException.class, () -> PublicationBuildIdentity.read(supplied));
    }
}
