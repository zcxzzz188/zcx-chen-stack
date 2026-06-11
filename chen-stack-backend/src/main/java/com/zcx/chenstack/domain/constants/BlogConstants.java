package com.zcx.chenstack.domain.constants;

/**
 * @author zcx
 * @since 2025-07-08
 */
public class BlogConstants {

    public static final String NotFoundUser = "该用户不存在";
    public static final String ExistUser = "该用户已存在";
    public static final String UserDisabled = "账号被禁用";
    public static final String CurrentUserCannotDisable = "当前登录账号不能停用自己";
    public static final String CurrentUserCannotDelete = "当前登录账号不能删除自己";
    public static final String SuperAdminCannotDisable = "超级管理员账号不能停用";
    public static final String SuperAdminCannotDelete = "超级管理员账号不能删除";
    public static final String ExistUserName = "用户名已存在";
    public static final String ExistEmail = "邮箱已存在";
    public static final String NotFoundEmail = "邮箱不存在";
    public static final String EmailNotMatch = "邮箱不匹配";
    public static final String CheckCodeError = "验证码错误";
    public static final String LoginRequired = "请先登录";
    public static final String LoginExpired = "登录已过期，请重新登录";
    public static final String NewPasswordSameAsOld = "新密码不能与原密码相同";
    public static final String UserIdRequired = "用户ID不能为空";
    public static final String UpdateUserInfoError = "更新用户信息失败";

    public static final String CannotGetRequestInfo = "无法获取请求信息";
    public static final String IllegalRequest = "非法请求";

    public static final String NotFoundRole = "角色不存在";
    public static final String ExistRole = "角色编码已存在";
    public static final String NotFoundMenu = "菜单不存在";
    public static final String NotFoundPermission = "权限不存在";
    public static final String NotAdminAccount = "不是管理后台账户";
    public static final String ExistRoleMenu = "角色菜单已存在";
    public static final String ExistRolePermission = "角色权限已存在";
    public static final String ExistUserRole = "用户角色已存在";
    public static final String SuperAdminMustBeUnique = "超级管理员必须且只能保留一个账号";
    public static final String BuiltInRoleCannotCreateDuplicate = "系统内置角色不能重复创建";
    public static final String BuiltInRoleCodeCannotModify = "系统内置角色编码不能修改";
    public static final String BuiltInRoleCannotDisable = "系统内置角色不能停用";
    public static final String SuperAdminRoleCannotDisable = "超级管理员角色不能停用";
    public static final String BuiltInRoleCannotDelete = "系统内置角色不能删除";
    public static final String CustomRoleCannotUseBuiltInRoleCode = "普通角色不能使用系统内置角色编码";
    public static final String UserMustHaveSingleRole = "每个用户必须且只能分配一个角色";
    public static final String SuperAdminRoleCannotChangeNormally = "超级管理员角色不能通过普通角色分配修改";
    public static final String SuperAdminMustUseTransfer = "超级管理员请通过专用转让功能变更";
    public static final String AssignRoleFromUserManagement = "请在用户管理中为用户分配角色";
    public static final String AdminAccountUseAdminLogin = "管理员账号请从管理端登录";
    public static final String SystemMenuCannotDisable = "系统管理菜单不能停用";
    public static final String SystemMenuRouteCannotModify = "系统管理菜单的路由和层级不能修改";
    public static final String SystemMenuCannotDelete = "系统管理菜单不能删除";
    public static final String CustomMenuCannotUseSystemPath = "普通菜单不能使用系统管理路径";
    public static final String AdminMustKeepSystemMenus = "超级管理员必须保留完整的系统管理菜单";
    public static final String SystemMenuStructureCannotModify = "系统管理菜单结构只能通过代码或初始化脚本调整";
    public static final String SystemMenusOnlyForAdmin = "系统管理菜单只能分配给超级管理员";
    public static final String BackendMenusOnlyForAdminRoles = "后台菜单只能分配给后台管理角色";
    public static final String UserRoleCannotAssignBackendMenu = "普通用户角色不能分配后台菜单";
    public static final String PermissionDefinitionReadOnly = "权限定义与后端代码绑定，只能通过代码和初始化脚本维护";
    public static final String GenericPermissionAssignmentDisabled = "通用权限分配已关闭，请使用受控的角色权限配置";
    public static final String FixedRoleSystemCannotAdd = "系统角色已固定，不能新增角色";
    public static final String UserCanOnlyUseBuiltInRole = "用户只能分配系统固定角色";

