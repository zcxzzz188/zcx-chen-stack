package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zcx.chenstack.domain.entity.Article;
import com.zcx.chenstack.domain.enums.EditStatusEnum;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent.EditableArticle;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.ArticleMapper;
import com.zcx.chenstack.mapper.ArticleColumnMapper;
import com.zcx.chenstack.mapper.ColumnMapper;
import com.zcx.chenstack.domain.entity.ArticleColumn;
import com.zcx.chenstack.domain.vo.ArticleVo;
import com.zcx.chenstack.domain.vo.ColumnVo;
import cn.hutool.core.bean.BeanUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class EditableArticleService {
    private final ArticleMapper mapper;
    @Resource
    private ArticleColumnMapper articleColumnMapper;
    @Resource
    private ColumnMapper columnMapper;

    public EditableArticleService(ArticleMapper mapper) {
        this.mapper = mapper;
    }

    // userId 只由请求线程捕获的认证主体提供，不进入模型参数或 HTTP 请求 DTO。
    public CustomerServiceEvent listForUser(Integer userId, int page, int pageSize) {
        requireUser(userId);
        if (page < 1 || page > 1000 || pageSize < 1 || pageSize > 20) {
            throw new BlogException("文章分页参数无效");
        }
        Page<Article> result = mapper.selectPage(new Page<>(page, pageSize), owned(userId)
                .orderByDesc(Article::getUpdateTime).orderByDesc(Article::getId));
        var articles = result.getRecords().stream().map(article -> toCard(article, userId)).toList();
        return new CustomerServiceEvent(articles.isEmpty() ? "EMPTY_RESULT" : "ARTICLE_SELECTION",
                articles.isEmpty() ? "这一页暂无有权编辑的文章。" : "请选择你要修改的文章。",
                null, articles, page, pageSize, result.hasNext(), "SUPPORTED");
    }

    public EditableArticle requireEditable(Integer userId, Integer articleId) {
        requireUser(userId);
        if (articleId == null || articleId <= 0) {
            throw new BlogException("文章不可编辑或不存在");
        }
        Article article = mapper.selectOne(owned(userId).eq(Article::getId, articleId));
        return toCard(article, userId);
    }

    public ArticleVo loadForUser(Integer userId, Integer articleId) {
        requireUser(userId);
        if (articleId == null || articleId <= 0) {
            throw new BlogException("文章不可编辑或不存在");
        }
        // 在同一条归属/状态受限的 SQL 中读取正文，不使用会增加阅读量的公开详情接口。
        Article article = mapper.selectOne(owned(userId).select(Article.class, field -> true)
                .eq(Article::getId, articleId));
        toCard(article, userId);
        ArticleVo result = BeanUtil.copyProperties(article, ArticleVo.class);
        var relations = articleColumnMapper.selectList(new LambdaQueryWrapper<ArticleColumn>()
                .eq(ArticleColumn::getArticleId, articleId));
        if (!relations.isEmpty()) {
            result.setColumns(columnMapper.selectBatchIds(relations.stream().map(ArticleColumn::getColumnId).toList())
                    .stream().filter(column -> Objects.equals(column.getUserId(), userId)
                            && Objects.equals(column.getIsDeleted(), 0)
                            && (Objects.equals(column.getExamineStatus(), ExamineStatusEnum.PASS.getCode())
                            || Objects.equals(column.getExamineStatus(), ExamineStatusEnum.WAIT.getCode())))
                    .map(column -> BeanUtil.copyProperties(column, ColumnVo.class)).toList());
        }
        return result;
    }

    private static LambdaQueryWrapper<Article> owned(Integer userId) {
        return new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getUserId, Article::getTitle,
                        Article::getEditStatus, Article::getExamineStatus, Article::getIsDeleted)
                .eq(Article::getUserId, userId).eq(Article::getIsDeleted, 0)
                .in(Article::getEditStatus, EditStatusEnum.PUBLISHED.getCode(), EditStatusEnum.DRAFT.getCode());
    }

    private static EditableArticle toCard(Article article, Integer userId) {
        if (article == null || !Objects.equals(article.getUserId(), userId)
                || !Objects.equals(article.getIsDeleted(), 0)
                || (!Objects.equals(article.getEditStatus(), EditStatusEnum.PUBLISHED.getCode())
                && !Objects.equals(article.getEditStatus(), EditStatusEnum.DRAFT.getCode()))) {
            throw new BlogException("文章不可编辑或不存在");
        }
        return new EditableArticle(article.getId(), article.getTitle(), article.getEditStatus(),
                article.getExamineStatus(), true);
    }

    private static void requireUser(Integer userId) {
        if (userId == null || userId <= 0) {
            throw new BlogException("请先登录");
        }
    }
}
