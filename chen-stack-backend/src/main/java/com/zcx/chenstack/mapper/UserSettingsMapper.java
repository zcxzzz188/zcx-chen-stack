package com.zcx.chenstack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zcx.chenstack.domain.entity.UserSettings;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户个人设置 Mapper 接口
 * </p>
 *
 * @author zcx
 * @since 2026-03-18
 */
@Mapper
public interface UserSettingsMapper extends BaseMapper<UserSettings> {

}
