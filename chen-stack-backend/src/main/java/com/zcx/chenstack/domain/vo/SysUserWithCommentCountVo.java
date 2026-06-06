package com.zcx.chenstack.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户信息包含评论数量VO
 * 
 * @author zcx
 * @since 2025-01-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SysUserWithCommentCountVo extends SysUserVo {

  /**
   * 评论数量
   */
  private Integer commentCount;

}
