package com.zcx.chenstack.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 受控发布身份回归：交付的制品必须自带可核验的构建身份。
 *
 * <p>正式库曾因制品内 {@code META-INF/chen-release-build.properties} 为 {@code gitCommit=UNSET}，
 * 使只读恢复在身份校验阶段即以 {@code PUBLICATION_BUILD_IDENTITY_REJECTED} 失败。本测试锁定：
 * 通过 {@code script/release/Build-Backend.ps1} 产出的制品必须能被 {@link PublicationBuildIdentity}
 * 完整接受（提交号格式、制品 SHA256、两份知识资源的唯一性与哈希）。</p>
 *
 * <p>默认跳过：需要显式传入实际制品路径，避免普通单测依赖构建产物。</p>
 *
 * <pre>mvn --offline test -Dtest=PackagedArtifactIdentityTest \
 *     -Dchen.release.artifact=target/zcx-chen-stack-backend-1.0-SNAPSHOT.jar</pre>
 */
class PackagedArtifactIdentityTest {
    @Test
    void packagedArtifactCarriesAValidReleaseIdentity() {
        String configured = System.getProperty("chen.release.artifact");
        assumeTrue(configured != null && !configured.isBlank(),
                "需要 -Dchen.release.artifact=<打包制品路径> 才执行制品身份校验");
        Path artifact = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(artifact), "打包制品不存在: " + artifact);

        PublicationBuildIdentity.Identity identity = PublicationBuildIdentity.inspect(artifact, artifact);

        assertTrue(identity.gitCommit().matches("[0-9a-f]{40}"), "gitCommit 必须是 40 位小写十六进制");
        assertNotEquals("UNSET", identity.gitCommit(), "制品不得以 UNSET 提交号交付");
        assertTrue(identity.artifactSha256().matches("[0-9a-f]{64}"), "制品哈希格式非法");
        assertEquals(PublicationManifest.RESOURCE_PATHS, identity.resources().keySet(),
                "必须且只能内嵌两份知识资源");
        identity.resources().forEach((name, bytes) -> assertTrue(bytes.length > 0, name + " 为空"));
    }
}
