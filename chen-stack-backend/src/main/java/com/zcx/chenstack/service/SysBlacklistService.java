package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.entity.SysBlacklist;

/**
 * <p>
 * 系统黑名单服务类
 * </p>
 *
 * @author zcx
 * @since 2025-10-02
 */
public interface SysBlacklistService extends IService<SysBlacklist> {

    /**
     * 添加黑名单记录到数据库
     *
     * @param identifier         用户标识（格式：user:123 或 ip:192.168.1.1）
     * @param reason             拉黑原因
     * @param banDurationSeconds 封禁时长（秒）
     */
    void addToBlacklist(String identifier, String reason, long banDurationSeconds);

    /**
     * 更新黑名单记录（升级封禁等级）
     *
     * @param identifier         用户标识（格式：user:123 或 ip:192.168.1.1）
     * @param reason             新的拉黑原因
     * @param banDurationSeconds 新的封禁时长（秒）
     */
    void updateBlacklist(String identifier, String reason, long banDurationSeconds);

}
