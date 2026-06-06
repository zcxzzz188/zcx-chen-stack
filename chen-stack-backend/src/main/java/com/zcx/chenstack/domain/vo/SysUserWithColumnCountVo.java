package com.zcx.chenstack.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户信息包含专栏数量VO
 * 
 * @author zcx
 * @since 2025-09-23
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SysUserWithColumnCountVo extends SysUserVo {

  /**
   * 专栏数量
   */
  private Integer columnCount;

}
