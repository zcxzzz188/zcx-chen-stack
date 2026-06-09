package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.config.ChenStackConfig;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.constants.MessageConstants;
import com.zcx.chenstack.domain.constants.RabbitMQConstants;
import com.zcx.chenstack.domain.dto.*;
import com.zcx.chenstack.domain.entity.Article;
import com.zcx.chenstack.domain.entity.Comment;
import com.zcx.chenstack.domain.entity.Like;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.enums.LikeTypeEnum;
import com.zcx.chenstack.domain.enums.MessageTypeEnum;
import com.zcx.chenstack.domain.result.AuditResult;
import com.zcx.chenstack.domain.vo.AdminCommentVo;
import com.zcx.chenstack.domain.vo.CommentVo;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.UserCommentManageVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.ArticleMapper;
import com.zcx.chenstack.mapper.CommentMapper;
import com.zcx.chenstack.mapper.LikeMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.redis.NotificationThreadPool;
import com.zcx.chenstack.service.CommentService;
import com.zcx.chenstack.service.MessageService;
import com.zcx.chenstack.service.UserSettingsService;
import com.zcx.chenstack.utils.CountUpdateUtils;
import com.zcx.chenstack.utils.EntityCheckUtils;
import com.zcx.chenstack.utils.PageUtils;
import com.zcx.chenstack.utils.SecurityUtils;
import com.zcx.chenstack.utils.TextAuditUtils;
import com.zcx.chenstack.utils.XssUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论服务实现类
 *
 * @author zcx
 * @since 2025-09-15
 */