    public static final String NotFoundPhoto = "照片不存在";
    public static final String ExamineStatusError = "审核状态错误";

    public static final String SaveMessageError = "消息保存失败";
    public static final String ReadMessageError = "消息读取失败";
    public static final String DeleteMessageError = "消息删除失败";
    public static final String MarkMessageAsReadError = "标记消息已读失败";

    public static final String AddTagError = "添加标签失败";
    public static final String DeleteTagError = "删除标签失败";
    public static final String UpdateCategorySortError = "更新分类排序失败";
    public static final String CategoryNotFound = "分类不存在";
    public static final String CategoryNameRequired = "分类名称不能为空";
    public static final String SortValueRequired = "排序值不能为空";
    public static final String AddArticleError = "添加文章失败";
    public static final String DeleteArticleError = "删除文章失败";
    public static final String CannotHandleOthersArticle = "不能操作别人的文章";
    public static final String UpdateArticleError = "更新文章失败";
    public static final String NotFoundArticle = "文章不存在";
    public static final String NotFoundTag = "标签不存在";

    public static final String NotFoundColumn = "专栏不存在";
    public static final String CannotHandleOthersColumn = "不能操作别人的专栏";
    public static final String AddColumnError = "添加专栏失败";
    public static final String UpdateColumnError = "更新专栏失败";
    public static final String DeleteColumnError = "删除专栏失败";
    public static final String ExamineColumnError = "审核专栏失败";
    public static final String BatchExamineColumnError = "批量审核专栏失败";
    public static final String BatchDeleteColumnError = "批量删除专栏失败";
    public static final String ColumnIdsEmpty = "专栏ID列表不能为空";
    public static final String ColumnIdEmpty = "专栏ID不能为空";
    public static final String ArticleNotInColumn = "文章不在该专栏中";
    public static final String RemoveArticleFromColumnError = "从专栏中移除文章失败";

    // 评论相关错误信息
    public static final String NotFoundComment = "评论不存在";
    public static final String NotFoundParentComment = "父评论不存在";
    public static final String CannotReplyUnApprovedComment = "无法回复未审核通过的评论";
    public static final String AddCommentError = "评论发表失败";
    public static final String CannotDeleteOthersComment = "无权限删除此评论";
    public static final String DeleteCommentError = "评论删除失败";
    public static final String ArticleIdRequired = "文章ID不能为空";
    public static final String CommentIdRequired = "评论ID不能为空";
    public static final String CommentExamineStatusError = "审核状态错误";
    public static final String CommentAuditError = "审核失败";
    public static final String CommentDeleteError = "评论删除失败";
    public static final String UpdateArticleCommentCountError = "更新文章评论数失败";

    // 点赞相关错误信息
    public static final String LikeTypeError = "点赞类型错误";
    public static final String UpdateArticleLikeCountError = "更新文章点赞数失败";
    public static final String UpdateCommentLikeCountError = "更新评论点赞数失败";

    // 阅读记录相关错误信息
    public static final String UpdateArticleReadCountError = "更新文章阅读数失败";
    public static final String SaveReadRecordError = "保存阅读记录失败";
    public static final String GetUserHistoryError = "获取用户浏览历史失败";
    public static final String ClearUserHistoryError = "清除用户浏览历史失败";
    public static final String GetHotArticleListError = "获取热门文章列表失败";

    // 收藏夹相关错误信息
    public static final String NotFoundFavorite = "收藏夹不存在";
    public static final String CannotHandleOthersFavorite = "不能操作别人的收藏夹";
    public static final String CannotAccessPrivateFavorite = "无权限访问私密收藏夹";
    public static final String AddFavoriteError = "添加收藏夹失败";
    public static final String UpdateFavoriteError = "更新收藏夹失败";
    public static final String FavoriteNameRequired = "收藏夹名称不能为空";
    public static final String AddArticleToFavoriteError = "添加文章到收藏夹失败";
    public static final String FavoriteIdRequired = "收藏夹ID不能为空";
    public static final String ArticleAlreadyInFavorite = "文章已在该收藏夹中";
    public static final String UpdateFavoriteArticleCountError = "更新收藏夹文章数失败";
    public static final String ArticleNotInFavorite = "文章不在该收藏夹中";
    public static final String RemoveArticleFromFavoriteError = "从收藏夹中移除文章失败";
    public static final String UpdateArticleCollectCountError = "更新文章收藏数失败";

