package com.zcx.chenstack.service.impl;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.RabbitMQConstants;
import com.zcx.chenstack.domain.dto.MessageDto;
import com.zcx.chenstack.domain.entity.SysBlacklist;
import com.zcx.chenstack.domain.enums.BlacklistTypeEnum;
import com.zcx.chenstack.domain.enums.IsReadStatusEnum;
import com.zcx.chenstack.domain.enums.MessageTypeEnum;
import com.zcx.chenstack.mapper.SysBlacklistMapper;
import com.zcx.chenstack.service.MessageService;
import com.zcx.chenstack.service.SysBlacklistService;
import com.zcx.chenstack.utils.IpUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * 系统黑名单服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-10-02
 */
@Service
@Slf4j
public class SysBlacklistServiceImpl extends ServiceImpl<SysBlacklistMapper, SysBlacklist>
        implements SysBlacklistService {

    @Resource
    private MessageService messageService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IpUtils ipUtils;

    @Override
    public void addToBlacklist(String identifier, String reason, long banDurationSeconds) {
        try {
            SysBlacklist blacklist = new SysBlacklist();
            String userId = "未知";
            String ip = "未知";

            // 解析用户标识，判断是用户类型还是IP类型
            if (identifier.startsWith("user:")) {
                // 用户类型
                blacklist.setType(BlacklistTypeEnum.USER.getCode());
                String userIdStr = identifier.substring(5); // 去掉 "user:" 前缀
                blacklist.setUserId(Integer.parseInt(userIdStr));
                userId = userIdStr;
            } else if (identifier.startsWith("ip:")) {
                // IP类型
                blacklist.setType(BlacklistTypeEnum.IP.getCode());
                String ipStr = identifier.substring(3); // 去掉 "ip:" 前缀
                blacklist.setIp(ipStr);
                ip = ipStr;
            } else {
                log.error("无效的用户标识格式: {}", identifier);
                return;
            }

            // 设置拉黑信息
            blacklist.setReason(reason);
            Date now = new Date();
            blacklist.setBanTime(now);
            blacklist.setExpireTime(new Date(now.getTime() + banDurationSeconds * 1000));

            // 保存到数据库
            this.save(blacklist);

            // 获取IP地址信息
            String address = "未知地址";
            if (!ip.equals("未知")) {
                try {
                    address = ipUtils.getAddress(ip);
                } catch (Exception e) {
                    log.error("获取IP地址信息失败: {}", ip, e);
                }
            }

            // 格式化封禁时长
            String banDuration = formatBanDuration(banDurationSeconds);

            // 格式化拉黑时间
            String banTime = DateUtil.format(now, "yyyy-MM-dd HH:mm:ss");

            // 发送站内消息给管理员
            try {
                MessageDto messageDto = new MessageDto();
                messageDto.setType(MessageTypeEnum.SYSTEM.getCode()); // 系统消息
                messageDto.setIsRead(IsReadStatusEnum.UNREAD.getCode()); // 未读
                messageDto.setContent(String.format(
                        "黑名单拉黑通知：用户ID为 %s，IP为 %s，地址为 %s 的用户因 %s 被拉入黑名单，封禁时长：%s",
                        userId, ip, address, reason, banDuration));
                messageService.sendToAdmin(messageDto);
            } catch (Exception e) {
                log.error("发送站内消息给管理员失败", e);
            }

            // 发送 RabbitMQ 消息（用于邮件通知）
            try {
                Map<String, Object> emailMessage = new HashMap<>();
                emailMessage.put("userId", userId);
                emailMessage.put("ip", ip);
                emailMessage.put("address", address);
                emailMessage.put("reason", reason);
                emailMessage.put("banDuration", banDuration);
                emailMessage.put("banTime", banTime);

                rabbitTemplate.convertAndSend(
                        RabbitMQConstants.Blacklist_Exchange,
                        RabbitMQConstants.Blacklist_Routing_Key,
                        emailMessage);
            } catch (Exception e) {
                log.error("发送 RabbitMQ 消息失败", e);
            }

        } catch (Exception e) {
            log.error("保存黑名单记录失败 - 标识: {}, 原因: {}", identifier, reason, e);
        }
    }

    @Override
    public void updateBlacklist(String identifier, String reason, long banDurationSeconds) {
        try {
            // 解析用户标识
            Integer userId = null;
            String ip = null;
            Integer type = null;

            if (identifier.startsWith("user:")) {
                type = BlacklistTypeEnum.USER.getCode();
                userId = Integer.parseInt(identifier.substring(5));
            } else if (identifier.startsWith("ip:")) {
                type = BlacklistTypeEnum.IP.getCode();
                ip = identifier.substring(3);
            } else {
                log.error("无效的用户标识格式: {}", identifier);
                return;
            }

            // 查询现有的黑名单记录
            LambdaQueryWrapper<SysBlacklist> queryWrapper = new LambdaQueryWrapper<SysBlacklist>()
                    .eq(SysBlacklist::getType, type)
                    .orderByDesc(SysBlacklist::getCreateTime)
                    .last("LIMIT 1");

            if (userId != null) {
                queryWrapper.eq(SysBlacklist::getUserId, userId);
            } else {
                queryWrapper.eq(SysBlacklist::getIp, ip);
            }

            SysBlacklist blacklist = this.getOne(queryWrapper);

            if (blacklist == null) {
                // 如果没有找到记录，则创建新记录
                addToBlacklist(identifier, reason, banDurationSeconds);
                return;
            }

            // 更新黑名单记录
            Date now = new Date();
            blacklist.setReason(reason);
            blacklist.setBanTime(now);
            blacklist.setExpireTime(new Date(now.getTime() + banDurationSeconds * 1000));

            boolean result = this.updateById(blacklist);
            if (!result) {
                log.error("更新黑名单记录失败 - 标识: {}", identifier);
            } else {
                
            }

        } catch (Exception e) {
            log.error("更新黑名单记录失败 - 标识: {}, 原因: {}", identifier, reason, e);
        }
    }

    /**
     * 格式化封禁时长
     * 
     * @param banDurationSeconds 封禁时长（秒）
     * @return 格式化后的时长字符串
     */
    private String formatBanDuration(long banDurationSeconds) {
        if (banDurationSeconds < 60) {
            return banDurationSeconds + "秒";
        } else if (banDurationSeconds < 3600) {
            return (banDurationSeconds / 60) + "分钟";
        } else if (banDurationSeconds < 86400) {
            return (banDurationSeconds / 3600) + "小时";
        } else {
            return (banDurationSeconds / 86400) + "天";
        }
    }

}