@Service
@Slf4j
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Resource
    private CommentMapper commentMapper;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private LikeMapper likeMapper;

    @Resource
    private TextAuditUtils textAuditUtils;

    @Resource
    private ChenStackConfig chenStackConfig;

    @Resource
    private MessageService messageService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private UserSettingsService userSettingsService;

    @Resource
    private NotificationThreadPool notificationThreadPool;

    @Override
    @Transactional
    public Integer addComment(CommentDto commentDto) {
        Integer currentUserId = SecurityUtils.getUserId();

        // 验证父评论是否存在
        if (commentDto.getParentId() != null && commentDto.getParentId() > 0) {
            Comment parentComment = getById(commentDto.getParentId());
            EntityCheckUtils.getOrThrowNotDeleted(parentComment, BlogConstants.NotFoundParentComment);

            // 如果父评论的审核状态不是通过，不允许回复
            if (parentComment.getExamineStatus() != 1) {
                throw new BlogException(BlogConstants.CannotReplyUnApprovedComment);
            }
        }

        // 创建评论实体
        Comment comment = new Comment();
        BeanUtil.copyProperties(commentDto, comment);
        comment.setUserId(currentUserId);
        // 先设置为待审核状态
        comment.setExamineStatus(ExamineStatusEnum.WAIT.getCode());

        // XSS 过滤评论内容，防止 XSS 攻击
        comment.setContent(XssUtils.cleanRichText(commentDto.getContent()));

        // 保存评论
        boolean saved = save(comment);
        if (!saved) {
            throw new BlogException(BlogConstants.AddCommentError);
        }

        // 保存成功后进行文字内容审核
        auditComment(comment, commentDto.getContent());

        return comment.getId();
    }

    /**
     * 评论审核
     *
     * @param comment 评论实体
     * @param content 评论内容
     */
    private void auditComment(Comment comment, String content) {
        Integer commentId = comment.getId();
        Integer userId = comment.getUserId();

        MessageDto messageDto = new MessageDto();
        messageDto.setType(MessageTypeEnum.SYSTEM.getCode());

        // 进行文字内容审核
        if (chenStackConfig.isCommentAutoAudit()) {
            try {
                AuditResult auditResult = textAuditUtils.auditTextWithDetailsSplit(content);

                Comment updateComment = new Comment();
                updateComment.setId(commentId);

                if (auditResult.getStatus().equals(ExamineStatusEnum.PASS.getCode())) {
                    // 审核通过
                    updateComment.setExamineStatus(ExamineStatusEnum.PASS.getCode());
                    updateById(updateComment);

                    // 审核通过后更新统计数据
                    updateCommentStatistics(comment);

                    // 发送评论通知
                    sendCommentNotification(comment);

                } else if (auditResult.getStatus().equals(ExamineStatusEnum.NO_PASS.getCode())) {
                    // 审核不通过，发送消息给用户
                    updateComment.setExamineStatus(ExamineStatusEnum.NO_PASS.getCode());
                    updateById(updateComment);
                    log.warn("评论文字审核不通过，评论ID: {}, 用户ID: {}, 原因: {}", commentId, userId, auditResult.getErrorMessage());

                    messageDto
                            .setContent(MessageConstants.CommentAuditNotPass(commentId, auditResult.getErrorMessage()));
                    messageDto.setReceiverId(userId);
                    messageService.sendToUser(messageDto);

                } else {
                    // 需要人工审核，发送消息给管理员并发送邮件
                    updateComment.setExamineStatus(ExamineStatusEnum.WAIT.getCode());
                    updateById(updateComment);

                    // 发送消息给管理员
                    messageDto.setContent(MessageConstants.CommentNeedReview(commentId, auditResult.getErrorMessage()));
                    messageService.sendToAdmin(messageDto);

                    // 发送邮件给管理员
                    HashMap<String, Object> sendEmail = new HashMap<>();
                    sendEmail.put("text", String.format(MessageConstants.COMMENT_NEED_REVIEW, commentId,
                            auditResult.getErrorMessage()));
                    rabbitTemplate.convertAndSend(RabbitMQConstants.Examine_Exchange,
                            RabbitMQConstants.Examine_Routing_Key, sendEmail);
                }
            } catch (Exception e) {
                log.error("评论文字审核过程中发生异常，评论ID: {}, 用户ID: {}, 错误信息: {}", commentId, userId, e.getMessage(), e);
                // 审核异常时，保持待审核状态，发送消息给管理员
                messageDto.setContent(MessageConstants.CommentNeedReview(commentId, "审核服务异常，需要人工审核"));
                messageService.sendToAdmin(messageDto);

                // 发送邮件给管理员
                HashMap<String, Object> sendEmail = new HashMap<>();
                sendEmail.put("text", String.format(MessageConstants.COMMENT_NEED_REVIEW, commentId, "审核服务异常，需要人工审核"));
                rabbitTemplate.convertAndSend(RabbitMQConstants.Examine_Exchange,
                        RabbitMQConstants.Examine_Routing_Key, sendEmail);
            }
        } else {
            // 如果没有开启自动审核，需要人工审核，发送消息给管理员
            messageDto.setContent(MessageConstants.CommentNeedReview(commentId, "未开启自动审核"));
            messageService.sendToAdmin(messageDto);

            // 发送邮件给管理员
            HashMap<String, Object> sendEmail = new HashMap<>();
            sendEmail.put("text", String.format(MessageConstants.COMMENT_NEED_REVIEW, commentId, "未开启自动审核"));
            rabbitTemplate.convertAndSend(RabbitMQConstants.Examine_Exchange,
                    RabbitMQConstants.Examine_Routing_Key, sendEmail);
        }
    }

    /**
     * 更新评论统计数据
     * 在评论审核通过时调用，增加文章评论数和父评论回复数
     *
     * @param comment 评论对象
     */
    private void updateCommentStatistics(Comment comment) {
        // 如果是回复评论，更新父评论的回复数
        if (comment.getParentId() != null && comment.getParentId() > 0) {
            LambdaUpdateWrapper<Comment> replyUpdateWrapper = new LambdaUpdateWrapper<>();
            replyUpdateWrapper.eq(Comment::getId, comment.getParentId())
                    .setIncrBy(Comment::getReplyCount, 1);
            int replyUpdateResult = commentMapper.update(null, replyUpdateWrapper);
            if (replyUpdateResult < 0) {
                log.warn("更新父评论回复数失败，父评论ID：{}，评论ID：{}", comment.getParentId(), comment.getId());
            }
            return;
        }

        // 仅父评论（parentId=0）计入文章评论数
        if (comment.getParentId() != null && comment.getParentId() == 0) {
            LambdaUpdateWrapper<Article> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Article::getId, comment.getArticleId())
                    .setIncrBy(Article::getCommentCount, 1);
            int articleUpdateResult = articleMapper.update(null, updateWrapper);
            if (articleUpdateResult < 0) {
                log.warn("更新文章评论数失败，文章ID：{}，评论ID：{}", comment.getArticleId(), comment.getId());
            }
        }
    }

    /**
     * 同步文章父评论数量，避免管理端审核状态反复切换时重复累加
     *
     * @param articleId 文章ID
     */
    private void syncArticleCommentCount(Integer articleId) {
        if (articleId == null || articleId <= 0) {
            return;
        }

        long commentCount = count(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getParentId, 0)
                .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                .eq(Comment::getIsDeleted, 0));

        LambdaUpdateWrapper<Article> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Article::getId, articleId)
                .set(Article::getCommentCount, Math.toIntExact(commentCount));
        int updateResult = articleMapper.update(null, updateWrapper);
        if (updateResult < 0) {
            log.warn("同步文章评论数失败，文章ID：{}", articleId);
        }
    }

    @Override
    @Transactional
    public void deleteComment(Integer commentId) {
        Integer currentUserId = SecurityUtils.getUserId();

        Comment comment = getById(commentId);
        EntityCheckUtils.getOrThrowNotDeleted(comment, BlogConstants.NotFoundComment);

        boolean isCommentOwner = comment.getUserId() != null && comment.getUserId().equals(currentUserId);
        boolean isArticleOwner = false;
        if (!isCommentOwner) {
            Article article = articleMapper.selectById(comment.getArticleId());
            EntityCheckUtils.getOrThrowNotDeleted(article, BlogConstants.NotFoundArticle);
            isArticleOwner = article.getUserId() != null && article.getUserId().equals(currentUserId);
        }

        // 只有评论作者本人或文章作者可以删除
        if (!isCommentOwner && !isArticleOwner) {
            throw new BlogException(BlogConstants.CannotDeleteOthersComment);
        }

        cleanupAndDeleteComments(Collections.singletonList(commentId));
    }

    @Override
    public PageVo<List<CommentVo>> getCommentList(Integer articleId, Integer pageNum, Integer pageSize) {
        if (articleId == null || articleId <= 0) {
            throw new BlogException(BlogConstants.ArticleIdRequired);
        }

        Integer offset = (pageNum - 1) * pageSize;
        // 添加参数边界验证，防止负数或过大值导致 SQL 错误
        offset = Math.max(0, offset);
        pageSize = Math.min(Math.max(1, pageSize), 100);
        Integer currentUserId = getCurrentUserIdOrNull();

        // 1. 查询顶级评论基础信息
        LambdaQueryWrapper<Comment> topCommentWrapper = buildVisibleCommentWrapper(articleId, 0, currentUserId)
                .orderByDesc(Comment::getCreateTime)
                .last("LIMIT " + offset + ", " + pageSize);

        List<Comment> topComments = list(topCommentWrapper);
        if (topComments.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), 0L);
        }

        Set<Integer> topCommentIds = topComments.stream().map(Comment::getId).collect(Collectors.toSet());

        // 2. 批量查询子评论（每个顶级评论最多2条）
        List<Comment> allReplies = batchQueryReplies(articleId, topCommentIds, 2, currentUserId);

        // 3. 批量构建CommentVo
        List<CommentVo> result = batchBuildCommentVos(topComments, allReplies, currentUserId, true);

        // 4. 查询总数
        Long total = count(buildVisibleCommentWrapper(articleId, 0, currentUserId));

        return new PageVo<>(result, total);
    }

    @Override
    public PageVo<List<CommentVo>> getReplyList(Integer commentId, Integer pageNum, Integer pageSize) {
        if (commentId == null || commentId <= 0) {
            throw new BlogException(BlogConstants.CommentIdRequired);
        }

        Comment parentComment = getById(commentId);
        EntityCheckUtils.getOrThrowNotDeleted(parentComment, BlogConstants.NotFoundParentComment);

        Integer offset = (pageNum - 1) * pageSize;
        // 添加参数边界验证，防止负数或过大值导致 SQL 错误
        offset = Math.max(0, offset);
        pageSize = Math.min(Math.max(1, pageSize), 100);
        Integer currentUserId = getCurrentUserIdOrNull();

        // 1. 查询回复评论基础信息
        LambdaQueryWrapper<Comment> replyWrapper = buildVisibleCommentWrapper(parentComment.getArticleId(), commentId, currentUserId)
                .orderByDesc(Comment::getCreateTime)
                .last("LIMIT " + offset + ", " + pageSize);

        List<Comment> replies = list(replyWrapper);
        if (replies.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), 0L);
        }

        // 2. 批量构建CommentVo（回复列表不需要子评论）
        List<CommentVo> result = batchBuildCommentVos(replies, new ArrayList<>(), currentUserId, false);

        // 3. 查询总数
        Long total = count(buildVisibleCommentWrapper(parentComment.getArticleId(), commentId, currentUserId));

        return new PageVo<>(result, total);
    }

    /**
     * 批量查询子评论
     *
     * @param articleId      文章ID
     * @param parentIds      父评论ID集合
     * @param limitPerParent 每个父评论最多查询的子评论数量
     * @return 子评论列表
     */
    private List<Comment> batchQueryReplies(Integer articleId, Set<Integer> parentIds, int limitPerParent,
            Integer currentUserId) {
        if (parentIds.isEmpty()) {
            return new ArrayList<>();
        }

        // 查询所有子评论
        LambdaQueryWrapper<Comment> replyWrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .in(Comment::getParentId, parentIds)
                .eq(Comment::getIsDeleted, 0)
                .orderByDesc(Comment::getCreateTime);
        appendVisibleCommentCondition(replyWrapper, currentUserId);

        List<Comment> allReplies = list(replyWrapper);

        // 按父评论分组并限制数量
        Map<Integer, List<Comment>> replyMap = allReplies.stream()
                .collect(Collectors.groupingBy(Comment::getParentId));

        List<Comment> limitedReplies = new ArrayList<>();
        replyMap.forEach((parentId, replies) -> {
            limitedReplies.addAll(replies.stream().limit(limitPerParent).collect(Collectors.toList()));
        });

        return limitedReplies;
    }

    private Integer getCurrentUserIdOrNull() {
        Integer currentUserId = SecurityUtils.getUserId();
        return currentUserId == null || currentUserId <= 0 ? null : currentUserId;
    }

    private LambdaQueryWrapper<Comment> buildVisibleCommentWrapper(Integer articleId, Integer parentId,
            Integer currentUserId) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getParentId, parentId)
                .eq(Comment::getIsDeleted, 0);
        appendVisibleCommentCondition(wrapper, currentUserId);
        return wrapper;
    }

    private void appendVisibleCommentCondition(LambdaQueryWrapper<Comment> wrapper, Integer currentUserId) {
        if (currentUserId == null) {
            wrapper.eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode());
            return;
        }

        wrapper.and(visibleWrapper -> visibleWrapper
                .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                .or(selfWrapper -> selfWrapper
                        .eq(Comment::getExamineStatus, ExamineStatusEnum.WAIT.getCode())
                        .eq(Comment::getUserId, currentUserId)));
    }

    /**
     * 批量构建CommentVo对象
     *
     * @param mainComments  主要评论列表（顶级评论或回复评论）
     * @param subComments   子评论列表
     * @param currentUserId 当前用户ID
     * @param withChildren  是否包含子评论
     * @return CommentVo列表
     */
    private List<CommentVo> batchBuildCommentVos(List<Comment> mainComments, List<Comment> subComments,
            Integer currentUserId, boolean withChildren) {
        if (mainComments.isEmpty()) {
            return new ArrayList<>();
        }

        // 收集所有需要的用户ID
        Set<Integer> allUserIds = new HashSet<>();
        Set<Integer> allCommentIds = new HashSet<>();

        // 收集主要评论的用户ID和评论ID
        mainComments.forEach(comment -> {
            allUserIds.add(comment.getUserId());
            allCommentIds.add(comment.getId());
            if (comment.getReplyUserId() != null) {
                allUserIds.add(comment.getReplyUserId());
            }
        });

        // 收集子评论的用户ID和评论ID
        subComments.forEach(comment -> {
            allUserIds.add(comment.getUserId());
            allCommentIds.add(comment.getId());
            if (comment.getReplyUserId() != null) {
                allUserIds.add(comment.getReplyUserId());
            }
        });

        // 批量查询用户信息
        final Map<Integer, SysUser> userMap;
        if (!allUserIds.isEmpty()) {
            LambdaQueryWrapper<SysUser> userWrapper = new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getId, allUserIds)
                    .select(SysUser::getId, SysUser::getNickname, SysUser::getAvatar);

            List<SysUser> users = sysUserMapper.selectList(userWrapper);
            userMap = users.stream().collect(Collectors.toMap(SysUser::getId, u -> u));
        } else {
            userMap = new HashMap<>();
        }

        // 批量查询点赞统计
        final Map<Integer, Long> likeCountMap;
        if (!allCommentIds.isEmpty()) {
            LambdaQueryWrapper<Like> likeCountWrapper = new LambdaQueryWrapper<Like>()
                    .eq(Like::getType, 1)
                    .in(Like::getTypeId, allCommentIds)
                    .select(Like::getTypeId);

            List<Like> likes = likeMapper.selectList(likeCountWrapper);
            likeCountMap = likes.stream()
                    .collect(Collectors.groupingBy(Like::getTypeId, Collectors.counting()));
        } else {
            likeCountMap = new HashMap<>();
        }

        // 查询当前用户点赞状态
        final Set<Integer> userLikedIds;
        if (currentUserId != null && !allCommentIds.isEmpty()) {
            LambdaQueryWrapper<Like> userLikeWrapper = new LambdaQueryWrapper<Like>()
                    .eq(Like::getType, 1)
                    .eq(Like::getUserId, currentUserId)
                    .in(Like::getTypeId, allCommentIds)
                    .select(Like::getTypeId);

            List<Like> userLikes = likeMapper.selectList(userLikeWrapper);
            userLikedIds = userLikes.stream().map(Like::getTypeId).collect(Collectors.toSet());
        } else {
            userLikedIds = new HashSet<>();
        }

        // 构建结果
        if (withChildren && !subComments.isEmpty()) {
            // 构建子评论映射
            Map<Integer, List<CommentVo>> replyMap = subComments.stream()
                    .map(reply -> buildCommentVo(reply, userMap, likeCountMap, userLikedIds))
                    .collect(Collectors.groupingBy(CommentVo::getParentId));

            // 构建主评论并设置子评论
            return mainComments.stream()
                    .map(comment -> {
                        CommentVo vo = buildCommentVo(comment, userMap, likeCountMap, userLikedIds);
                        vo.setChildren(replyMap.getOrDefault(comment.getId(), new ArrayList<>()));
                        return vo;
                    })
                    .collect(Collectors.toList());
        } else {
            // 只构建主评论
            return mainComments.stream()
                    .map(comment -> buildCommentVo(comment, userMap, likeCountMap, userLikedIds))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 构建评论VO对象
     *
     * @param comment      评论实体
     * @param userMap      用户信息映射
     * @param likeCountMap 点赞数映射
     * @param userLikedIds 当前用户点赞的评论ID集合
     * @return 评论VO
     */
    private CommentVo buildCommentVo(Comment comment, Map<Integer, SysUser> userMap,
            Map<Integer, Long> likeCountMap, Set<Integer> userLikedIds) {
        CommentVo vo = BeanUtil.copyProperties(comment, CommentVo.class);

        // 设置用户信息
        SysUser user = userMap.get(comment.getUserId());
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }

        // 设置回复用户信息
        if (comment.getReplyUserId() != null) {
            SysUser replyUser = userMap.get(comment.getReplyUserId());
            if (replyUser != null) {
                vo.setReplyUserNickname(replyUser.getNickname());
            }
        }

        // 设置点赞信息
        vo.setLikeCount(Math.toIntExact(likeCountMap.getOrDefault(comment.getId(), 0L)));
        // 设置当前用户是否点赞
        vo.setIsLiked(userLikedIds.contains(comment.getId()));

        return vo;
    }

    @Override
    public PageVo<List<AdminCommentVo>> adminGetCommentList(Integer pageNum, Integer pageSize) {
        Page<Comment> page = new Page<>(pageNum, pageSize);
        // 构建查询条件 - 获取所有未删除的评论
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<Comment>()
                .orderByDesc(Comment::getCreateTime);

        List<Comment> comments = page(page, queryWrapper).getRecords();

        if (comments.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), page.getTotal());
        }

        // 批量构建AdminCommentVo
        return new PageVo<>(batchBuildAdminCommentVos(comments), page.getTotal());
    }

    @Override
    public PageVo<List<AdminCommentVo>> adminGetCommentsByUserId(Integer userId, Integer pageNum, Integer pageSize) {
        if (userId == null || userId <= 0) {
            throw new BlogException(BlogConstants.UserIdRequired);
        }

        // 验证用户是否存在
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }

        Page<Comment> page = new Page<>(pageNum, pageSize);
        // 查询该用户的所有评论（包括已删除的）
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getUserId, userId)
                .eq(Comment::getIsDeleted, 0)
                .orderByDesc(Comment::getCreateTime);

        List<Comment> comments = page(page, queryWrapper).getRecords();

        if (comments.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), page.getTotal());
        }

        // 批量构建AdminCommentVo
        return new PageVo<>(batchBuildAdminCommentVos(comments), page.getTotal());
    }

    @Override
    public PageVo<List<AdminCommentVo>> adminSearchComment(CommentSearchDto commentSearchDto) {
        Page<Comment> page = new Page<>(commentSearchDto.getPageNum(), commentSearchDto.getPageSize());
        // 构建查询条件
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getIsDeleted, 0)
                .orderByDesc(Comment::getCreateTime);

        queryWrapper
                // 根据审核状态筛选
                .eq(commentSearchDto.getExamineStatus() != null, Comment::getExamineStatus,
                        commentSearchDto.getExamineStatus())
                // 根据用户ID筛选
                .eq(commentSearchDto.getUserId() != null && commentSearchDto.getUserId() > 0, Comment::getUserId,
                        commentSearchDto.getUserId())
                // 根据文章ID筛选
                .eq(commentSearchDto.getArticleId() != null && commentSearchDto.getArticleId() > 0,
                        Comment::getArticleId, commentSearchDto.getArticleId())
                // 根据关键词搜索评论内容
                .like(commentSearchDto.getKeyword() != null && !commentSearchDto.getKeyword().trim().isEmpty(),
                        Comment::getContent,
                        commentSearchDto.getKeyword() != null ? commentSearchDto.getKeyword().trim() : null)
                // 根据创建时间范围筛选
                .ge(commentSearchDto.getCreateTimeStart() != null, Comment::getCreateTime,
                        commentSearchDto.getCreateTimeStart())
                .le(commentSearchDto.getCreateTimeEnd() != null, Comment::getCreateTime,
                        commentSearchDto.getCreateTimeEnd());

        List<Comment> comments = page(page, queryWrapper).getRecords();

        if (comments.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), page.getTotal());
        }

        // 批量构建AdminCommentVo
        return new PageVo<>(batchBuildAdminCommentVos(comments), page.getTotal());
    }

    /**
     * 批量构建管理员评论VO对象
     *
     * @param comments 评论列表
     * @return AdminCommentVo列表
     */
    private List<AdminCommentVo> batchBuildAdminCommentVos(List<Comment> comments) {
        if (comments.isEmpty()) {
            return new ArrayList<>();
        }

        // 收集需要查询的用户ID和文章ID
        Set<Integer> userIds = new HashSet<>();
        Set<Integer> articleIds = new HashSet<>();
        Set<Integer> commentIds = new HashSet<>();

        comments.forEach(comment -> {
            userIds.add(comment.getUserId());
            articleIds.add(comment.getArticleId());
            commentIds.add(comment.getId());
            if (comment.getReplyUserId() != null) {
                userIds.add(comment.getReplyUserId());
            }
        });

        // 批量查询用户信息
        final Map<Integer, SysUser> userMap;
        if (!userIds.isEmpty()) {
            LambdaQueryWrapper<SysUser> userWrapper = new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getId, userIds)
                    .select(SysUser::getId, SysUser::getNickname, SysUser::getAvatar);
            List<SysUser> users = sysUserMapper.selectList(userWrapper);
            userMap = users.stream().collect(Collectors.toMap(SysUser::getId, u -> u));
        } else {
            userMap = new HashMap<>();
        }

        // 批量查询文章信息
        final Map<Integer, Article> articleMap;
        if (!articleIds.isEmpty()) {
            LambdaQueryWrapper<Article> articleWrapper = new LambdaQueryWrapper<Article>()
                    .in(Article::getId, articleIds)
                    .select(Article::getId, Article::getTitle);
            List<Article> articles = articleMapper.selectList(articleWrapper);
            articleMap = articles.stream().collect(Collectors.toMap(Article::getId, a -> a));
        } else {
            articleMap = new HashMap<>();
        }

        // 批量查询点赞统计
        final Map<Integer, Long> likeCountMap;
        if (!commentIds.isEmpty()) {
            LambdaQueryWrapper<Like> likeCountWrapper = new LambdaQueryWrapper<Like>()
                    .eq(Like::getType, 1)
                    .in(Like::getTypeId, commentIds)
                    .select(Like::getTypeId);
            List<Like> likes = likeMapper.selectList(likeCountWrapper);
            likeCountMap = likes.stream()
                    .collect(Collectors.groupingBy(Like::getTypeId, Collectors.counting()));
        } else {
            likeCountMap = new HashMap<>();
        }

        // 构建结果
        return comments.stream()
                .map(comment -> buildAdminCommentVo(comment, userMap, articleMap, likeCountMap))
                .collect(Collectors.toList());
    }

    /**
     * 构建管理员评论VO对象
     *
     * @param comment      评论实体
     * @param userMap      用户信息映射
     * @param articleMap   文章信息映射
     * @param likeCountMap 点赞数映射
     * @return 管理员评论VO
     */
    private AdminCommentVo buildAdminCommentVo(Comment comment, Map<Integer, SysUser> userMap,
            Map<Integer, Article> articleMap, Map<Integer, Long> likeCountMap) {
        AdminCommentVo vo = BeanUtil.copyProperties(comment, AdminCommentVo.class);

        // 设置用户信息
        SysUser user = userMap.get(comment.getUserId());
        if (user != null) {
            vo.setNickname(user.getNickname());
            vo.setAvatar(user.getAvatar());
        }

        // 设置回复用户信息
        if (comment.getReplyUserId() != null) {
            SysUser replyUser = userMap.get(comment.getReplyUserId());
            if (replyUser != null) {
                vo.setReplyUserNickname(replyUser.getNickname());
            }
        }

        // 设置文章信息
        Article article = articleMap.get(comment.getArticleId());
        if (article != null) {
            vo.setArticleTitle(article.getTitle());
        }

        // 设置点赞数
        vo.setLikeCount(Math.toIntExact(likeCountMap.getOrDefault(comment.getId(), 0L)));

        return vo;
    }

    @Override
    public void adminExamineComment(CommentAuditDto commentAuditDto) {
        if (commentAuditDto == null || commentAuditDto.getCommentId() == null || commentAuditDto.getCommentId() <= 0) {
            throw new BlogException(BlogConstants.CommentIdRequired);
        }

        Integer examineStatus = commentAuditDto.getExamineStatus();
        if (examineStatus == null || examineStatus < 0 || examineStatus > 2) {
            throw new BlogException(BlogConstants.CommentExamineStatusError);
        }

        Comment comment = getById(commentAuditDto.getCommentId());
        EntityCheckUtils.getOrThrowNotDeleted(comment, BlogConstants.NotFoundComment);
        boolean isParentComment = comment.getParentId() != null && comment.getParentId() == 0;

        comment.setExamineStatus(examineStatus);
        // 如果审核未通过且有原因，可以记录原因（这里暂时不保存到数据库）
        boolean updated = updateById(comment);
        if (!updated) {
            throw new BlogException(BlogConstants.CommentAuditError);
        }

        if (isParentComment) {
            syncArticleCommentCount(comment.getArticleId());
        }

        // 如果审核通过，更新统计数据和发送通知
        if (examineStatus.equals(ExamineStatusEnum.PASS.getCode())) {
            if (!isParentComment) {
                updateCommentStatistics(comment);
            }
            // 发送评论通知
            sendCommentNotification(comment);
        }
    }

    /**
     * 发送评论通知（异步）
     * 
     * @param comment 评论对象
     */
    private void sendCommentNotification(Comment comment) {
        // 使用线程池异步执行
        notificationThreadPool.getNotificationPool("comment").execute(() -> {
            try {
                // 查询文章信息
                Article article = articleMapper.selectById(comment.getArticleId());
                if (article == null) {
                    log.warn("发送评论通知失败：文章不存在，articleId={}", comment.getArticleId());
                    return;
                }

                // 查询评论者信息
                SysUser commenter = sysUserMapper.selectById(comment.getUserId());
                if (commenter == null) {
                    log.warn("发送评论通知失败：评论者不存在，userId={}", comment.getUserId());
                    return;
                }

                // 如果是回复评论，通知被回复的用户
                if (comment.getReplyUserId() != null && comment.getReplyUserId() > 0) {
                    messageService.sendReplyNotification(
                            comment.getUserId(),
                            comment.getReplyUserId(),
                            commenter.getNickname(),
                            article.getTitle(),
                            article.getId(),
                            article.getUserId(), // 文章作者ID
                            comment.getContent()); // 回复内容

                    // 发送邮件通知给被回复者
                    sendCommentEmailNotification(
                            comment.getReplyUserId(),
                            commenter.getNickname(),
                            article.getTitle(),
                            article.getId(),
                            comment.getContent(),
                            true,
                            null);
                } else {
                    // 否则通知文章作者
                    messageService.sendCommentNotification(
                            comment.getUserId(),
                            article.getUserId(),
                            commenter.getNickname(),
                            article.getTitle(),
                            article.getId(),
                            comment.getContent()); // 评论内容

                    // 发送邮件通知给文章作者
                    sendCommentEmailNotification(
                            article.getUserId(),
                            commenter.getNickname(),
                            article.getTitle(),
                            article.getId(),
                            comment.getContent(),
                            false,
                            null);
                }
            } catch (Exception e) {
                log.error("发送评论通知失败：commentId={}", comment.getId(), e);
            }
        });
    }

    /**
     * 发送评论邮件通知
     *
     * @param notifiedUserId 被通知的用户 ID
     * @param commenterNickname 评论者昵称
     * @param articleTitle 文章标题
     * @param articleId 文章 ID
     * @param commentContent 评论内容
     * @param isReply 是否是回复评论
     * @param parentCommentContent 父评论内容（如果是回复）
     */
    private void sendCommentEmailNotification(Integer notifiedUserId, String commenterNickname,
            String articleTitle, Integer articleId, String commentContent,
            Boolean isReply, String parentCommentContent) {
        try {
            // 检查用户是否开启了评论邮件通知
            Integer receiveCommentEmail = userSettingsService.getReceiveCommentEmail(notifiedUserId);
            if (receiveCommentEmail == 0) {
                
                return;
            }

            // 查询用户邮箱
            SysUser notifiedUser = sysUserMapper.selectById(notifiedUserId);
            if (notifiedUser == null || notifiedUser.getEmail() == null) {
                log.warn("发送邮件通知失败：用户 {} 不存在或邮箱为空", notifiedUserId);
                return;
            }

            // 发送邮件到队列
            HashMap<String, Object> emailMessage = new HashMap<>();
            emailMessage.put("email", notifiedUser.getEmail());
            emailMessage.put("recipientNickname", notifiedUser.getNickname());
            emailMessage.put("commenterNickname", commenterNickname);
            emailMessage.put("articleTitle", articleTitle);
            emailMessage.put("articleId", articleId);
            emailMessage.put("commentContent", commentContent);
            emailMessage.put("isReply", isReply);
            emailMessage.put("parentCommentContent", parentCommentContent);
            emailMessage.put("type", "commentNotification");

            rabbitTemplate.convertAndSend(
                    RabbitMQConstants.Email_Exchange,
                    RabbitMQConstants.Comment_Email_Routing_Key,
                    emailMessage);

            
        } catch (Exception e) {
            log.error("发送评论邮件通知失败：notifiedUserId={}", notifiedUserId, e);
        }
    }

    @Override
    public void adminExamineBatchComment(List<CommentAuditDto> commentAuditDtos) {
        if (commentAuditDtos == null || commentAuditDtos.isEmpty()) {
            throw new BlogException(BlogConstants.CommentIdRequired);
        }

        for (CommentAuditDto commentAuditDto : commentAuditDtos) {
            if (commentAuditDto.getCommentId() == null || commentAuditDto.getCommentId() <= 0) {
                continue; // 跳过无效的评论ID
            }

            try {
                adminExamineComment(commentAuditDto);
            } catch (Exception e) {
                log.error("批量审核评论失败，评论ID：{}，错误：{}", commentAuditDto.getCommentId(), e.getMessage());
                // 继续处理其他评论，不中断整个批量操作
            }
        }
    }

    @Override
    @Transactional
    public void adminDeleteComment(Integer commentId) {
        if (commentId == null || commentId <= 0) {
            throw new BlogException(BlogConstants.CommentIdRequired);
        }

        Comment comment = getById(commentId);
        EntityCheckUtils.getOrThrowNotDeleted(comment, BlogConstants.NotFoundComment);

        cleanupAndDeleteComments(Collections.singletonList(commentId));
    }

    @Override
    @Transactional
    public void adminDeleteBatchComment(List<Integer> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            throw new BlogException(BlogConstants.CommentIdRequired);
        }

        // 过滤无效的评论ID
        List<Integer> validCommentIds = commentIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toList());

        if (validCommentIds.isEmpty()) {
            throw new BlogException(BlogConstants.CommentIdRequired);
        }

        // 验证评论是否存在
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<Comment>()
                .in(Comment::getId, validCommentIds)
                .eq(Comment::getIsDeleted, 0);

        List<Comment> existingComments = list(queryWrapper);

        if (existingComments.isEmpty()) {
            throw new BlogException(BlogConstants.NotFoundComment);
        }

        cleanupAndDeleteComments(validCommentIds);
    }

    private void cleanupAndDeleteComments(Collection<Integer> rootCommentIds) {
        if (ObjectUtil.isEmpty(rootCommentIds)) {
            return;
        }

        List<Integer> normalizedRootCommentIds = rootCommentIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (ObjectUtil.isEmpty(normalizedRootCommentIds)) {
            return;
        }

        List<Comment> rootComments = list(new LambdaQueryWrapper<Comment>()
                .in(Comment::getId, normalizedRootCommentIds)
                .eq(Comment::getIsDeleted, 0));
        if (ObjectUtil.isEmpty(rootComments)) {
            throw new BlogException(BlogConstants.NotFoundComment);
        }

        Map<Integer, Comment> commentsToDelete = new LinkedHashMap<>();
        rootComments.forEach(comment -> collectCommentTree(comment, commentsToDelete));
        if (commentsToDelete.isEmpty()) {
            return;
        }

        Set<Integer> deletedCommentIds = commentsToDelete.keySet();
        Set<Integer> affectedArticleIds = commentsToDelete.values().stream()
                .map(Comment::getArticleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Integer> affectedParentIds = commentsToDelete.values().stream()
                .map(Comment::getParentId)
                .filter(Objects::nonNull)
                .filter(parentId -> parentId > 0)
                .filter(parentId -> !deletedCommentIds.contains(parentId))
                .collect(Collectors.toSet());

        likeMapper.delete(new LambdaQueryWrapper<Like>()
                .eq(Like::getType, LikeTypeEnum.COMMENT.getCode())
                .in(Like::getTypeId, deletedCommentIds));

        boolean deleted = removeByIds(new ArrayList<>(deletedCommentIds));
        if (!deleted) {
            throw new BlogException(BlogConstants.CommentDeleteError);
        }

        affectedArticleIds.forEach(this::syncArticleCommentCount);
        syncParentReplyCounts(affectedParentIds);
    }

    private void collectCommentTree(Comment comment, Map<Integer, Comment> commentsToDelete) {
        if (comment == null || comment.getId() == null || commentsToDelete.containsKey(comment.getId())) {
            return;
        }

        commentsToDelete.put(comment.getId(), comment);

        List<Comment> childComments = list(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getParentId, comment.getId())
                .eq(Comment::getIsDeleted, 0));
        if (ObjectUtil.isEmpty(childComments)) {
            return;
        }

        childComments.forEach(childComment -> collectCommentTree(childComment, commentsToDelete));
    }

    private void syncParentReplyCounts(Collection<Integer> parentIds) {
        if (ObjectUtil.isEmpty(parentIds)) {
            return;
        }

        List<Comment> parentComments = commentMapper.selectBatchIds(parentIds);
        if (ObjectUtil.isEmpty(parentComments)) {
            return;
        }

        parentComments.stream()
                .filter(Objects::nonNull)
                .filter(parentComment -> parentComment.getId() != null)
                .forEach(parentComment -> {
                    long replyCount = count(new LambdaQueryWrapper<Comment>()
                            .eq(Comment::getParentId, parentComment.getId())
                            .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                            .eq(Comment::getIsDeleted, 0));

                    LambdaUpdateWrapper<Comment> updateWrapper = new LambdaUpdateWrapper<>();
                    updateWrapper.eq(Comment::getId, parentComment.getId())
                            .set(Comment::getReplyCount, Math.toIntExact(replyCount));
                    int updateResult = commentMapper.update(null, updateWrapper);
                    if (updateResult < 0) {
                        log.warn("同步父评论回复数失败，父评论ID：{}", parentComment.getId());
                    }
                });
    }

    @Override
    public Long getCommentTotalCount() {
        return this.count(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getIsDeleted, 0)); // 只统计未删除的评论
    }

    @Override
    public PageVo<List<UserCommentManageVo>> getUserCommentManageList(Integer pageNum, Integer pageSize,
            CommentFilterDto commentFilterDto) {
        Integer currentUserId = SecurityUtils.getUserId(); // 当前登录用户ID

        Page<Comment> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getUserId, currentUserId) // 只查询当前用户的评论
                .eq(Comment::getIsDeleted, 0)
                .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode()) // 只返回审核通过的评论
                .orderByDesc(Comment::getCreateTime); // 按创建时间倒序

        applyCommentManageKeywordFilter(qw, commentFilterDto, findArticleIdsByTitleKeyword(commentFilterDto));
        applyCommentManageDateFilter(qw, commentFilterDto);

        List<Comment> comments = commentMapper.selectPage(page, qw).getRecords();
        List<UserCommentManageVo> userCommentManageVos = buildUserCommentManageVos(comments, Collections.emptyMap());

        return new PageVo<>(userCommentManageVos, page.getTotal());
    }

    @Override
    public PageVo<List<UserCommentManageVo>> getUserReceivedCommentManageList(Integer pageNum, Integer pageSize,
            CommentFilterDto commentFilterDto) {
        Integer currentUserId = SecurityUtils.getUserId();

        List<Article> ownedArticles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .eq(Article::getUserId, currentUserId)
                .eq(Article::getIsDeleted, 0)
                .select(Article::getId, Article::getUserId, Article::getTitle, Article::getCoverUrl));
        if (ObjectUtil.isEmpty(ownedArticles)) {
            return new PageVo<>(new ArrayList<>(), 0L);
        }

        Map<Integer, Article> articleMap = ownedArticles.stream()
                .filter(Objects::nonNull)
                .filter(article -> article.getId() != null)
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        List<Integer> ownedArticleIds = new ArrayList<>(articleMap.keySet());
        if (ownedArticleIds.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), 0L);
        }

        Page<Comment> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Comment> qw = new LambdaQueryWrapper<Comment>()
                .in(Comment::getArticleId, ownedArticleIds)
                .eq(Comment::getIsDeleted, 0)
                .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                .orderByDesc(Comment::getCreateTime);

        applyCommentManageKeywordFilter(qw, commentFilterDto, findMatchedOwnedArticleIdsByKeyword(commentFilterDto, articleMap));
        applyCommentManageDateFilter(qw, commentFilterDto);

        List<Comment> comments = commentMapper.selectPage(page, qw).getRecords();
        List<UserCommentManageVo> userCommentManageVos = buildUserCommentManageVos(comments, articleMap);
        return new PageVo<>(userCommentManageVos, page.getTotal());
    }

    private List<UserCommentManageVo> buildUserCommentManageVos(List<Comment> comments, Map<Integer, Article> preloadedArticleMap) {
        if (ObjectUtil.isEmpty(comments)) {
            return new ArrayList<>();
        }

        Map<Integer, Article> articleMap = new HashMap<>();
        if (preloadedArticleMap != null) {
            articleMap.putAll(preloadedArticleMap);
        }

        Set<Integer> missingArticleIds = comments.stream()
                .map(Comment::getArticleId)
                .filter(Objects::nonNull)
                .filter(articleId -> !articleMap.containsKey(articleId))
                .collect(Collectors.toSet());
        if (ObjectUtil.isNotEmpty(missingArticleIds)) {
            articleMapper.selectBatchIds(missingArticleIds).stream()
                    .filter(Objects::nonNull)
                    .filter(article -> article.getId() != null)
                    .forEach(article -> articleMap.put(article.getId(), article));
        }

        Set<Integer> userIds = new HashSet<>();
        Set<Integer> parentIds = new HashSet<>();
        comments.forEach(comment -> {
            if (comment.getUserId() != null) {
                userIds.add(comment.getUserId());
            }
            if (comment.getReplyUserId() != null) {
                userIds.add(comment.getReplyUserId());
            }
            if (comment.getParentId() != null && comment.getParentId() > 0) {
                parentIds.add(comment.getParentId());
            }
        });

        Map<Integer, SysUser> userMap = new HashMap<>();
        if (ObjectUtil.isNotEmpty(userIds)) {
            sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .in(SysUser::getId, userIds)
                            .select(SysUser::getId, SysUser::getNickname, SysUser::getAvatar))
                    .forEach(user -> userMap.put(user.getId(), user));
        }

        Map<Integer, Comment> parentCommentMap = new HashMap<>();
        if (ObjectUtil.isNotEmpty(parentIds)) {
            commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                            .in(Comment::getId, parentIds)
                            .eq(Comment::getIsDeleted, 0)
                            .select(Comment::getId, Comment::getContent))
                    .forEach(comment -> parentCommentMap.put(comment.getId(), comment));
        }

        return comments.stream().map(comment -> {
            UserCommentManageVo vo = new UserCommentManageVo();
            vo.setId(comment.getId());
            vo.setContent(comment.getContent());
            vo.setCreateTime(comment.getCreateTime());
            vo.setLikeCount(comment.getLikeCount());
            vo.setReplyCount(comment.getReplyCount());
            vo.setArticleId(comment.getArticleId());

            SysUser commentUser = userMap.get(comment.getUserId());
            if (commentUser != null) {
                vo.setCommentUserId(commentUser.getId());
                vo.setCommentUserNickname(commentUser.getNickname());
                vo.setCommentUserAvatar(commentUser.getAvatar());
            } else {
                vo.setCommentUserId(comment.getUserId());
            }

            if (comment.getReplyUserId() != null) {
                SysUser replyUser = userMap.get(comment.getReplyUserId());
                vo.setReplyUserId(comment.getReplyUserId());
                if (replyUser != null) {
                    vo.setReplyUserNickname(replyUser.getNickname());
                    vo.setReplyUserAvatar(replyUser.getAvatar());
                }
            }

            if (comment.getParentId() != null && comment.getParentId() > 0) {
                Comment parentComment = parentCommentMap.get(comment.getParentId());
                if (parentComment != null) {
                    vo.setReplyCommentContent(parentComment.getContent());
                }
            }

            Article article = articleMap.get(comment.getArticleId());
            if (article != null) {
                vo.setArticleTitle(article.getTitle());
                vo.setArticleCoverUrl(article.getCoverUrl());
                vo.setArticleUserId(article.getUserId());
            }

            return vo;
        }).collect(Collectors.toList());
    }

    private void applyCommentManageKeywordFilter(LambdaQueryWrapper<Comment> queryWrapper,
            CommentFilterDto commentFilterDto, Collection<Integer> matchedArticleIds) {
        if (commentFilterDto == null || ObjectUtil.isEmpty(commentFilterDto.getKeyword())) {
            return;
        }

        String keyword = commentFilterDto.getKeyword().trim();
        if (keyword.isEmpty()) {
            return;
        }

        if (ObjectUtil.isEmpty(matchedArticleIds)) {
            queryWrapper.like(Comment::getContent, keyword);
            return;
        }

        queryWrapper.and(wrapper -> wrapper
                .like(Comment::getContent, keyword)
                .or()
                .in(Comment::getArticleId, matchedArticleIds));
    }

    private void applyCommentManageDateFilter(LambdaQueryWrapper<Comment> queryWrapper, CommentFilterDto commentFilterDto) {
        if (commentFilterDto == null
                || (ObjectUtil.isEmpty(commentFilterDto.getYear()) && ObjectUtil.isEmpty(commentFilterDto.getMonth()))) {
            return;
        }

        if (ObjectUtil.isNotEmpty(commentFilterDto.getMonth())) {
            int year = ObjectUtil.isNotEmpty(commentFilterDto.getYear()) ? commentFilterDto.getYear()
                    : LocalDateTime.now().getYear();
            LocalDateTime firstDayOfMonth = LocalDateTime.of(year, commentFilterDto.getMonth(), 1, 0, 0, 0);
            LocalDateTime lastDayOfMonth = firstDayOfMonth.with(TemporalAdjusters.lastDayOfMonth())
                    .with(LocalTime.MAX);
            queryWrapper.between(Comment::getCreateTime, firstDayOfMonth, lastDayOfMonth);
            return;
        }

        LocalDateTime firstDayOfYear = LocalDateTime.of(commentFilterDto.getYear(), 1, 1, 0, 0, 0);
        LocalDateTime lastDayOfYear = LocalDateTime.of(commentFilterDto.getYear(), 12, 31, 23, 59, 59);
        queryWrapper.between(Comment::getCreateTime, firstDayOfYear, lastDayOfYear);
    }

    private List<Integer> findArticleIdsByTitleKeyword(CommentFilterDto commentFilterDto) {
        if (commentFilterDto == null || ObjectUtil.isEmpty(commentFilterDto.getKeyword())) {
            return Collections.emptyList();
        }

        String keyword = commentFilterDto.getKeyword().trim();
        if (keyword.isEmpty()) {
            return Collections.emptyList();
        }

        return articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .like(Article::getTitle, keyword)
                        .eq(Article::getIsDeleted, 0)
                        .select(Article::getId))
                .stream()
                .map(Article::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Integer> findMatchedOwnedArticleIdsByKeyword(CommentFilterDto commentFilterDto,
            Map<Integer, Article> articleMap) {
        if (commentFilterDto == null || ObjectUtil.isEmpty(commentFilterDto.getKeyword()) || ObjectUtil.isEmpty(articleMap)) {
            return Collections.emptyList();
        }

        String keyword = commentFilterDto.getKeyword().trim();
        if (keyword.isEmpty()) {
            return Collections.emptyList();
        }

        return articleMap.values().stream()
                .filter(Objects::nonNull)
                .filter(article -> ObjectUtil.isNotEmpty(article.getTitle()))
                .filter(article -> article.getTitle().contains(keyword))
                .map(Article::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

}