    // 关注相关错误信息
    public static final String CannotFollowSelf = "不能关注自己";
    public static final String FollowError = "关注失败";
    public static final String UnfollowError = "取消关注失败";
    public static final String FollowedUserIdRequired = "被关注用户ID不能为空";

    // 限流相关错误信息
    public static final String RateLimitExceeded = "操作繁忙，请稍候再试";

    // 访客日志相关错误信息
    public static final String VisitorLogIdsRequired = "访客日志ID列表不能为空";
    public static final String QueryDaysMustGreaterThanZero = "查询天数必须大于0";
    public static final String QueryDaysCannotExceed365 = "查询天数不能超过365天";

    // 登录日志相关错误信息
    public static final String LoginLogIdsRequired = "登录日志ID列表不能为空";
    public static final String DeleteLoginLogError = "删除登录日志失败";

    // 操作日志相关错误信息
    public static final String OperationlogIdsRequired = "操作日志 ID 列表不能为空";
    public static final String DeleteOperationlogError = "删除操作日志失败";
    public static final String NotFoundOperationlog = "操作日志不存在";

    // AI相关错误信息
    public static final String AiDailyLimitExceeded = "今日AI调用次数已达上限，请明天再试";
    public static final String AiContentTooShort = "文章内容过短（少于100字），无需AI生成摘要";
    public static final String AiContentEmpty = "文章内容不能为空";
    public static final String AiDuplicateRequest = "请勿重复提交相同内容";
    public static final String AiExtractSummaryError = "AI提取摘要失败";

    // 文章访问相关错误信息
    public static final String ArticleFansOnly = "该文章仅粉丝可见";
    public static final String ArticlePrivateOnly = "该文章仅作者本人可见";

    // 私信相关错误信息
    public static final String NotFoundPrivateMessage = "私信不存在";
    public static final String CannotRevokeOthersMessage = "不能撤回别人的消息";
    public static final String MessageRevokeTimeExpired = "消息发送超过2分钟，无法撤回";
    public static final String MessageAlreadyRevoked = "消息已被撤回";
    public static final String NotFoundConversation = "会话不存在";
    public static final String RevokedMessage = "撤回了一条消息";

    // 通用错误信息
    public static final String ParamError = "参数错误";
    public static final String SystemInternalError = "系统内部错误";

    // 系统通知消息模板（评论、点赞、收藏、关注）
    /**
     * 评论通知消息模板
     * 
     * @param nickname     评论者昵称
     * @param articleTitle 文章标题
     */
    public static String CommentNotification(String nickname, String articleTitle) {
        return String.format("%s 评论了你的文章《%s》", nickname, articleTitle);
    }

    /**
     * 回复通知消息模板
     * 
     * @param nickname     回复者昵称
     * @param articleTitle 文章标题
     */
    public static String ReplyNotification(String nickname, String articleTitle) {
        return String.format("%s 回复了你在《%s》中的评论", nickname, articleTitle);
    }

    /**
     * 点赞文章通知消息模板
     * 
     * @param nickname     点赞者昵称
     * @param articleTitle 文章标题
     */
    public static String LikeArticleNotification(String nickname, String articleTitle) {
        return String.format("%s 点赞了你的文章《%s》", nickname, articleTitle);
    }

    /**
     * 点赞评论通知消息模板
     * 
     * @param nickname 点赞者昵称
     */
    public static String LikeCommentNotification(String nickname) {
        return String.format("%s 点赞了你的评论", nickname);
    }

    /**
     * 收藏文章通知消息模板
     * 
     * @param nickname     收藏者昵称
     * @param articleTitle 文章标题
     */
    public static String CollectArticleNotification(String nickname, String articleTitle) {
        return String.format("%s 收藏了你的文章《%s》", nickname, articleTitle);
    }

    /**
     * 关注通知消息模板
     * 
     * @param nickname 关注者昵称
     */
    public static String FollowNotification(String nickname) {
        return String.format("%s 关注了你", nickname);
    }
}
