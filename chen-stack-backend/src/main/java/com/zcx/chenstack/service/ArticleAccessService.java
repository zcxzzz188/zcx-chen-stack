package com.zcx.chenstack.service;

import com.zcx.chenstack.domain.entity.Article;

/**
 * 文章可见范围鉴权服务，统一处理公开、私有和粉丝访问判断。
 */
public interface ArticleAccessService {

    /**
     * 校验当前用户是否可以访问指定文章。
     */
    boolean canAccessArticle(Article article, Integer currentUserId);

    /** 完整读取权限，包含删除、发布、审核状态及作者例外。 */
    boolean canReadArticle(Article article, Integer currentUserId);

    /**
     * 校验当前用户是否可以访问粉丝可见内容。
     */
    boolean canAccessFansContent(Integer authorId, Integer currentUserId);

}
