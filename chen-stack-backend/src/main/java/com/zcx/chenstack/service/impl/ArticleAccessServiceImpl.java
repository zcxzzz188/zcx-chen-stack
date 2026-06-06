package com.zcx.chenstack.service.impl;

import com.zcx.chenstack.domain.entity.Article;
import com.zcx.chenstack.domain.enums.VisibleRangeEnum;
import com.zcx.chenstack.service.ArticleAccessService;
import com.zcx.chenstack.service.FollowService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 文章访问鉴权实现，统一收口粉丝可见内容的判断逻辑。
 */
@Service
public class ArticleAccessServiceImpl implements ArticleAccessService {

    @Resource
    private FollowService followService;

    /**
     * 详情页和列表页都复用这里，避免不同入口出现鉴权分叉。
     */
    @Override
    public boolean canAccessArticle(Article article, Integer currentUserId) {
        if (article == null) {
            return false;
        }
        Integer safeCurrentUserId = currentUserId == null ? 0 : currentUserId;
        if (Objects.equals(article.getUserId(), safeCurrentUserId) && safeCurrentUserId != 0) {
            return true;
        }
        Integer visibleRange = article.getVisibleRange();
        if (Objects.equals(visibleRange, VisibleRangeEnum.ALL.getCode())) {
            return true;
        }
        if (Objects.equals(visibleRange, VisibleRangeEnum.ME.getCode())) {
            return false;
        }
        if (Objects.equals(visibleRange, VisibleRangeEnum.FANS.getCode())) {
            return canAccessFansContent(article.getUserId(), safeCurrentUserId);
        }
        return false;
    }

    /**
     * 粉丝可见要求当前用户已登录，且已关注作者本人。
     */
    @Override
    public boolean canAccessFansContent(Integer authorId, Integer currentUserId) {
        if (authorId == null || currentUserId == null || currentUserId <= 0) {
            return false;
        }
        if (Objects.equals(authorId, currentUserId)) {
            return true;
        }
        return Boolean.TRUE.equals(followService.isFollowing(currentUserId, authorId));
    }
}
