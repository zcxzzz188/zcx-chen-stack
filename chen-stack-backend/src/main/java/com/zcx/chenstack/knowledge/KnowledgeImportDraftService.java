package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.service.AiKnowledgeManagementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把管理员确认的 AI 候选知识保存到原有知识草稿箱。
 *
 * <p>本服务只做批量编排与来源校验；真正的草稿写入复用 {@link AiKnowledgeManagementService}，
 * 因此版本、权限、审核与发布语义保持一致。保存草稿不会审核、不会发布、不会进入 RAG。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Service
public class KnowledgeImportDraftService {
    public record CandidateInput(String candidateId, String title, String content, List<String> keywords,
                                 String sourceUrl) {
    }

    public record Saved(String candidateId, AiKnowledgeManagementService.Draft draft) {
    }

    public record Failed(String candidateId, String code, String message) {
    }

    public record Result(String batchId, List<Saved> saved, List<Failed> failed) {
        public Result {
            saved = saved == null ? List.of() : List.copyOf(saved);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }
    }

    private final KnowledgeDocService documents;
    private final AiKnowledgeManagementService drafts;
    private final KnowledgeDocProperties properties;

    public KnowledgeImportDraftService(KnowledgeDocService documents, AiKnowledgeManagementService drafts,
                                       KnowledgeDocProperties properties) {
        this.documents = documents;
        this.drafts = drafts;
        this.properties = properties;
    }

    /**
     * 串行保存，配合 {@code originRef = imp:<requestId>:<candidateId>} 防止同一批候选被创建多次。
     * 多实例部署时仍由数据库中的 originRef 检查兜底；这里不引入新表或唯一索引。
     */
    public synchronized Result save(String requestId, String batchId, List<CandidateInput> inputs, Integer actorId) {
        if (actorId == null || actorId <= 0) {
            throw KnowledgeDocException.denied();
        }
        if (batchId == null || !batchId.matches("[A-Za-z0-9._-]{1,72}")) {
            throw KnowledgeDocException.bad("DRAFT_BATCH_INVALID", "保存批次标识无效");
        }
        if (inputs == null || inputs.isEmpty()) {
            throw KnowledgeDocException.bad("DRAFT_SELECTION_REQUIRED", "请至少选择一条候选知识");
        }
        if (inputs.size() > Math.min(properties.getAnalysisMaxCandidates(), 12)) {
            throw KnowledgeDocException.bad("DRAFT_SELECTION_TOO_LARGE", "一次保存的候选知识过多");
        }
        KnowledgeDocStore.ImportRow task = documents.importTask(requestId);
        if (!List.of("PARSED", "APPROVED", "PUBLISHED").contains(task.status())) {
            throw KnowledgeDocException.conflict("IMPORT_STATE_INVALID", "当前导入任务不能保存知识草稿");
        }
        KnowledgeDocService.SourceDocument source = documents.ensureSourceDocument(requestId, actorId);
        Map<String, CandidateInput> unique = new LinkedHashMap<>();
        for (CandidateInput input : inputs) {
            if (input == null || input.candidateId() == null
                    || !input.candidateId().matches("[a-f0-9]{16}")) {
                throw KnowledgeDocException.bad("DRAFT_CANDIDATE_INVALID", "候选知识标识无效");
            }
            unique.putIfAbsent(input.candidateId(), input);
        }
        List<Saved> saved = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (CandidateInput input : unique.values()) {
            try {
                String title = input.title() == null ? "" : input.title().strip();
                String content = input.content() == null ? "" : input.content().strip();
                if (title.isEmpty()) {
                    throw KnowledgeDocException.bad("DRAFT_TITLE_REQUIRED", "知识标题不能为空");
                }
                if (content.isEmpty()) {
                    throw KnowledgeDocException.bad("DRAFT_CONTENT_REQUIRED", "知识正文不能为空");
                }
                String version = "ai-" + input.candidateId().substring(0, 12);
                String originRef = "imp:" + requestId + ":" + input.candidateId();
                AiKnowledgeManagementService.Draft draft = drafts.createDraftFromImport(requestId, title, content,
                        version, input.sourceUrl(), input.keywords(), originRef, source.documentId(), source.versionId());
                saved.add(new Saved(input.candidateId(), draft));
            } catch (KnowledgeDocException failure) {
                failed.add(new Failed(input.candidateId(), failure.code(), failure.getMessage()));
            } catch (com.zcx.chenstack.wiki.WikiProductException failure) {
                failed.add(new Failed(input.candidateId(), "DRAFT_INPUT_INVALID", failure.getMessage()));
            } catch (RuntimeException failure) {
                failed.add(new Failed(input.candidateId(), "DRAFT_SAVE_FAILED", "保存草稿失败，请稍后重试"));
            }
        }
        return new Result(batchId, saved, failed);
    }
}
