package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.rag.CatalogLifecycle;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.KnowledgeDocument;
import com.zcx.chenstack.security.AdminSecurity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static com.zcx.chenstack.wiki.WikiViews.Revision;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MySQL and trusted services; generated P2 drafts are synthetic fixtures, with zero model calls. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WikiPublicationIntegrationIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final SourceKey ARTICLE = new SourceKey(SourceType.ARTICLE, "1");
    @TempDir static Path temporary;
    private final String id = UUID.randomUUID().toString().replace("-", "");
    private final String name = "chen-wiki-publication-" + id;
    private final String password = UUID.randomUUID().toString();
    private int port;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private CatalogLifecycle catalog;
    private WikiFoundation foundation;
    private WikiPublicationService publication;
    private WikiManagementService management;

    @BeforeAll void startOwnedMysql() throws Exception {
        assertEquals("true", System.getProperty("chen.wiki.it"), "Explicit isolated integration opt-in required");
        docker("image", "inspect", "mysql:8.0");
        docker("network", "create", "--label", "chen.wiki.publication=" + id, name);
        Path env = temporary.resolve("synthetic-mysql.env");
        Files.writeString(env, "MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_ROOT_HOST=%\n");
        docker("run", "--pull=never", "--detach", "--name", name, "--label", "chen.wiki.publication=" + id,
                "--network", name, "--dns", "127.0.0.1", "--publish", "127.0.0.1::3306",
                "--tmpfs", "/var/lib/mysql:rw,nosuid,size=512m", "--env-file", env.toString(), "mysql:8.0");
        String binding = docker("port", name, "3306/tcp").strip();
        assertTrue(binding.startsWith("127.0.0.1:"));
        port = Integer.parseInt(binding.substring(binding.lastIndexOf(':') + 1));
        boolean ready = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(url("mysql"), "root", password)) {
                connection.createStatement().execute("CREATE DATABASE chen_wiki_publication CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
                ready = true;
                break;
            } catch (Exception starting) {
                Thread.sleep(250);
            }
        }
        assertTrue(ready, "Owned MySQL must become ready");
        var dataSource = new DriverManagerDataSource(url("chen_wiki_publication"), "root", password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        manager = new DataSourceTransactionManager(dataSource);
        apply("../sql/g2_rag_catalog.sql");
        apply("../sql/g3_wiki_foundation.sql");
        apply("../sql/g3_wiki_compiler.sql");
        jdbc.execute("""
                CREATE TABLE article (
                    id INT PRIMARY KEY,user_id INT NOT NULL,title VARCHAR(255) NOT NULL,
                    description TEXT,content MEDIUMTEXT,tag VARCHAR(255),reprint_type TINYINT,reprint_url TEXT,
                    edit_status TINYINT,examine_status TINYINT,visible_range TINYINT,is_deleted TINYINT,
                    read_count INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        apply("../sql/g3_wiki_invalidation.sql");
        apply("../sql/g3_wiki_product.sql");
        System.out.println("WIKI_PUBLICATION_MYSQL_VERSION=" + jdbc.queryForObject("SELECT VERSION()", String.class));
    }

    @BeforeEach void fixture() throws Exception {
        jdbc.update("UPDATE wiki_page SET current_revision_id=NULL");
        for (String table : List.of("wiki_review_event", "wiki_revision", "wiki_page", "wiki_compile_topic",
                "wiki_compile_attempt", "wiki_compile_budget", "wiki_draft", "wiki_job_source", "wiki_compile_job",
                "wiki_source_snapshot", "wiki_source", "article", "rag_source_state")) {
            jdbc.update("DELETE FROM " + table);
        }
        insertArticle(1, "合成公开来源", 0, 1, 0);
        insertArticle(2, "私有资料不应出现", 0, 1, 1);
        insertArticle(3, "未审核资料不应出现", 0, 0, 0);
        KnowledgeDocument help = new KnowledgeDocument("help-1", "合成帮助", "仅用于隔离测试的公开帮助正文", "v1",
                "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("合成"));
        jdbc.update("""
                INSERT INTO rag_source_state(source_id,version,document_json,content_sha256,generation,tombstone,deleted,version_history)
                VALUES('help-1','v1',?,?,0,FALSE,FALSE,'{}')
                """, JSON.writeValueAsString(help), "f".repeat(64));
        catalog = new KnowledgeCatalog(List.of(help)).lifecycle();
        foundation = new WikiFoundation(jdbc, manager, new WikiSourceReader(jdbc, catalog, JSON), new AdminSecurity());
        publication = newPublication();
        management = new WikiManagementService(jdbc, foundation, publication,
                new DefaultListableBeanFactory().getBeanProvider(WikiCompilationService.class));
        login(true);
    }

    @AfterEach void clearIdentity() {
        if (catalog != null) catalog.close();
        SecurityContextHolder.clearContext();
    }

    @AfterAll void removeOnlyOwnedResources() throws Exception {
        Set<String> containers = Set.copyOf(docker("ps", "--all", "--filter", "label=chen.wiki.publication=" + id,
                "--format", "{{.Names}}").lines().toList());
        if (containers.contains(name)) {
            assertEquals(id, JSON.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", name)).get("chen.wiki.publication").asText());
            docker("rm", "--force", "--volumes", name);
        }
        Set<String> networks = Set.copyOf(docker("network", "ls", "--filter", "label=chen.wiki.publication=" + id,
                "--format", "{{.Name}}").lines().toList());
        if (networks.contains(name)) {
            assertEquals(id, JSON.readTree(docker("network", "inspect", "--format", "{{json .Labels}}", name)).get("chen.wiki.publication").asText());
            docker("network", "rm", name);
        }
        assertTrue(docker("ps", "--all", "--filter", "label=chen.wiki.publication=" + id, "--format", "{{.Names}}").isBlank());
        System.out.println("WIKI_PUBLICATION_RESOURCES_REMOVED=" + name);
    }

    @Test void sourcePickerUsesActualAdmissionAndRequiresExplicitFingerprintAuthorization() {
        var sources = management.sources("", 1, 20);
        assertEquals(2, sources.total());
        assertTrue(sources.items().stream().anyMatch(s -> s.type().equals("ARTICLE") && s.id().equals("1")));
        assertFalse(sources.items().stream().anyMatch(s -> s.title().contains("不应出现")));
        assertTrue(sources.items().stream().noneMatch(WikiViews.Source::active));
        assertEquals(0, count("wiki_source_snapshot"));
        var inspection = management.inspect(ARTICLE);
        assertThrows(SecurityException.class, () -> management.authorize(ARTICLE, "a".repeat(64)));
        var authorized = management.authorize(ARTICLE, inspection.fingerprint());
        assertTrue(authorized.active());
        assertNotNull(authorized.snapshotId());
        management.revoke(ARTICLE);
        assertFalse(management.inspect(ARTICLE).active());
    }

    @Test void cancellationWithoutCompilerPreservesAuthorizationStateAndBudget() {
        Snapshot source = foundation.authorize(ARTICLE, foundation.inspect(ARTICLE).fingerprint());
        foundation.prepare("queued-offline", List.of(source.id()), "c".repeat(64));
        login(false);
        status(403, () -> management.cancel("queued-offline"));
        status(403, () -> management.affected(ARTICLE));
        login(true);
        assertEquals("QUEUED", foundation.status("queued-offline").state());
        management.cancel("queued-offline");
        management.cancel("queued-offline");
        assertEquals("CANCELLED", foundation.status("queued-offline").state());

        foundation.prepare("running-offline", List.of(source.id()), "d".repeat(64));
        WikiCompilationStore store = new WikiCompilationStore(jdbc);
        foundation.start("running-offline", input -> store.open("running-offline", "synthetic", WikiCompilationService.Options.defaults()));
        management.cancel("running-offline");
        assertEquals("CANCELLED", foundation.status("running-offline").state());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT first_stop FROM wiki_compile_budget WHERE request_id='running-offline'", String.class));
        status(503, () -> management.createJob("new-offline", List.of(source.id()), Set.of()));
    }

    @Test void completedDraftAndAffectedTopicsRemainAvailableWithoutCompiler() {
        String request = syntheticDraft("done-offline", "transactions");
        WikiViews.Draft before = management.draft(request);
        management.cancel(request);
        assertEquals("DRAFT_READY", foundation.status(request).state());
        assertEquals(before, management.draft(request));
        assertEquals(0, count("wiki_compile_budget"));
        List<WikiCompilationService.RecompileTarget> affected = management.affected(ARTICLE);
        assertEquals(1, affected.size());
        assertEquals(request, affected.getFirst().previousRequestId());
        assertEquals("transactions", affected.getFirst().topic());
        assertEquals(List.of(before.sources().getFirst().snapshotId()), affected.getFirst().previousSnapshots());
    }

    @Test void draftAndApprovedButUnpublishedContentStayOutOfAllPublicProjections() {
        Revision draft = create(null, "草稿保密词", "事务摘要", "事务正文", "transactions");
        assertEquals("DRAFT", draft.state());
        assertEquals(0, publication.list("保密词", null, 1, 20).total());
        missing(() -> publication.read(draft.pageId()));
        Revision approved = approve(draft);
        assertEquals("APPROVED", approved.state());
        assertEquals(0, publication.list("", null, 1, 20).total());
        missing(() -> publication.read(draft.pageId()));
    }

    @Test void humanReviewAndPublicationPersistVersionPointerAndAuditAtomically() {
        Revision draft = create(null, "事务知识", "事务摘要", "事务正文", "transactions");
        Revision approved = approve(draft);
        Revision published = publication.publish(approved.id(), approved.bodyHash(), null);
        assertEquals("PUBLISHED", published.state());
        assertTrue(published.current());
        assertEquals(1, publication.read(draft.pageId()).version());
        assertEquals(List.of("CREATE", "SUBMIT", "APPROVE", "PUBLISH"), jdbc.queryForList(
                "SELECT action FROM wiki_review_event ORDER BY id", String.class));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT state FROM wiki_draft WHERE request_id=?", String.class, draft.requestId()));
        assertEquals(1, publication.managedPages().size());
    }

    @Test void evidenceAndConflictsMustBeAcknowledgedBeforeApproval() {
        Revision draft = create(null, "待核查", "摘要", "正文", "review");
        publication.submit(draft.id(), draft.bodyHash());
        status(400, () -> publication.approve(draft.id(), draft.bodyHash(), false, true));
        status(400, () -> publication.approve(draft.id(), draft.bodyHash(), true, false));
        assertEquals("IN_REVIEW", revision(draft.id()).state());
        status(409, () -> publication.publish(draft.id(), draft.bodyHash(), null));
    }

    @Test void publicationAuditFailureRollsBackStateAndCurrentPointer() {
        Revision approved = approve(create(null, "原子发布", "摘要", "正文", "transactions"));
        jdbc.execute("CREATE TRIGGER wiki_publication_fault BEFORE INSERT ON wiki_review_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_AUDIT_FAILURE'");
        try {
            assertThrows(DataAccessException.class, () -> publication.publish(approved.id(), approved.bodyHash(), null));
            assertNull(jdbc.queryForObject("SELECT current_revision_id FROM wiki_page WHERE id=?", Long.class, approved.pageId()));
            assertEquals("APPROVED", revision(approved.id()).state());
            assertEquals(0, count("wiki_review_event", "action='PUBLISH'"));
        } finally { jdbc.execute("DROP TRIGGER wiki_publication_fault"); }
    }

    @Test void editedVersionNeedsItsOwnReviewAndCannotInheritPreviousApprovalHash() {
        Revision original = approve(create(null, "第一版", "摘要", "旧正文", "review"));
        Revision edited = create(original.pageId(), "第二版", "摘要", "新正文", "review");
        assertEquals(2, edited.version());
        assertEquals("DRAFT", edited.state());
        assertNotEquals(original.bodyHash(), edited.bodyHash());
        status(409, () -> publication.submit(edited.id(), original.bodyHash()));
        status(409, () -> publication.publish(edited.id(), edited.bodyHash(), null));
        Revision approved = approve(edited);
        publication.publish(approved.id(), approved.bodyHash(), null);
        assertEquals("新正文", publication.read(original.pageId()).markdown());
    }

    @Test void sourceChangesRejectAnOtherwiseValidApprovalAndDoNotPublishAnyPointer() {
        Revision approved = approve(create(null, "过时审批", "摘要", "正文", "review"));
        jdbc.update("UPDATE article SET content='<p>更改后的正文</p>' WHERE id=1");
        assertThrows(SecurityException.class, () -> publication.publish(approved.id(), approved.bodyHash(), null));
        assertEquals(0, publication.list("", null, 1, 20).total());
        assertNull(jdbc.queryForObject("SELECT current_revision_id FROM wiki_page WHERE id=?", Long.class, approved.pageId()));
        assertEquals("APPROVED", revision(approved.id()).state());
    }

    @Test void mismatchedBodyOrManifestCannotUsePersistedApproval() {
        Revision approved = approve(create(null, "哈希绑定", "摘要", "正文", "review"));
        status(409, () -> publication.publish(approved.id(), "a".repeat(64), null));
        jdbc.update("UPDATE wiki_revision SET markdown='未经审核的替换正文' WHERE id=?", approved.id());
        status(409, () -> publication.publish(approved.id(), approved.bodyHash(), null));
        jdbc.update("UPDATE wiki_revision SET markdown=? WHERE id=?", approved.markdown(), approved.id());
        jdbc.update("UPDATE wiki_compile_job SET manifest_hash=? WHERE request_id=?", "f".repeat(64), approved.requestId());
        status(409, () -> publication.publish(approved.id(), approved.bodyHash(), null));
        assertEquals(0, count("wiki_review_event", "action='PUBLISH'"));
    }

    @Test void chineseBoundedSearchAndTopicsOnlyIncludeCurrentlyPublishedEligiblePages() {
        Revision visible = publish(create(null, "中文事务知识", "回滚与隔离", "数据库事务正文", "数据库"), null);
        create(null, "中文秘密草稿", "秘密", "秘密", "秘密主题");
        Revision hidden = publish(create(null, "应隐藏页面", "隔离", "内部隐藏词", "隐藏主题"), null);
        publication.withdraw(hidden.pageId(), hidden.version());
        var found = publication.list("事务", "数据库", 1, 1);
        assertEquals(1, found.total());
        assertEquals(visible.pageId(), found.items().getFirst().id());
        assertEquals(List.of("数据库"), found.topics());
        assertEquals(0, publication.list("秘密", null, 1, 20).total());
        assertEquals(0, publication.list("内部隐藏词", null, 1, 20).total());
        assertEquals(0, publication.list("事务", "不存在主题", 1, 20).total());
        assertEquals(2, publication.list("", null, 2, 1).page());
    }

    @Test void wikiQuestionAnsweringUsesRealPublishedAuthorityAndRejectsRevocationDuringGeneration() {
        Revision published = publish(create(null, "事务知识问答", "问答摘要", "PUBLISHED_QA_SENTINEL 知识正文", "事务"), null);
        create(null, "DRAFT_PRIVATE_SENTINEL", "秘密草稿", "DRAFT_BODY_SENTINEL", "秘密");
        int jobsBefore = count("wiki_compile_job");
        int revisionsBefore = count("wiki_revision");
        var knowledge = org.mockito.Mockito.mock(com.zcx.chenstack.rag.RagKnowledgeService.class);
        org.mockito.Mockito.when(knowledge.chatTimeoutSeconds()).thenReturn(10);
        org.mockito.Mockito.when(knowledge.contextByteBudget()).thenReturn(24000);
        var model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(model.getOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().model("fixture").build());
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        var revoke = new java.util.concurrent.atomic.AtomicBoolean();
        var prompts = new java.util.ArrayList<org.springframework.ai.chat.prompt.Prompt>();
        org.mockito.Mockito.when(model.stream(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(invocation -> {
            org.springframework.ai.chat.prompt.Prompt prompt = invocation.getArgument(0);
            String reason = "stop";
            org.springframework.ai.chat.messages.AssistantMessage message;
            if (prompt.getSystemMessage().getText().startsWith("你是用户端客服的请求范围分类器")) {
                message = new org.springframework.ai.chat.messages.AssistantMessage("USER_HELP");
            } else {
                prompts.add(prompt);
                if (rounds.getAndIncrement() % 2 == 0) {
                    reason = "tool_calls";
                    message = org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("wiki-search", "function", "searchPublishedWiki", "{\"query\":\"知识正文\"}"))).build();
                } else {
                    String context = prompt.getInstructions().toString();
                    assertTrue(context.contains("PUBLISHED_QA_SENTINEL"));
                    assertFalse(context.contains("DRAFT_PRIVATE_SENTINEL"));
                    assertFalse(context.contains("DRAFT_BODY_SENTINEL"));
                    if (revoke.get()) jdbc.update("UPDATE article SET visible_range=1 WHERE id=1");
                    message = new org.springframework.ai.chat.messages.AssistantMessage("依据正式Wiki回答");
                }
            }
            return reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(List.of(
                    new org.springframework.ai.chat.model.Generation(message,
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(reason).build()))));
        });
        var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build();
        var answers = new com.zcx.chenstack.rag.ProductRagAnswerService(knowledge,
                org.springframework.ai.chat.client.ChatClient.create(model), memory, null,
                new com.zcx.chenstack.config.AiKnowledgeProperties("wiki"), publication);
        assertEquals(List.of("依据正式Wiki回答"), answers.answer("事务知识", "published", 7, () -> true).collectList().block(Duration.ofSeconds(15)));
        revoke.set(true);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> answers.answer("事务知识", "revoked", 7, () -> true).collectList().block(Duration.ofSeconds(15)));
        assertEquals(com.zcx.chenstack.service.CustomerServiceError.KNOWLEDGE_UNAVAILABLE,
                com.zcx.chenstack.service.CustomerServiceError.classify(failure));
        assertTrue(memory.get("wiki:7:revoked").isEmpty());
        missing(() -> publication.read(published.pageId()));
        assertEquals(jobsBefore, count("wiki_compile_job"));
        assertEquals(revisionsBefore, count("wiki_revision"));
        org.mockito.Mockito.verify(knowledge, org.mockito.Mockito.never()).retrieve(org.mockito.ArgumentMatchers.anyString());
    }

    @Test void sourceWithdrawalHidesReadingSearchTopicsAndDirectoryImmediately() {
        Revision published = publish(create(null, "立即撤回", "摘要", "可搜索正文", "撤回主题"), null);
        assertTrue(publication.managedPages().getFirst().publicEligible());
        jdbc.update("UPDATE article SET visible_range=1 WHERE id=1");
        assertFalse(publication.managedPages().getFirst().publicEligible());
        missing(() -> publication.read(published.pageId()));
        var listed = publication.list("", null, 1, 20);
        assertEquals(0, listed.total());
        assertTrue(listed.topics().isEmpty());
        assertEquals(0, publication.list("可搜索正文", null, 1, 20).total());
    }

    @Test void transientSourceWithdrawalAndRestorationWithoutReadsNeverRevivesPublishedContent() {
        Revision published = publish(create(null, "旧许可", "摘要", "正文", "revocation"), null);
        jdbc.update("UPDATE article SET edit_status=1 WHERE id=1");
        jdbc.update("UPDATE article SET edit_status=0 WHERE id=1");
        missing(() -> publication.read(published.pageId()));
        assertThrows(SecurityException.class, () -> publication.restore(published.pageId(), published.id(), published.version()));
        management.authorize(ARTICLE, management.inspect(ARTICLE).fingerprint());
        missing(() -> publication.read(published.pageId()));
        assertThrows(SecurityException.class, () -> publication.restore(published.pageId(), published.id(), published.version()));
    }

    @Test void withdrawalAndRestoreOnlyChangePointerToPreviouslyPublishedCurrentlyValidVersion() {
        Revision first = publish(create(null, "第一版", "摘要", "第一版正文", "history"), null);
        Revision second = publish(create(first.pageId(), "第二版", "摘要", "第二版正文", "history"), first.version());
        assertEquals(2, publication.versions(first.pageId()).size());
        publication.withdraw(first.pageId(), second.version());
        missing(() -> publication.read(first.pageId()));
        Revision restored = publication.restore(first.pageId(), first.id(), second.version());
        assertTrue(restored.current());
        assertEquals(1, publication.read(first.pageId()).version());
        assertEquals(1, count("wiki_review_event", "action='RESTORE'"));
        Revision draft = create(first.pageId(), "未发布新版", "摘要", "正文", "history");
        status(409, () -> publication.restore(first.pageId(), draft.id(), first.version()));
    }

    @Test void restoringAnotherPagesVersionAndCrossPagePointerWritesAreRejected() {
        Revision first = publish(create(null, "页面A", "摘要", "A正文", "history"), null);
        Revision second = publish(create(null, "页面B", "摘要", "B正文", "history"), null);
        status(404, () -> publication.restore(first.pageId(), second.id(), first.version()));
        assertEquals("A正文", publication.read(first.pageId()).markdown());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE wiki_page SET current_revision_id=? WHERE id=?", second.id(), first.pageId()));
    }

    @Test void concurrentPublicationsWithSameExpectedPointerHaveExactlyOneWinner() throws Exception {
        Revision first = publish(create(null, "并发首页", "摘要", "初始正文", "concurrency"), null);
        Revision candidateA = approve(create(first.pageId(), "候选A", "摘要", "A正文", "concurrency"));
        Revision candidateB = approve(create(first.pageId(), "候选B", "摘要", "B正文", "concurrency"));
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> concurrentPublish(candidateA, first.version(), start));
            var b = workers.submit(() -> concurrentPublish(candidateB, first.version(), start));
            start.countDown();
            assertEquals(1, a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, publication.versions(first.pageId()).stream().filter(Revision::current).count());
        assertEquals(2, count("wiki_review_event", "action='PUBLISH'"));
        assertTrue(Set.of(candidateA.version(), candidateB.version()).contains(publication.read(first.pageId()).version()));
    }

    @Test void ordinaryAndAnonymousUsersCannotExerciseAdministrativeAuthority() {
        Revision draft = create(null, "后台页面", "摘要", "正文", "security");
        for (int identity = 0; identity < 3; identity++) {
            if (identity == 0) {
                login(false);
            } else if (identity == 1) {
                SecurityContextHolder.clearContext();
            } else {
                login(true);
                SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
            }
            status(403, () -> publication.create(draft.requestId(), null, "越权", "摘要", "正文"));
            status(403, () -> publication.submit(draft.id(), draft.bodyHash()));
            status(403, () -> publication.approve(draft.id(), draft.bodyHash(), true, true));
            status(403, () -> publication.publish(draft.id(), draft.bodyHash(), null));
            status(403, () -> publication.restore(draft.pageId(), draft.id(), null));
            status(403, () -> publication.withdraw(draft.pageId(), null));
            status(403, publication::managedPages);
            status(403, () -> publication.versions(draft.pageId()));
            status(403, () -> management.sources("", 1, 20));
            status(403, () -> management.jobs(1, 20));
            status(403, () -> management.draft(draft.requestId()));
        }
        assertEquals(1, count("wiki_revision"));
        assertEquals(1, count("wiki_review_event"));
    }

    @Test void publicProjectionIsAnonymousSafeAndDoesNotSerializeInternalCompileOrSourceMetadata() throws Exception {
        String request = syntheticDraft("public-projection", "公开主题");
        Revision revision = publication.create(request, null, "安全投影", "公开摘要", null);
        Revision published = publish(revision, null);
        SecurityContextHolder.clearContext();
        WikiViews.PublishedPage page = publication.read(published.pageId());
        String encoded = JSON.writeValueAsString(page);
        for (String internal : List.of("requestId", "manifestHash", "bodyHash", "rawJson", "raw_json", "snapshot=", "generation=", "selector=", request)) {
            assertFalse(encoded.contains(internal), internal);
        }
        assertEquals("/user/7/article/1", page.citations().getFirst().url());
        assertEquals("合成公开来源", page.citations().getFirst().title());
        assertNotNull(page.citations().getFirst().version());
        assertTrue(page.markdown().contains("SEMANTIC_NOT_VERIFIED"));
        assertTrue(page.markdown().contains("> “知识正文”"));
        assertEquals(1, publication.list("安全投影", null, 1, 20).total());
        jdbc.update("UPDATE wiki_revision SET markdown='篡改正文' WHERE id=?", published.id());
        missing(() -> publication.read(published.pageId()));
        assertEquals(0, publication.list("", null, 1, 20).total());
    }

    @Test void relatedLinksResolveOnlyToCurrentlyVisiblePublishedPages() {
        Revision target = publish(create(null, "关联目标", "摘要", "目标正文", "shared"), null);
        Revision page = publish(create(null, "关联首页", "摘要", "查看 [[topic:shared]]", "shared"), null);
        assertEquals(target.pageId(), publication.read(page.pageId()).related().getFirst().id());
        assertTrue(publication.read(page.pageId()).markdown().contains("(/wiki/" + target.pageId() + ")"));
        publication.withdraw(target.pageId(), target.version());
        assertTrue(publication.read(page.pageId()).related().isEmpty());
        assertFalse(publication.read(page.pageId()).markdown().contains("(/wiki/" + target.pageId() + ")"));
    }

    @Test void invalidIdentifiersPaginationAndUnboundedSearchAreRejected() {
        status(400, () -> publication.read(0));
        status(400, () -> publication.read(-1));
        missing(() -> publication.read(Long.MAX_VALUE));
        status(400, () -> publication.list("x".repeat(81), null, 1, 20));
        status(400, () -> publication.list("", null, 0, 20));
        status(400, () -> publication.list("", null, 1, 51));
        status(400, () -> publication.list("", "x".repeat(101), 1, 20));
    }

    @Test void missingSynchronousInvalidationMigrationFailsClosed() throws Exception {
        jdbc.execute("DROP TRIGGER wiki_article_changed");
        try {
            IllegalStateException rejected = assertThrows(IllegalStateException.class, this::newPublication);
            assertEquals("WIKI_INVALIDATION_MIGRATION_REQUIRED", rejected.getMessage());
        } finally {
            String sql = Files.readString(Path.of("../sql/g3_wiki_invalidation.sql"), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--[^\\r\\n]*", "");
            jdbc.execute(sql.split(";")[0]);
        }
    }

    @Test void modelDraftStateConstraintAndDisabledCompilerBoundaryRemainIntact() {
        String request = syntheticDraft("draft-only", "安全主题");
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE wiki_draft SET state='PUBLISHED' WHERE request_id=?", request));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT state FROM wiki_draft WHERE request_id=?", String.class, request));
        status(503, () -> management.createJob("disabled-compiler", List.of(1L), Set.of()));
        assertEquals(1, count("wiki_compile_job"));
        var job = management.jobs(1, 20).items().getFirst();
        assertEquals("DRAFT_READY", job.state());
        assertEquals(0, job.usedCalls());
        assertNull(job.maxCalls());
        var draft = management.draft(request);
        assertTrue(draft.markdown().contains("SEMANTIC_NOT_VERIFIED"));
        assertFalse(draft.conflicts().isEmpty());
        assertEquals(1, draft.sources().size());
    }

    private int concurrentPublish(Revision revision, int expected, CountDownLatch start) throws Exception {
        login(true);
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            publication.publish(revision.id(), revision.bodyHash(), expected);
            return 1;
        } catch (WikiProductException conflict) {
            assertEquals(409, conflict.status());
            return 0;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Revision create(Long page, String title, String summary, String body, String topic) {
        String request = syntheticDraft("job-" + UUID.randomUUID(), topic);
        return publication.create(request, page, title, summary, body);
    }

    private String syntheticDraft(String request, String topic) {
        Snapshot source = foundation.authorize(ARTICLE, foundation.inspect(ARTICLE).fingerprint());
        foundation.prepare(request, List.of(source.id()), "c".repeat(64));
        String markdown = "# Wiki 编译草稿\n\n状态：DRAFT · SEMANTIC_NOT_VERIFIED · 待人工核查\n\n"
                + "编译版本：" + WikiDraftCompiler.VERSION + "\n\n任务：" + request + "\n\n来源清单版本：internal-manifest\n\n"
                + "## 合成事务知识\n\n- 事务声明 [SEMANTIC_NOT_VERIFIED]\n\n- 值冲突 **CONFLICT：来源值不同，未裁决**\n"
                + "  - snapshot=" + source.id() + " source=ARTICLE:1 version=" + source.material().originVersion()
                + " generation=" + source.generation() + " selector=utf16:0-4 quote=“知识正文”\n";
        foundation.saveDraft(request, markdown);
        jdbc.update("INSERT INTO wiki_compile_topic(request_id,topic_key,snapshot_id) VALUES(?,?,?)", request, topic, source.id());
        return request;
    }

    private Revision approve(Revision revision) {
        publication.submit(revision.id(), revision.bodyHash());
        return publication.approve(revision.id(), revision.bodyHash(), true, true);
    }

    private Revision publish(Revision revision, Integer expected) {
        Revision approved = approve(revision);
        return publication.publish(approved.id(), approved.bodyHash(), expected);
    }

    private Revision revision(long id) {
        long page = Objects.requireNonNull(jdbc.queryForObject("SELECT page_id FROM wiki_revision WHERE id=?", Long.class, id));
        return publication.versions(page).stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    private WikiPublicationService newPublication() {
        return new WikiPublicationService(jdbc, manager, foundation, new AdminSecurity());
    }

    private static void login(boolean admin) {
        SysUser user = new SysUser();
        user.setId(1); user.setUsername("synthetic-publication-operator"); user.setSysPermissions(List.of());
        SysRole role = new SysRole(); role.setRole(admin ? "admin" : "user"); user.setSysRoles(List.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of()));
    }

    private void insertArticle(int id, String title, int edit, int review, int visibility) {
        jdbc.update("""
                INSERT INTO article(id,user_id,title,description,content,tag,reprint_type,reprint_url,edit_status,examine_status,visible_range,is_deleted)
                VALUES(?,7,?,'隔离样本','<p>知识正文 /Cache/Data</p>','java',0,NULL,?,?,?,0)
                """, id, title, edit, review, visibility);
    }

    private int count(String table) { return count(table, "1=1"); }
    private int count(String table, String condition) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class));
    }
    private static void missing(org.junit.jupiter.api.function.Executable action) { status(404, action); }
    private static void status(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(WikiProductException.class, action).status());
    }
    private void apply(String file) throws IOException {
        String sql = Files.readString(Path.of(file), StandardCharsets.UTF_8).replaceAll("(?m)^--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }
    private String url(String schema) {
        if (!Set.of("mysql", "chen_wiki_publication").contains(schema) || port <= 0) throw new IllegalArgumentException();
        return "jdbc:mysql://127.0.0.1:" + port + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }
    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("WIKI_PUBLICATION_DOCKER_TIMEOUT");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IOException("WIKI_PUBLICATION_DOCKER_FAILED: " + output.substring(0, Math.min(500, output.length())));
        return output;
    }
}
