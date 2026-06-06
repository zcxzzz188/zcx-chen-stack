package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.PrivateMessageVo;

import java.util.List;

/**
 * 私信服务接口
 */
public interface PrivateMessageService extends IService<PrivateMessage> {

    /**
     * 获取与目标用户的聊天记录（分页）
     */
    PageVo<List<PrivateMessageVo>> getChatHistory(Integer userId, Integer targetUserId, Integer pageNum,
                                                  Integer pageSize);

    /**
     * 标记消息为已读
     */
    void markAsRead(Integer fromUserId, Integer toUserId);

    /**
     * 撤回消息
     */
    PrivateMessage revokeMessage(Integer messageId, Integer userId);

    /**
     * 删除消息
     */
    void deleteMessage(Integer messageId, Integer userId);

    /**
     * 获取未读消息数
     */
    Integer getUnreadCount(Integer userId);
}
