package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.entity.Conversation;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.vo.ConversationVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.ConversationMapper;
import com.zcx.chenstack.mapper.PrivateMessageMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.redis.RedisComponent;
import com.zcx.chenstack.service.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话服务实现类
 */
@Service
@Slf4j
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private PrivateMessageMapper privateMessageMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private RedisComponent redisComponent;

    @Override
    public List<ConversationVo> getConversationList(Integer userId) {
        LambdaQueryWrapper<Conversation> qw = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getUpdateTime);

        List<Conversation> conversations = conversationMapper.selectList(qw);

        if (conversations.isEmpty()) {
            return new ArrayList<>();
        }

        // 已审且未读的消息是权威，避免会话计数的增量更新与清零互相覆盖。
        QueryWrapper<PrivateMessage> unreadQuery = new QueryWrapper<PrivateMessage>()
                .select("from_user_id", "COUNT(*) AS unread_count")
                .eq("to_user_id", userId).eq("is_read", 0).eq("is_revoked", 0)
                .and(w -> w.eq("examine_status", ExamineStatusEnum.PASS.getCode()).or().isNull("examine_status"))
                .groupBy("from_user_id");
        Map<Integer, Integer> unreadBySender = privateMessageMapper.selectMaps(unreadQuery).stream()
                .collect(Collectors.toMap(row -> ((Number) row.get("from_user_id")).intValue(),
                        row -> ((Number) row.get("unread_count")).intValue()));

        // 收集所有目标用户ID
        List<Integer> targetUserIds = conversations.stream()
                .map(Conversation::getTargetUserId)
                .distinct()
                .collect(Collectors.toList());

        // 批量查询用户信息（避免 N+1 查询）
        List<SysUser> targetUsers = sysUserMapper.selectBatchIds(targetUserIds);
        Map<Integer, SysUser> userMap = targetUsers.stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user));

        // 批量查询在线状态（避免 N+1 Redis 查询）
        Map<Integer, Boolean> onlineStatusMap = redisComponent.batchGetUserOnlineStatus(targetUserIds);

        // 转换为 VO 并填充数据
        return conversations.stream().map(conv -> {
            ConversationVo vo = BeanUtil.copyProperties(conv, ConversationVo.class);
            vo.setUnreadCount(unreadBySender.getOrDefault(conv.getTargetUserId(), 0));

            // 从 Map 中获取目标用户信息
            SysUser targetUser = userMap.get(conv.getTargetUserId());
            if (targetUser != null) {
                vo.setTargetUserNickname(targetUser.getNickname());
                vo.setTargetUserAvatar(targetUser.getAvatar());
            }

            // 从 Map 中获取在线状态
            vo.setIsOnline(onlineStatusMap.getOrDefault(conv.getTargetUserId(), false));

            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public void updateConversation(Integer fromUserId, Integer toUserId, PrivateMessage message) {
        // 更新发送者的会话
        updateUserConversation(fromUserId, toUserId, message);

        // 更新接收者的会话
        updateUserConversation(toUserId, fromUserId, message);
    }

    private void updateUserConversation(Integer userId, Integer targetUserId, PrivateMessage message) {
        LambdaQueryWrapper<Conversation> qw = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetUserId, targetUserId);

        Conversation conversation = conversationMapper.selectOne(qw);

        if (conversation == null) {
            // 创建新会话
            conversation = new Conversation();
            conversation.setUserId(userId);
            conversation.setTargetUserId(targetUserId);
            conversation.setLastMessageId(message.getId());
            conversation.setLastMessageContent(message.getContent());
            conversation.setLastMessageTime(message.getCreateTime());
            conversation.setUnreadCount(0);
            conversation.setCreateTime(new Date());
            conversation.setUpdateTime(new Date());
            conversationMapper.insert(conversation);
        } else {
            // 更新已有会话
            conversation.setLastMessageId(message.getId());
            conversation.setLastMessageContent(message.getContent());
            conversation.setLastMessageTime(message.getCreateTime());
            conversation.setUpdateTime(new Date());
            conversationMapper.updateById(conversation);
        }
    }

    @Override
    public void clearUnreadCount(Integer userId, Integer targetUserId) {
        privateMessageMapper.update(null, new LambdaUpdateWrapper<PrivateMessage>()
                .eq(PrivateMessage::getFromUserId, targetUserId).eq(PrivateMessage::getToUserId, userId)
                .eq(PrivateMessage::getIsRead, 0).eq(PrivateMessage::getIsRevoked, 0)
                .and(w -> w.eq(PrivateMessage::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                        .or().isNull(PrivateMessage::getExamineStatus))
                .set(PrivateMessage::getIsRead, 1).set(PrivateMessage::getReadTime, new Date()));
        LambdaUpdateWrapper<Conversation> updateWrapper = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetUserId, targetUserId)
                .set(Conversation::getUnreadCount, 0);

        conversationMapper.update(null, updateWrapper);
    }

    @Override
    public void deleteConversation(Integer userId, Integer targetUserId) {
        LambdaQueryWrapper<Conversation> qw = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetUserId, targetUserId);

        Conversation conversation = conversationMapper.selectOne(qw);

        if (conversation == null) {
            throw new BlogException(BlogConstants.NotFoundConversation);
        }

        conversationMapper.deleteById(conversation.getId());
    }

    @Override
    public Integer getUnreadCount(Integer userId, Integer targetUserId) {
        return Math.toIntExact(privateMessageMapper.selectCount(new LambdaQueryWrapper<PrivateMessage>()
                .eq(PrivateMessage::getToUserId, userId).eq(PrivateMessage::getFromUserId, targetUserId)
                .eq(PrivateMessage::getIsRead, 0).eq(PrivateMessage::getIsRevoked, 0)
                .and(w -> w.eq(PrivateMessage::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                        .or().isNull(PrivateMessage::getExamineStatus))));
    }

    @Override
    public List<Integer> getConversationUserIds(Integer userId) {
        LambdaQueryWrapper<Conversation> qw = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .select(Conversation::getTargetUserId);

        List<Conversation> conversations = conversationMapper.selectList(qw);

        return conversations.stream()
                .map(Conversation::getTargetUserId)
                .collect(Collectors.toList());
    }

    @Override
    public void updateLastMessageAfterRevoke(Integer userId, Integer targetUserId, String revokeText) {
        // 更新用户自己的会话
        LambdaUpdateWrapper<Conversation> updateWrapper1 = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetUserId, targetUserId)
                .set(Conversation::getLastMessageContent, revokeText)
                .set(Conversation::getUpdateTime, new Date());

        conversationMapper.update(null, updateWrapper1);

        // 更新对方的会话
        LambdaUpdateWrapper<Conversation> updateWrapper2 = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getUserId, targetUserId)
                .eq(Conversation::getTargetUserId, userId)
                .set(Conversation::getLastMessageContent, revokeText)
                .set(Conversation::getUpdateTime, new Date());

        conversationMapper.update(null, updateWrapper2);
    }
}
