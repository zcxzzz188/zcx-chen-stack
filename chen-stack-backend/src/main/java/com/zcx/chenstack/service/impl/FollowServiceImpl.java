package com.zcx.chenstack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.entity.Follow;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.SysUserVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.FollowMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.service.FollowService;
import com.zcx.chenstack.service.MessageService;
import com.zcx.chenstack.utils.SecurityUtils;
import com.zcx.chenstack.redis.NotificationThreadPool;
import com.zcx.chenstack.utils.CountUpdateUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-09-25
 */
@Service
@Slf4j
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements FollowService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private MessageService messageService;

    @Resource
    private NotificationThreadPool notificationThreadPool;

    @Override
    @Transactional
    public Boolean toggleFollow(Integer followedId) {
        // 参数校验
        if (followedId == null) {
            throw new BlogException(BlogConstants.FollowedUserIdRequired);
        }

        // 获取当前登录用户ID
        Integer followerId = SecurityUtils.getUserId();
        if (followerId == null || followerId == 0) {
            throw new BlogException(BlogConstants.LoginRequired);
        }

        // 不能关注自己
        if (followerId.equals(followedId)) {
            throw new BlogException(BlogConstants.CannotFollowSelf);
        }

        sysUserMapper.requireCommunityUser(followerId);
        sysUserMapper.requireCommunityUser(followedId);

        // 检查是否已关注
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowerId, followerId)
                .eq(Follow::getFollowedId, followedId);

        Follow existingFollow = this.getOne(queryWrapper);

        if (existingFollow != null) {
            // 已关注，执行取消关注
            boolean removed = this.remove(queryWrapper);
            if (!removed) {
                throw new BlogException(BlogConstants.UnfollowError);
            }

            // 异步更新用户计数（取消关注）
            asyncUpdateUserCounts(followerId, followedId, false);

            return false; // 返回false表示已取消关注
        } else {
            // 未关注，执行关注
            Follow follow = new Follow();
            follow.setFollowerId(followerId);
            follow.setFollowedId(followedId);
            follow.setCreateTime(LocalDateTime.now());

            boolean saved = this.save(follow);
            if (!saved) {
                throw new BlogException(BlogConstants.FollowError);
            }

            // 异步更新用户计数（关注）
            asyncUpdateUserCounts(followerId, followedId, true);

            // 发送关注通知
            sendFollowNotification(followerId, followedId);

            return true; // 返回true表示已关注
        }
    }

    /**
     * 发送关注通知（异步）
     * 
     * @param followerId 关注者ID
     * @param followedId 被关注者ID
     */
    private void sendFollowNotification(Integer followerId, Integer followedId) {
        // 使用线程池异步执行
        notificationThreadPool.getNotificationPool("follow").execute(() -> {
            try {
                // 查询关注者信息
                SysUser follower = sysUserMapper.selectById(followerId);
                if (follower == null) {
                    log.warn("发送关注通知失败：关注者不存在，followerId={}", followerId);
                    return;
                }

                // 发送通知
                messageService.sendFollowNotification(
                        followerId,
                        followedId,
                        follower.getNickname());
            } catch (Exception e) {
                log.error("发送关注通知失败：followerId={}, followedId={}", followerId, followedId, e);
            }
        });
    }

    @Override
    public Boolean isFollowing(Integer followerId, Integer followedId) {
        if (followerId == null || followedId == null) {
            return false;
        }

        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Follow::getFollowerId, followerId)
                .eq(Follow::getFollowedId, followedId)
                .inSql(Follow::getFollowerId, SysUserMapper.COMMUNITY_USER_IDS)
                .inSql(Follow::getFollowedId, SysUserMapper.COMMUNITY_USER_IDS);

        return this.exists(queryWrapper);
    }

    @Override
    public PageVo<List<SysUserVo>> getFollowList(Integer userId, Integer pageNum, Integer pageSize) {
        return getCommunityRelations(userId, pageNum, pageSize, Follow::getFollowerId, Follow::getFollowedId);
    }

    @Override
    public PageVo<List<SysUserVo>> getFansList(Integer userId, Integer pageNum, Integer pageSize) {
        return getCommunityRelations(userId, pageNum, pageSize, Follow::getFollowedId, Follow::getFollowerId);
    }

    private PageVo<List<SysUserVo>> getCommunityRelations(Integer userId, Integer pageNum, Integer pageSize,
            SFunction<Follow, Integer> owner, SFunction<Follow, Integer> peer) {
        sysUserMapper.requireCommunityUser(userId);
        Page<Follow> page = this.page(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<Follow>()
                .eq(owner, userId)
                .inSql(peer, SysUserMapper.COMMUNITY_USER_IDS)
                .orderByDesc(Follow::getCreateTime, Follow::getId));
        List<Integer> peerIds = page.getRecords().stream().map(peer).toList();
        if (peerIds.isEmpty()) {
            return new PageVo<>(List.of(), page.getTotal());
        }

        Map<Integer, SysUser> users = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                        .select("id", "nickname", "avatar", "introduction", "sex", "username",
                                "(SELECT COUNT(*) FROM follow f WHERE f.followed_id = sys_user.id"
                                        + " AND f.follower_id IN (" + SysUserMapper.COMMUNITY_USER_IDS + ")) AS fans_count",
                                "(SELECT COUNT(*) FROM follow f WHERE f.follower_id = sys_user.id"
                                        + " AND f.followed_id IN (" + SysUserMapper.COMMUNITY_USER_IDS + ")) AS follow_count")
                        .in("id", peerIds)
                        .inSql("id", SysUserMapper.COMMUNITY_USER_IDS))
                .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));
        List<SysUserVo> profiles = peerIds.stream().filter(users::containsKey).map(id -> {
            SysUserVo profile = new SysUserVo();
            BeanUtils.copyProperties(users.get(id), profile);
            return profile;
        }).toList();
        return new PageVo<>(profiles, page.getTotal());
    }

    /**
     * 异步更新用户关注数和粉丝数
     * 
     * @param followerId 关注者ID
     * @param followedId 被关注者ID
     * @param isFollow   true-关注操作，false-取消关注操作
     */
    private void asyncUpdateUserCounts(Integer followerId, Integer followedId, boolean isFollow) {
        notificationThreadPool.getNotificationPool("follow").execute(() -> {
            try {
                if (isFollow) {
                    // 关注操作：关注者的关注数+1，被关注者的粉丝数+1
                    updateFollowCount(followerId, 1);
                    updateFansCount(followedId, 1);
                } else {
                    // 取消关注操作：关注者的关注数-1，被关注者的粉丝数-1
                    updateFollowCount(followerId, -1);
                    updateFansCount(followedId, -1);
                }
            } catch (Exception e) {
                log.error("异步更新用户计数失败：关注者ID={}, 被关注者ID={}, 操作类型={}",
                        followerId, followedId, isFollow ? "关注" : "取消关注", e);
            }
        });
    }

    /**
     * 更新用户关注数
     * 
     * @param userId    用户ID
     * @param increment 增量（正数为增加，负数为减少）
     */
    private void updateFollowCount(Integer userId, int increment) {
        try {
            LambdaUpdateWrapper<SysUser> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(SysUser::getId, userId);
            CountUpdateUtils.incrementCount(updateWrapper, "follow_count", increment);

            int updateResult = sysUserMapper.update(null, updateWrapper);
            if (updateResult == 0) {
                log.warn("更新用户关注数失败，可能用户不存在：userId={}", userId);
            }
        } catch (Exception e) {
            log.error("更新用户关注数异常：userId={}, increment={}", userId, increment, e);
            throw e;
        }
    }

    /**
     * 更新用户粉丝数
     * 
     * @param userId    用户ID
     * @param increment 增量（正数为增加，负数为减少）
     */
    private void updateFansCount(Integer userId, int increment) {
        try {
            LambdaUpdateWrapper<SysUser> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(SysUser::getId, userId);
            CountUpdateUtils.incrementCount(updateWrapper, "fans_count", increment);

            int updateResult = sysUserMapper.update(null, updateWrapper);
            if (updateResult == 0) {
                log.warn("更新用户粉丝数失败，可能用户不存在：userId={}", userId);
            }
        } catch (Exception e) {
            log.error("更新用户粉丝数异常：userId={}, increment={}", userId, increment, e);
            throw e;
        }
    }
}
