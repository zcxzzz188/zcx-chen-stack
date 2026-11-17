package com.zcx.chenstack.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.vo.SysUserVo;
import com.zcx.chenstack.exception.BlogException;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import com.zcx.chenstack.domain.entity.SysUser;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
    // 与普通端登录一致：唯一的有效角色为 user。
    String COMMUNITY_USER_IDS = """
            SELECT community_user.id FROM sys_user community_user
            JOIN sys_user_role ur ON ur.user_id = community_user.id
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 0 AND r.is_deleted = 0
            WHERE community_user.status = 0 AND community_user.is_deleted = 0
            GROUP BY community_user.id
            HAVING MIN(r.role) = 'user' AND MAX(r.role) = 'user'
            """;

    static LambdaQueryWrapper<SysUser> communityUsers() {
        return new LambdaQueryWrapper<SysUser>().inSql(SysUser::getId, COMMUNITY_USER_IDS);
    }

    default SysUser requireCommunityUser(Integer userId) {
        SysUser user = userId == null || userId <= 0 ? null
                : selectOne(communityUsers().eq(SysUser::getId, userId));
        if (user == null) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }
        return user;
    }

    default boolean isCommunityUser(Integer userId) {
        return userId != null && userId > 0 && exists(communityUsers().eq(SysUser::getId, userId));
    }

    @Select("""
            SELECT u.id, u.nickname, u.username, u.avatar, u.introduction,
                   COUNT(a.id) AS articleCount,
                   (SELECT COUNT(*) FROM follow f
                    WHERE f.followed_id = u.id AND f.follower_id IN (
            """ + COMMUNITY_USER_IDS + """
                    )) AS fansCount
            FROM sys_user u
            JOIN article a ON a.user_id = u.id AND a.is_deleted = 0
                AND a.edit_status = #{published} AND a.examine_status = #{approved}
                AND a.visible_range = #{publicRange}
            WHERE u.id IN (
            """ + COMMUNITY_USER_IDS + """
            )
            GROUP BY u.id, u.nickname, u.username, u.avatar, u.introduction
            ORDER BY articleCount DESC, fansCount DESC, u.id ASC
            LIMIT #{limit}
            """)
    List<SysUserVo> selectRecommendedCommunityAuthors(@Param("limit") int limit,
            @Param("published") int published, @Param("approved") int approved,
            @Param("publicRange") int publicRange);

}
