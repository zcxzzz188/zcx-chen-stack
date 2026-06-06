package com.zcx.chenstack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 私信 Mapper
 */
@Mapper
public interface PrivateMessageMapper extends BaseMapper<PrivateMessage> {
}
