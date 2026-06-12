package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.constants.RedisConstants;
import com.zcx.chenstack.domain.constants.RabbitMQConstants;
import com.zcx.chenstack.domain.dto.*;
import com.zcx.chenstack.domain.entity.*;
import com.zcx.chenstack.domain.enums.*;
import com.zcx.chenstack.domain.vo.*;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.*;
import com.zcx.chenstack.redis.RedisComponent;
import com.zcx.chenstack.redis.NotificationThreadPool;
import com.zcx.chenstack.security.SysUserDetailsService;
import com.zcx.chenstack.service.IpService;
import com.zcx.chenstack.service.SysUserRoleService;
import com.zcx.chenstack.service.SysUserService;
import com.zcx.chenstack.service.UserSettingsService;
import com.zcx.chenstack.utils.IpUtils;
import com.zcx.chenstack.utils.JwtUtils;
import com.zcx.chenstack.utils.SecurityUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
@Service
@Slf4j
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Resource
    private AuthenticationManager authenticationManager;

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private RabbitTemplate rabbitTemplate;

    // Spring Security的密码加密器
    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private PhotoMapper photoMapper;

    @Resource
    private SysUserDetailsService sysUserDetailsService;

    @Resource
    private IpUtils ipUtils;

    @Resource
    private IpService ipService;

    @Resource
    private SysUserRoleService sysUserRoleService;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private CommentMapper commentMapper;

    @Resource
    private ColumnMapper columnMapper;
    @Resource
    private ArticleColumnMapper articleColumnMapper;
    @Resource
    private LikeMapper likeMapper;
    @Resource
    private FavoriteMapper favoriteMapper;
    @Resource
    private ArticleFavoriteMapper articleFavoriteMapper;
    @Resource
    private HistoryMapper historyMapper;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private MessageMapper messageMapper;
    @Resource
    private PrivateMessageMapper privateMessageMapper;
    @Resource
    private ConversationMapper conversationMapper;
    @Resource
    private SysUserRoleMapper sysUserRoleMapper;
    @Resource
    private SysRoleMapper sysRoleMapper;
    @Resource
    private UserSettingsMapper userSettingsMapper;

    @Resource
    private com.zcx.chenstack.service.SysLoginLogService sysLoginLogService;

    @Resource
    private UserSettingsService userSettingsService;

    @Resource
    private NotificationThreadPool notificationThreadPool;
    @Resource
    @Lazy
    private ArticleServiceImpl articleServiceImpl;
    @Resource
    private ColumnServiceImpl columnServiceImpl;
    @Resource
    private PhotoServiceImpl photoServiceImpl;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String login(LoginDto loginDto) {
        try {
            // 校验验证码（时间安全比较，忽略大小写）
            String storedCheckCode = redisComponent.getCheckCode(loginDto.getCheckCodeKey());
            String inputCodeLower = loginDto.getCheckCode().toLowerCase();
            String storedCodeLower = storedCheckCode != null ? storedCheckCode.toLowerCase() : null;
            if (storedCodeLower == null || !MessageDigest.isEqual(
                    inputCodeLower.getBytes(), storedCodeLower.getBytes())) {
                throw new BlogException(BlogConstants.CheckCodeError); // 验证码错误
            }
            // 去除用户名首尾空格后再认证
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    loginDto.getUsername().trim(), loginDto.getPassword());
            // 调用loadUserByUsername方法
            Authentication authenticate = authenticationManager.authenticate(authentication);
            // 获取用户信息，返回的就是UserDetails
            LoginUser loginUser = (LoginUser) authenticate.getPrincipal();
            if (!isUserAccount(loginUser.getSysUser())) {
                throw new BlogException(BlogConstants.AdminAccountUseAdminLogin);
            }
            recordLoginSuccess(loginUser.getSysUser(), RegisterOrLoginTypeEnum.EMAIL.getCode());
            // 创建token,此处的token时由UUID编码而成JWT字符串
            String token = jwtUtils.createToken(loginUser.getSysUser().getId(), loginDto.getRememberMe());
            return token;
        } catch (Exception e) {
            // 记录登录失败日志
            String ip = ipUtils.getIp();
            sysLoginLogService.recordLoginLog(
                    null, // 登录失败时可能无法获取用户ID
                    loginDto.getUsername(),
                    RegisterOrLoginTypeEnum.EMAIL.getCode(), // 0-用户名/邮箱登录
                    ip,
                    LoginStatusEnum.FAIL.getCode() // 1-失败
            );
            throw e; // 重新抛出异常，让全局异常处理器处理
        } finally {
            redisComponent.cleanCheckCode(loginDto.getCheckCodeKey());
        }

    }

    @Override
    public String register(RegisterDto registerDto) {
        // 必须校验验证码，不能为空
        if (ObjectUtil.isEmpty(registerDto.getEmailCheckCode())) {
            throw new BlogException(BlogConstants.CheckCodeError);
        }

        // 校验验证码是否正确（使用时间安全比较防止时序攻击）
        String storedEmailCode = redisComponent.getEmailCheckCode(registerDto.getEmail(), MailEnum.REGISTER.getType());
        if (!MessageDigest.isEqual(
                registerDto.getEmailCheckCode().getBytes(),
                storedEmailCode != null ? storedEmailCode.getBytes() : new byte[0])) {
            throw new BlogException(BlogConstants.CheckCodeError);
        }

        // 分别检查用户名和邮箱是否存在，避免or条件导致的逻辑错误
        LambdaQueryWrapper<SysUser> usernameQuery = new LambdaQueryWrapper<>();
        usernameQuery.eq(SysUser::getUsername, registerDto.getUsername());
        SysUser existUsername = sysUserMapper.selectOne(usernameQuery);
        if (ObjectUtil.isNotNull(existUsername)) {
            throw new BlogException(BlogConstants.ExistUserName);// 用户名已存在
        }

        LambdaQueryWrapper<SysUser> emailQuery = new LambdaQueryWrapper<>();
        emailQuery.eq(SysUser::getEmail, registerDto.getEmail());
        SysUser existEmail = sysUserMapper.selectOne(emailQuery);
        if (ObjectUtil.isNotNull(existEmail)) {
            throw new BlogException(BlogConstants.ExistEmail);// 邮箱已存在
        }

        // 注册用户
        SysUser user = new SysUser();
        user.setUsername(registerDto.getUsername());
        user.setNickname(registerDto.getUsername());
        user.setPassword(passwordEncoder.encode(registerDto.getPassword()));
        user.setEmail(registerDto.getEmail());
        user.setRegisterIp(ipUtils.getIp());
        user.setRegisterAddress(ipUtils.getAddress());
        user.setStatus(StatusEnum.NORMAL.getStatus());
        String ip = ipUtils.getIp();

        int insert = sysUserMapper.insert(user);
        if (insert != 1 || user.getId() == null) {
            throw new BlogException(BlogConstants.SystemInternalError);
        }

        Integer userId = user.getId();

        // 异步创建用户设置
        notificationThreadPool.getNotificationPool("user").execute(() -> {
            try {
                userSettingsService.createDefaultSettings(userId);
            } catch (Exception e) {
                log.error("异步创建用户设置失败，userId={}", userId, e);
            }
        });

        ipService.setRegisterIp(userId, ip);
        sysUserRoleService.setRegisterRole(userId);
        recordLoginSuccess(user, RegisterOrLoginTypeEnum.EMAIL.getCode());
        redisComponent.cleanEmailCheckCode(registerDto.getEmail(), MailEnum.REGISTER.getType());
        return jwtUtils.createToken(userId, false);
    }

    @Override
    public void sendEmailCheckCode(EmailDto emailDto) {
        String email = emailDto.getEmail();
        // 如果是重置密码, 则需要先校验邮箱是否存在
        if (emailDto.getType().equals(MailEnum.RESET_PASSWORD.getType())
                || emailDto.getType().equals(MailEnum.RESET_EMAIL.getType())) {
            SysUser sysUser = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email));
            if (ObjectUtil.isNull(sysUser)) {
                throw new BlogException(BlogConstants.NotFoundEmail);// 邮箱不存在
            }
        }
        // 生成六位数的验证码
        String checkCode = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        // 将验证码存入redis
        redisComponent.saveEmailCheckCode(email, emailDto.getType(), checkCode);
        // 发送邮件
        HashMap<String, Object> sendEmail = new HashMap<>();
        sendEmail.put("email", email);
        sendEmail.put("checkCode", checkCode);
        sendEmail.put("type", emailDto.getType());
        rabbitTemplate.convertAndSend(RabbitMQConstants.Email_Exchange, RabbitMQConstants.Email_Routing_Key, sendEmail);
    }

    @Override
    public void verifyResetPassword(VerifyResetDto verifyResetDto) {
        if (!verifyResetDto.getEmailCheckCode()
                .equals(redisComponent.getEmailCheckCode(verifyResetDto.getEmail(),
                        MailEnum.RESET_PASSWORD.getType()))) {
            throw new BlogException(BlogConstants.CheckCodeError); // 验证码错误
        }
    }

    @Override
    public void resetPassword(ResetPasswordDto resetPasswordDto) {
        // 校验邮箱验证码
        if (!resetPasswordDto.getEmailCheckCode()
                .equals(redisComponent.getEmailCheckCode(resetPasswordDto.getEmail(),
                        MailEnum.RESET_PASSWORD.getType()))) {
            throw new BlogException(BlogConstants.CheckCodeError);
        }

        // 查询用户
        SysUser sysUser = sysUserMapper
                .selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, resetPasswordDto.getEmail()));
        if (ObjectUtil.isNull(sysUser)) {
            throw new BlogException(BlogConstants.NotFoundEmail); // 邮箱不存在
        }

        // 检查新密码是否与原密码相同
        if (passwordEncoder.matches(resetPasswordDto.getPassword(), sysUser.getPassword())) {
            throw new BlogException(BlogConstants.NewPasswordSameAsOld); // 新密码不能与原密码相同
        }

        // 更新密码
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail,
                resetPasswordDto.getEmail());
        SysUser newSysUser = new SysUser().setPassword(passwordEncoder.encode(resetPasswordDto.getPassword()));
        sysUserMapper.update(newSysUser, queryWrapper);

        // 清除验证码
        redisComponent.cleanEmailCheckCode(resetPasswordDto.getEmail(), MailEnum.RESET_PASSWORD.getType());
    }

    @Override
    public void verifyResetEmail(VerifyEmailDto verifyEmailDto) {
        // 校验原邮箱的验证码
        if (!verifyEmailDto.getEmailCheckCode()
                .equals(redisComponent.getEmailCheckCode(verifyEmailDto.getEmail(), MailEnum.RESET_EMAIL.getType()))) {
            throw new BlogException(BlogConstants.CheckCodeError);
        }
    }

    @Override
    public void resetEmail(UpdateEmailDto updateEmailDto) {
        // 获取当前登录用户ID
        Integer userId = SecurityUtils.getUserId();

        // 查询当前用户
        SysUser currentUser = sysUserMapper.selectById(userId);
        if (currentUser == null) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }

        // 校验原邮箱是否是当前用户的邮箱
        if (!currentUser.getEmail().equals(updateEmailDto.getEmail())) {
            throw new BlogException(BlogConstants.EmailNotMatch);
        }

        // 校验原邮箱的验证码
        if (!updateEmailDto.getEmailCheckCode()
                .equals(redisComponent.getEmailCheckCode(updateEmailDto.getEmail(), MailEnum.RESET_EMAIL.getType()))) {
            throw new BlogException(BlogConstants.CheckCodeError);
        }

        // 检查新邮箱是否已被其他用户使用
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getEmail, updateEmailDto.getNewEmail());
        SysUser existUser = sysUserMapper.selectOne(queryWrapper);
        if (existUser != null) {
            throw new BlogException(BlogConstants.ExistEmail);
        }

        // 更新为新邮箱
        LambdaUpdateWrapper<SysUser> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SysUser::getId, userId)
                .set(SysUser::getEmail, updateEmailDto.getNewEmail());

        int result = sysUserMapper.update(null, updateWrapper);
        if (result != 1) {
            throw new BlogException(BlogConstants.UpdateUserInfoError);
        }

        // 清除原邮箱的验证码
        redisComponent.cleanEmailCheckCode(updateEmailDto.getEmail(), MailEnum.RESET_EMAIL.getType());
    }

    @Override
    public SysUserVo info() {
        Integer userId = SecurityUtils.getUserId();
        SysUser sysUser = sysUserMapper.selectById(userId);
        if (sysUser == null) {
            throw new BlogException(BlogConstants.NotFoundUser); // 用户不存在
        }
        SysUserVo sysUserVo = new SysUserVo();
        sysUserVo.setId(sysUser.getId());
        sysUserVo.setNickname(sysUser.getNickname());
        sysUserVo.setUsername(sysUser.getUsername());
        sysUserVo.setEmail(sysUser.getEmail());
        sysUserVo.setIntroduction(sysUser.getIntroduction());
        sysUserVo.setAvatar(sysUser.getAvatar());
        sysUserVo.setSex(sysUser.getSex());
        sysUserVo.setFansCount(sysUser.getFansCount());
        sysUserVo.setFollowCount(sysUser.getFollowCount());

        Photo latestAvatarPhoto = photoMapper.selectOne(new LambdaQueryWrapper<Photo>()
                .select(Photo::getUrl, Photo::getExamineStatus)
                .eq(Photo::getUserId, userId)
                .like(Photo::getUrl, "/user/avatar/")
                .orderByDesc(Photo::getId)
                .last("LIMIT 1"));
        if (latestAvatarPhoto != null && ExamineStatusEnum.WAIT.getCode().equals(latestAvatarPhoto.getExamineStatus())) {
            sysUserVo.setPendingAvatarUrl(latestAvatarPhoto.getUrl());
            sysUserVo.setPendingAvatarStatus(latestAvatarPhoto.getExamineStatus());
        }

        // 从 user_settings 表读取邮件通知设置
        sysUserVo.setIsReceivePrivateMessageEmail(userSettingsService.getReceivePrivateMessageEmail(userId));
        sysUserVo.setIsReceiveCommentEmail(userSettingsService.getReceiveCommentEmail(userId));
        sysUserVo.setIsReceiveSystemEmail(userSettingsService.getReceiveSystemEmail(userId));
        return sysUserVo;
    }

    @Override
    public SysUserVo getUserInfoById(Integer userId) {
        SysUser sysUser = sysUserMapper.selectById(userId);
        if (sysUser == null) {
            throw new BlogException(BlogConstants.NotFoundUser); // 用户不存在
        }
        SysUserVo sysUserVo = new SysUserVo();
        sysUserVo.setId(sysUser.getId());
        sysUserVo.setNickname(sysUser.getNickname());
        sysUserVo.setUsername(sysUser.getUsername());
        sysUserVo.setIntroduction(sysUser.getIntroduction());
        sysUserVo.setAvatar(sysUser.getAvatar());
        sysUserVo.setSex(sysUser.getSex());
        sysUserVo.setCreateTime(sysUser.getCreateTime());
        String loginAddress = processLoginAddress(sysUser.getLoginAddress());
        sysUserVo.setLoginAddress(loginAddress);
        sysUserVo.setFansCount(sysUser.getFansCount());
        sysUserVo.setFollowCount(sysUser.getFollowCount());

        // 实时查询已发布且审核通过的文章数量
        LambdaQueryWrapper<Article> articleQuery = new LambdaQueryWrapper<>();
        articleQuery.eq(Article::getUserId, userId)
                .eq(Article::getEditStatus, EditStatusEnum.PUBLISHED.getCode()) // 已发布
                .eq(Article::getExamineStatus, ExamineStatusEnum.PASS.getCode()); // 审核通过
        Integer articleCount = Math.toIntExact(articleMapper.selectCount(articleQuery));
        sysUserVo.setArticleCount(articleCount);
        return sysUserVo;
    }

    @Override
    public void updateUserInfo(UpdateUserInfoDto updateUserInfoDto) {
        // 获取当前登录用户ID
        Integer userId = SecurityUtils.getUserId();

        // 查询当前用户
        SysUser currentUser = sysUserMapper.selectById(userId);
        if (currentUser == null) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }

        LambdaUpdateWrapper<SysUser> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SysUser::getId, userId)
                .set(ObjectUtil.isNotEmpty(updateUserInfoDto.getNickname()), SysUser::getNickname,
                        updateUserInfoDto.getNickname())
                .set(ObjectUtil.isNotNull(updateUserInfoDto.getSex()), SysUser::getSex, updateUserInfoDto.getSex())
                .set(SysUser::getIntroduction, updateUserInfoDto.getIntroduction())
                .set(ObjectUtil.isNotEmpty(updateUserInfoDto.getAvatar()), SysUser::getAvatar,
                        updateUserInfoDto.getAvatar());

        int result = sysUserMapper.update(null, updateWrapper);
        if (result != 1) {
            throw new BlogException(BlogConstants.UpdateUserInfoError);
        }

        // 更新私信邮件通知设置到 user_settings 表
        if (ObjectUtil.isNotNull(updateUserInfoDto.getIsReceivePrivateMessageEmail())) {
            userSettingsService.setReceivePrivateMessageEmail(userId, updateUserInfoDto.getIsReceivePrivateMessageEmail());
        }

    }

    /**
     * 处理登录地址显示
     * 如果不是中国开头的，截取第一个-之前的内容
     * 如果是中国开头的，截取第二段文字（江西、上海等省份）
     *
     * @param loginAddress 原始登录地址，格式如：中国-广东-广州 或 美国-纽约
     * @return 处理后的地址
     */
    private String processLoginAddress(String loginAddress) {
        if (ObjectUtil.isEmpty(loginAddress)) {
            return loginAddress;
        }

        // 按-分割地址
        String[] addressParts = loginAddress.split("-");

        if (addressParts.length == 0) {
            return loginAddress;
        }

        // 如果是中国开头，返回第二段（省份）
        if ("中国".equals(addressParts[0]) && addressParts.length > 1) {
            return addressParts[1];
        }

        // 否则返回第一段（国家）
        return addressParts[0];
    }

    // 管理员登录
    @Override
    public String adminLogin(AdminLoginDto adminLoginDto) {
        // 验证图形验证码（时间安全比较，忽略大小写）
        String cachedCode = redisComponent.getCheckCode(adminLoginDto.getCheckCodeKey());
        String inputCodeLower = adminLoginDto.getCheckCode().toLowerCase();
        String cachedCodeLower = cachedCode != null ? cachedCode.toLowerCase() : null;
        if (cachedCodeLower == null || !MessageDigest.isEqual(
                inputCodeLower.getBytes(), cachedCodeLower.getBytes())) {
            throw new BlogException(BlogConstants.CheckCodeError); // 验证码错误
        }
        // 验证成功后清除验证码
        redisComponent.cleanCheckCode(adminLoginDto.getCheckCodeKey());

        try {
            // 去除用户名首尾空格后再认证
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    adminLoginDto.getUsername().trim(), adminLoginDto.getPassword());
            // 调用loadUserByUsername方法
            Authentication authenticate = authenticationManager.authenticate(authentication);
            // 获取用户信息
            LoginUser loginUser = (LoginUser) authenticate.getPrincipal();
            if (loginUser.getSysUser() == null) {
                throw new BlogException(BlogConstants.NotFoundUser);
            }
            if (!isAdminAccount(loginUser.getSysUser())) {
                throw new BlogException(BlogConstants.NotAdminAccount); // 不是管理后台账户
            }
            recordLoginSuccess(loginUser.getSysUser(), RegisterOrLoginTypeEnum.EMAIL.getCode());
            // 创建token,此处的token时由UUID编码而成JWT字符串
            String token = jwtUtils.createToken(loginUser.getSysUser().getId(), adminLoginDto.getRememberMe());
            return token;
        } catch (Exception e) {
            // 记录管理员登录失败日志
            String ip = ipUtils.getIp();
            sysLoginLogService.recordLoginLog(
                    null, // 登录失败时可能无法获取用户ID
                    adminLoginDto.getUsername(),
                    RegisterOrLoginTypeEnum.EMAIL.getCode(), // 0-用户名/邮箱登录
                    ip,
                    LoginStatusEnum.FAIL.getCode() // 1-失败
            );
            throw e; // 重新抛出异常，让全局异常处理器处理
        }
    }

    /**
     * 登录成功后统一补齐最后登录信息和成功日志
     *
     * @param sysUser    登录用户
     * @param loginType  登录方式
     */
    private void recordLoginSuccess(SysUser sysUser, Integer loginType) {
        if (sysUser == null || sysUser.getId() == null) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }
        String ip = ipUtils.getIp();
        String loginAddress = ipUtils.getAddress();
        SysUser updateUser = new SysUser()
                .setId(sysUser.getId())
                .setLoginType(loginType)
                .setLoginIp(ip)
                .setLoginAddress(loginAddress)
                .setLoginTime(new Date());
        int result = sysUserMapper.updateById(updateUser);
        if (result == 0) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }
        ipService.setLoginIp(sysUser.getId(), ip);
        sysLoginLogService.recordLoginLog(
                sysUser.getId(),
                sysUser.getUsername(),
                loginType,
                ip,
                LoginStatusEnum.SUCCESS.getCode()
        );
    }

    // 管理端获取用户信息
    @Override
    public SysUserVo getAdminInfo() {
        // 获取用户信息
        SysUser user = SecurityUtils.getUser();
        if (ObjectUtil.isEmpty(user)) {
            return null;
        }

        List<SysRole> sysRoles = user.getSysRoles();

        if (sysRoles.stream().noneMatch(r -> r.getRole().equals("admin") || r.getRole().equals("content_admin"))) {
            throw new BlogException(BlogConstants.NotAdminAccount); // 不是管理员账户
        }

        SysUserVo sysUserVo = BeanUtil.copyProperties(user, SysUserVo.class);
        return sysUserVo;
    }

    // 管理端获取用户列表
    @Override
    public List<SysUserVo> listUser() {
        List<SysUser> sysUsers = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId));
        List<SysUserVo> sysUserVos = BeanUtil.copyToList(sysUsers, SysUserVo.class);
        maskEmailsForNonAdmin(sysUserVos);
        return sysUserVos;
    }

    @Override
    public PageVo<List<SysUserVo>> pageUser(Integer pageNum, Integer pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId);
        Page<SysUser> userPage = sysUserMapper.selectPage(page, queryWrapper);
        List<SysUserVo> sysUserVos = BeanUtil.copyToList(userPage.getRecords(), SysUserVo.class);
        fillUserRoleCodes(sysUserVos);
        maskEmailsForNonAdmin(sysUserVos);
        return new PageVo<>(sysUserVos, userPage.getTotal());
    }

    // 管理端获取用户列表（包含文章数量）
    @Override
    public List<SysUserWithArticleCountVo> listUserWithArticleCount() {
        List<SysUser> sysUsers = sysUserMapper.selectList(null);
        List<SysUserWithArticleCountVo> sysUserVos = BeanUtil.copyToList(sysUsers, SysUserWithArticleCountVo.class);

        // 为每个用户设置文章数量
        for (SysUserWithArticleCountVo userVo : sysUserVos) {
            LambdaQueryWrapper<Article> articleQuery = new LambdaQueryWrapper<Article>()
                    .eq(Article::getUserId, userVo.getId());
            Integer articleCount = Math.toIntExact(articleMapper.selectCount(articleQuery));
            userVo.setArticleCount(articleCount);
        }

        return sysUserVos;
    }

    // 管理端获取用户列表（包含评论数量）
    @Override
    public List<SysUserWithCommentCountVo> listUserWithCommentCount() {
        List<SysUser> sysUsers = sysUserMapper.selectList(null);
        List<SysUserWithCommentCountVo> sysUserVos = BeanUtil.copyToList(sysUsers, SysUserWithCommentCountVo.class);

        // 为每个用户设置评论数量
        for (SysUserWithCommentCountVo userVo : sysUserVos) {
            LambdaQueryWrapper<Comment> commentQuery = new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getUserId, userVo.getId());
            Integer commentCount = Math.toIntExact(commentMapper.selectCount(commentQuery));
            userVo.setCommentCount(commentCount);
        }

        return sysUserVos;
    }

    @Override
    public List<SysUserWithColumnCountVo> listUserWithColumnCount() {
        List<SysUser> sysUsers = sysUserMapper.selectList(null);
        List<SysUserWithColumnCountVo> sysUserVos = BeanUtil.copyToList(sysUsers, SysUserWithColumnCountVo.class);

        // 为每个用户设置专栏数量
        for (SysUserWithColumnCountVo userVo : sysUserVos) {
            LambdaQueryWrapper<Column> columnQuery = new LambdaQueryWrapper<Column>()
                    .eq(Column::getUserId, userVo.getId());
            Integer columnCount = Math.toIntExact(columnMapper.selectCount(columnQuery));
            userVo.setColumnCount(columnCount);
        }

        return sysUserVos;
    }

    // 管理端更新用户信息
    @Override
    public void updateUser(SysUserDto sysUserDto) {
        SysUser originalUser = sysUserMapper.selectById(sysUserDto.getId());
        if (originalUser == null) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }

        if (isDisablingUser(sysUserDto, originalUser)) {
            Integer currentUserId = SecurityUtils.getUserId();
            if (Objects.equals(currentUserId, originalUser.getId())) {
                throw new BlogException(BlogConstants.CurrentUserCannotDisable);
            }
            if (isAdminUser(originalUser.getId())) {
                throw new BlogException(BlogConstants.SuperAdminCannotDisable);
            }
        }

        SysUser sysUser = BeanUtil.copyProperties(sysUserDto, SysUser.class);
        sysUserMapper.updateById(sysUser);
    }

    // 管理端删除用户
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Integer id) {
        try {
            log.info("管理端删除用户开始，userId: {}", id);
            log.info("管理端删除用户-校验用户存在，userId: {}", id);
            SysUser sysUser = sysUserMapper.selectById(id);
            if (sysUser == null) {
                throw new BlogException(BlogConstants.NotFoundUser);
            }
            log.info("管理端删除用户-用户存在校验通过，userId: {}", id);

            Integer currentUserId = SecurityUtils.getUserId();
            if (Objects.equals(currentUserId, id)) {
                throw new BlogException(BlogConstants.CurrentUserCannotDelete);
            }
            if (isAdminUser(id)) {
                throw new BlogException(BlogConstants.SuperAdminCannotDelete);
            }

            log.info("管理端删除用户-收集非文章图片开始，userId: {}", id);
            Set<String> nonArticlePhotoUrls = collectUserNonArticlePhotoUrls(sysUser, id);
            log.info("管理端删除用户-收集非文章图片完成，userId: {}, photoUrlCount: {}", id, nonArticlePhotoUrls.size());

            log.info("管理端删除用户-收集相关会话用户开始，userId: {}", id);
            Set<Integer> relatedConversationUserIds = collectRelatedConversationUserIds(id);
            log.info("管理端删除用户-收集相关会话用户完成，userId: {}, relatedUserCount: {}", id,
                    relatedConversationUserIds.size());

            log.info("管理端删除用户-清理用户文章开始，userId: {}", id);
            articleServiceImpl.cleanupAndDeleteArticlesForUser(id);
            log.info("管理端删除用户-清理用户文章完成，userId: {}", id);

            log.info("管理端删除用户-清理用户专栏开始，userId: {}", id);
            nonArticlePhotoUrls.addAll(columnServiceImpl.cleanupAndDeleteColumnsForUser(id));
            log.info("管理端删除用户-清理用户专栏完成，userId: {}, photoUrlCount: {}", id, nonArticlePhotoUrls.size());

            log.info("管理端删除用户-清理用户评论开始，userId: {}", id);
            cleanupUserComments(id);
            log.info("管理端删除用户-清理用户评论完成，userId: {}", id);

            log.info("管理端删除用户-清理点赞开始，userId: {}", id);
            likeMapper.delete(new LambdaQueryWrapper<Like>().eq(Like::getUserId, id));
            log.info("管理端删除用户-清理点赞完成，userId: {}", id);

            log.info("管理端删除用户-清理收藏夹开始，userId: {}", id);
            cleanupUserFavorites(id);
            log.info("管理端删除用户-清理收藏夹完成，userId: {}", id);

            log.info("管理端删除用户-清理历史记录开始，userId: {}", id);
            historyMapper.delete(new LambdaQueryWrapper<History>().eq(History::getUserId, id));
            log.info("管理端删除用户-清理历史记录完成，userId: {}", id);

            log.info("管理端删除用户-清理关注关系开始，userId: {}", id);
            Set<Integer> affectedFollowUserIds = deleteUserFollowRelations(id);
            log.info("管理端删除用户-清理关注关系完成，userId: {}, affectedUserCount: {}", id, affectedFollowUserIds.size());

            log.info("管理端删除用户-重算关注计数开始，userId: {}", id);
            syncFollowCounts(affectedFollowUserIds);
            log.info("管理端删除用户-重算关注计数完成，userId: {}", id);

            log.info("管理端删除用户-清理通知开始，userId: {}", id);
            messageMapper.delete(new LambdaQueryWrapper<Message>()
                    .and(wrapper -> wrapper.eq(Message::getSenderId, id).or().eq(Message::getReceiverId, id)));
            log.info("管理端删除用户-清理通知完成，userId: {}", id);

            log.info("管理端删除用户-清理私信开始，userId: {}", id);
            privateMessageMapper.delete(new LambdaQueryWrapper<PrivateMessage>()
                    .and(wrapper -> wrapper.eq(PrivateMessage::getFromUserId, id).or().eq(PrivateMessage::getToUserId, id)));
            log.info("管理端删除用户-清理私信完成，userId: {}", id);

            log.info("管理端删除用户-清理会话开始，userId: {}", id);
            conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                    .and(wrapper -> wrapper.eq(Conversation::getUserId, id).or().eq(Conversation::getTargetUserId, id)));
            log.info("管理端删除用户-清理会话完成，userId: {}", id);

            log.info("管理端删除用户-清理角色/设置/图片记录开始，userId: {}", id);
            sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
            userSettingsMapper.delete(new LambdaQueryWrapper<UserSettings>().eq(UserSettings::getUserId, id));
            photoMapper.delete(new LambdaQueryWrapper<Photo>().eq(Photo::getUserId, id));
            log.info("管理端删除用户-清理角色/设置/图片记录完成，userId: {}", id);

            log.info("管理端删除用户-注册提交后清理任务开始，userId: {}", id);
            registerUserCleanupAfterCommit(id, nonArticlePhotoUrls, relatedConversationUserIds);
            log.info("管理端删除用户-注册提交后清理任务完成，userId: {}", id);

            log.info("管理端删除用户-删除用户主表开始，userId: {}", id);
            sysUserMapper.deleteById(id);
            log.info("管理端删除用户完成，userId: {}", id);
        } catch (Exception e) {
            log.error("管理端删除用户失败，userId: {}", id, e);
            throw e;
        }
    }

    private Set<String> collectUserNonArticlePhotoUrls(SysUser sysUser, Integer userId) {
        Set<String> urls = new HashSet<>();
        addNonEmptyPhotoUrl(urls, sysUser == null ? null : sysUser.getAvatar());

        List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
                .select(Photo::getUrl)
                .eq(Photo::getUserId, userId));
        if (ObjectUtil.isNotEmpty(photos)) {
            photos.stream()
                    .filter(Objects::nonNull)
                    .map(Photo::getUrl)
                    .filter(this::isNonEmptyPhotoUrl)
                    .forEach(urls::add);
        }

        List<PrivateMessage> privateMessages = privateMessageMapper.selectList(new LambdaQueryWrapper<PrivateMessage>()
                .select(PrivateMessage::getImageUrl)
                .and(wrapper -> wrapper
                        .eq(PrivateMessage::getFromUserId, userId)
                        .or()
                        .eq(PrivateMessage::getToUserId, userId)));
        if (ObjectUtil.isNotEmpty(privateMessages)) {
            privateMessages.stream()
                    .filter(Objects::nonNull)
                    .map(PrivateMessage::getImageUrl)
                    .filter(this::isNonEmptyPhotoUrl)
                    .forEach(urls::add);
        }

        return urls;
    }

    private void addNonEmptyPhotoUrl(Set<String> urls, String url) {
        if (urls != null && isNonEmptyPhotoUrl(url)) {
            urls.add(url);
        }
    }

    private boolean isNonEmptyPhotoUrl(String url) {
        return url != null && !url.trim().isEmpty();
    }

    private boolean isAdminUser(Integer userId) {
        if (userId == null) {
            return false;
        }

        List<Integer> roleIds = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (roleIds.isEmpty()) {
            return false;
        }

        return sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getId, roleIds)
                .eq(SysRole::getRole, "admin")) > 0;
    }

    private boolean isUserAccount(SysUser user) {
        if (user == null || user.getSysRoles() == null) {
            return false;
        }
        Set<String> roleCodes = user.getSysRoles().stream()
                .map(SysRole::getRole)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return roleCodes.size() == 1 && roleCodes.contains("user");
    }

    private boolean isAdminAccount(SysUser user) {
        if (user == null || user.getSysRoles() == null) {
            return false;
        }
        Set<String> roleCodes = user.getSysRoles().stream()
                .map(SysRole::getRole)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return roleCodes.size() == 1 && (roleCodes.contains("admin") || roleCodes.contains("content_admin"));
    }

    private boolean isDisablingUser(SysUserDto sysUserDto, SysUser originalUser) {
        return sysUserDto.getStatus() != null
                && Objects.equals(sysUserDto.getStatus(), StatusEnum.DISABLE.getStatus())
                && !Objects.equals(originalUser.getStatus(), sysUserDto.getStatus());
    }

    private Set<Integer> collectRelatedConversationUserIds(Integer userId) {
        Set<Integer> relatedUserIds = new HashSet<>();

        privateMessageMapper.selectList(new LambdaQueryWrapper<PrivateMessage>()
                        .select(PrivateMessage::getFromUserId, PrivateMessage::getToUserId)
                        .and(wrapper -> wrapper
                                .eq(PrivateMessage::getFromUserId, userId)
                                .or()
                                .eq(PrivateMessage::getToUserId, userId)))
                .forEach(message -> {
                    if (!Objects.equals(message.getFromUserId(), userId)) {
                        relatedUserIds.add(message.getFromUserId());
                    }
                    if (!Objects.equals(message.getToUserId(), userId)) {
                        relatedUserIds.add(message.getToUserId());
                    }
                });

        conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .select(Conversation::getUserId, Conversation::getTargetUserId)
                        .and(wrapper -> wrapper
                                .eq(Conversation::getUserId, userId)
                                .or()
                                .eq(Conversation::getTargetUserId, userId)))
                .forEach(conversation -> {
                    if (!Objects.equals(conversation.getUserId(), userId)) {
                        relatedUserIds.add(conversation.getUserId());
                    }
                    if (!Objects.equals(conversation.getTargetUserId(), userId)) {
                        relatedUserIds.add(conversation.getTargetUserId());
                    }
                });

        relatedUserIds.remove(userId);
        return relatedUserIds;
    }

    private void cleanupUserFavorites(Integer userId) {
        List<Integer> favoriteIds = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                        .select(Favorite::getId)
                        .eq(Favorite::getUserId, userId))
                .stream()
                .map(Favorite::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (ObjectUtil.isEmpty(favoriteIds)) {
            return;
        }

        articleFavoriteMapper.delete(new LambdaQueryWrapper<ArticleFavorite>()
                .in(ArticleFavorite::getFavoriteId, favoriteIds));
        favoriteMapper.deleteBatchIds(favoriteIds);
    }

    private void cleanupUserComments(Integer userId) {
        List<Comment> userComments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .select(Comment::getId, Comment::getArticleId, Comment::getParentId)
                .eq(Comment::getUserId, userId));

        if (ObjectUtil.isEmpty(userComments)) {
            commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                    .eq(Comment::getReplyUserId, userId)
                    .set(Comment::getReplyUserId, null));
            return;
        }

        Set<Integer> commentIdsToDelete = userComments.stream()
                .map(Comment::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Integer> affectedArticleIds = userComments.stream()
                .map(Comment::getArticleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<Integer> currentParentIds = new ArrayList<>(commentIdsToDelete);
        while (ObjectUtil.isNotEmpty(currentParentIds)) {
            List<Comment> childComments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                    .select(Comment::getId, Comment::getArticleId, Comment::getParentId)
                    .in(Comment::getParentId, currentParentIds));
            if (ObjectUtil.isEmpty(childComments)) {
                break;
            }

            currentParentIds = new ArrayList<>();
            for (Comment childComment : childComments) {
                if (childComment == null || childComment.getId() == null || !commentIdsToDelete.add(childComment.getId())) {
                    continue;
                }
                if (childComment.getArticleId() != null) {
                    affectedArticleIds.add(childComment.getArticleId());
                }
                currentParentIds.add(childComment.getId());
            }
        }

        Set<Integer> affectedParentCommentIds = userComments.stream()
                .map(Comment::getParentId)
                .filter(parentId -> parentId != null && parentId > 0 && !commentIdsToDelete.contains(parentId))
                .collect(Collectors.toCollection(HashSet::new));

        likeMapper.delete(new LambdaQueryWrapper<Like>()
                .eq(Like::getType, LikeTypeEnum.COMMENT.getCode())
                .in(Like::getTypeId, commentIdsToDelete));
        commentMapper.delete(new LambdaQueryWrapper<Comment>().in(Comment::getId, commentIdsToDelete));
        commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getReplyUserId, userId)
                .set(Comment::getReplyUserId, null));

        syncParentCommentReplyCounts(affectedParentCommentIds);
        syncArticleCommentCounts(affectedArticleIds);
    }

    private void syncParentCommentReplyCounts(Collection<Integer> parentCommentIds) {
        if (ObjectUtil.isEmpty(parentCommentIds)) {
            return;
        }

        parentCommentIds.forEach(parentCommentId -> {
            long replyCount = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getParentId, parentCommentId)
                    .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode()));
            commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                    .eq(Comment::getId, parentCommentId)
                    .set(Comment::getReplyCount, Math.toIntExact(replyCount)));
        });
    }

    private void syncArticleCommentCounts(Collection<Integer> articleIds) {
        if (ObjectUtil.isEmpty(articleIds)) {
            return;
        }

        articleIds.forEach(articleId -> {
            long commentCount = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getArticleId, articleId)
                    .eq(Comment::getParentId, 0)
                    .eq(Comment::getExamineStatus, ExamineStatusEnum.PASS.getCode()));
            articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, articleId)
                    .set(Article::getCommentCount, Math.toIntExact(commentCount)));
        });
    }

    private Set<Integer> deleteUserFollowRelations(Integer userId) {
        List<Follow> follows = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                .select(Follow::getFollowerId, Follow::getFollowedId)
                .and(wrapper -> wrapper
                        .eq(Follow::getFollowerId, userId)
                        .or()
                        .eq(Follow::getFollowedId, userId)));

        Set<Integer> affectedUserIds = new HashSet<>();
        follows.forEach(follow -> {
            if (!Objects.equals(follow.getFollowerId(), userId)) {
                affectedUserIds.add(follow.getFollowerId());
            }
            if (!Objects.equals(follow.getFollowedId(), userId)) {
                affectedUserIds.add(follow.getFollowedId());
            }
        });

        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .and(wrapper -> wrapper
                        .eq(Follow::getFollowerId, userId)
                        .or()
                        .eq(Follow::getFollowedId, userId)));
        return affectedUserIds;
    }

    private void syncFollowCounts(Collection<Integer> userIds) {
        if (ObjectUtil.isEmpty(userIds)) {
            return;
        }

        userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(userId -> {
                    long followCount = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getFollowerId, userId));
                    long fansCount = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getFollowedId, userId));
                    SysUser updateUser = new SysUser()
                            .setId(userId)
                            .setFollowCount(Math.toIntExact(followCount))
                            .setFansCount(Math.toIntExact(fansCount));
                    sysUserMapper.updateById(updateUser);
                });
    }

    private void registerUserCleanupAfterCommit(Integer userId, Collection<String> nonArticlePhotoUrls,
            Collection<Integer> relatedConversationUserIds) {
        Runnable cleanupTask = () -> {
            try {
                log.info("管理端删除用户-afterCommit清理开始，userId: {}", userId);
                photoServiceImpl.cleanNonArticlePhotoObjectsByUrlsIfUnused(nonArticlePhotoUrls);
                clearUserRedisKeys(userId, relatedConversationUserIds);
                log.info("管理端删除用户-afterCommit清理完成，userId: {}", userId);
            } catch (Exception e) {
                log.error("管理端删除用户-afterCommit清理失败，userId: {}", userId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupTask.run();
                }
            });
            return;
        }

        cleanupTask.run();
    }

    private void clearUserRedisKeys(Integer userId, Collection<Integer> relatedConversationUserIds) {
        Set<String> keysToDelete = new HashSet<>();
        keysToDelete.add(RedisConstants.UserDetail + userId);
        keysToDelete.add(RedisConstants.UserProfile + userId);
        keysToDelete.add(RedisConstants.UserBehavior + userId);
        keysToDelete.add(RedisConstants.UserRecommend + userId);
        keysToDelete.add(RedisConstants.USER_ONLINE_STATUS_KEY + userId);
        keysToDelete.add(RedisConstants.PRIVATE_MESSAGE_UNREAD_COUNT_KEY + userId);
        keysToDelete.add(RedisConstants.AiUsage + userId);

        if (ObjectUtil.isNotEmpty(relatedConversationUserIds)) {
            relatedConversationUserIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(relatedUserId -> keysToDelete.add(RedisConstants.PRIVATE_MESSAGE_UNREAD_COUNT_KEY + relatedUserId));
        }

        stringRedisTemplate.delete(keysToDelete);

        Set<String> aiContentHashKeys = stringRedisTemplate.keys(RedisConstants.AiContentHash + userId + ":*");
        if (ObjectUtil.isNotEmpty(aiContentHashKeys)) {
            stringRedisTemplate.delete(aiContentHashKeys);
        }
    }

    // 管理端搜索角色
    @Override
    public List<SysUserVo> searchUser(SysUserSearchDTO sysUserSearchDTO) {
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(ObjectUtil.isNotEmpty(sysUserSearchDTO.getUsername()), SysUser::getUsername,
                sysUserSearchDTO.getUsername());
        queryWrapper.like(ObjectUtil.isNotEmpty(sysUserSearchDTO.getEmail()), SysUser::getEmail,
                sysUserSearchDTO.getEmail());
        queryWrapper.eq(ObjectUtil.isNotEmpty(sysUserSearchDTO.getStatus()), SysUser::getStatus,
                sysUserSearchDTO.getStatus());
        queryWrapper.ge(ObjectUtil.isNotEmpty(sysUserSearchDTO.getCreateTimeStart()), SysUser::getCreateTime,
                sysUserSearchDTO.getCreateTimeStart());
        queryWrapper.le(ObjectUtil.isNotEmpty(sysUserSearchDTO.getCreateTimeEnd()), SysUser::getCreateTime,
                sysUserSearchDTO.getCreateTimeEnd());
        queryWrapper.orderByAsc(SysUser::getId);

        List<SysUser> sysUsers = sysUserMapper.selectList(queryWrapper);
        List<SysUserVo> sysUserVos = BeanUtil.copyToList(sysUsers, SysUserVo.class);
        maskEmailsForNonAdmin(sysUserVos);
        return sysUserVos;
    }

    @Override
    public PageVo<List<SysUserVo>> searchUserPage(SysUserSearchDTO sysUserSearchDTO) {
        Page<SysUser> page = new Page<>(sysUserSearchDTO.getPageNum(), sysUserSearchDTO.getPageSize());
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(ObjectUtil.isNotEmpty(sysUserSearchDTO.getUsername()), SysUser::getUsername,
                sysUserSearchDTO.getUsername());
        queryWrapper.like(ObjectUtil.isNotEmpty(sysUserSearchDTO.getEmail()), SysUser::getEmail,
                sysUserSearchDTO.getEmail());
        queryWrapper.eq(ObjectUtil.isNotEmpty(sysUserSearchDTO.getStatus()), SysUser::getStatus,
                sysUserSearchDTO.getStatus());
        queryWrapper.ge(ObjectUtil.isNotEmpty(sysUserSearchDTO.getCreateTimeStart()), SysUser::getCreateTime,
                sysUserSearchDTO.getCreateTimeStart());
        queryWrapper.le(ObjectUtil.isNotEmpty(sysUserSearchDTO.getCreateTimeEnd()), SysUser::getCreateTime,
                sysUserSearchDTO.getCreateTimeEnd());
        queryWrapper.orderByAsc(SysUser::getId);

        Page<SysUser> userPage = sysUserMapper.selectPage(page, queryWrapper);
        List<SysUserVo> sysUserVos = BeanUtil.copyToList(userPage.getRecords(), SysUserVo.class);
        fillUserRoleCodes(sysUserVos);
        maskEmailsForNonAdmin(sysUserVos);
        return new PageVo<>(sysUserVos, userPage.getTotal());
    }

    private void fillUserRoleCodes(List<SysUserVo> userVos) {
        if (ObjectUtil.isEmpty(userVos)) {
            return;
        }

        List<Integer> userIds = userVos.stream()
                .map(SysUserVo::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ObjectUtil.isEmpty(userIds)) {
            return;
        }

        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .in(SysUserRole::getUserId, userIds));
        if (ObjectUtil.isEmpty(userRoles)) {
            return;
        }

        Map<Integer, Integer> userIdRoleIdMap = new HashMap<>();
        userRoles.forEach(userRole -> {
            if (userRole != null && userRole.getUserId() != null && userRole.getRoleId() != null) {
                userIdRoleIdMap.putIfAbsent(userRole.getUserId(), userRole.getRoleId());
            }
        });
        if (userIdRoleIdMap.isEmpty()) {
            return;
        }

        List<Integer> roleIds = userIdRoleIdMap.values().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ObjectUtil.isEmpty(roleIds)) {
            return;
        }

        List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
        if (ObjectUtil.isEmpty(roles)) {
            return;
        }

        Map<Integer, String> roleIdRoleCodeMap = roles.stream()
                .filter(Objects::nonNull)
                .filter(role -> role.getId() != null)
                .collect(Collectors.toMap(SysRole::getId, SysRole::getRole, (existing, replacement) -> existing));

        userVos.forEach(userVo -> {
            if (userVo == null || userVo.getId() == null) {
                return;
            }
            Integer roleId = userIdRoleIdMap.get(userVo.getId());
            if (roleId == null) {
                return;
            }
            String roleCode = roleIdRoleCodeMap.get(roleId);
            if (roleCode != null) {
                userVo.setRoleCode(roleCode);
            }
        });
    }

    // 管理端获取用户详情
    @Override
    public SysUserDetailVo getUserInfo(Integer userId) {
        SysUser sysUser = sysUserMapper.selectById(userId);
        SysUser sysUserDetail = sysUserDetailsService.setUserDetail(sysUser);
        SysUserDetailVo sysUserDetailVo = BeanUtil.copyProperties(sysUserDetail, SysUserDetailVo.class);
        return sysUserDetailVo;
    }

    // 管理端获取用户总数统计
    @Override
    public Long getUserTotalCount() {
        return sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getIsDeleted, 0)); // 只统计未删除的用户
    }

    @Override
    public List<SysUserVo> getRecommendedAuthors(Integer limit) {
        // 按文章数量排序，获取活跃作者（已发布且审核通过的文章数量）
        // 先获取所有用户，然后按文章数量排序
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getIsDeleted, 0) // 只统计未删除的用户
                .orderByDesc(SysUser::getFansCount) // 按粉丝数降序
                // 添加参数边界验证，防止负数或过大值导致 SQL 错误
                .last("LIMIT " + Math.min(Math.max(1, limit), 100));

        List<SysUser> users = this.list(queryWrapper);
        List<SysUserVo> result = BeanUtil.copyToList(users, SysUserVo.class);

        // 为每个用户设置文章数量
        for (SysUserVo userVo : result) {
            LambdaQueryWrapper<Article> articleQuery = new LambdaQueryWrapper<Article>()
                    .eq(Article::getUserId, userVo.getId())
                    .eq(Article::getEditStatus, EditStatusEnum.PUBLISHED.getCode())
                    .eq(Article::getExamineStatus, ExamineStatusEnum.PASS.getCode());
            Integer articleCount = Math.toIntExact(articleMapper.selectCount(articleQuery));
            userVo.setArticleCount(articleCount);
        }

        return result;
    }

    private void maskEmailsForNonAdmin(List<SysUserVo> sysUserVos) {
        SysUser currentUser = SecurityUtils.getUser();
        boolean isAdmin = currentUser.getSysRoles() != null && currentUser.getSysRoles().stream()
                .anyMatch(role -> "admin".equals(role.getRole()));
        if (!isAdmin) {
            sysUserVos.forEach(userVo -> userVo.setEmail(null));
        }
    }

    @Override
    public Map<String, Object> getCommunityStats() {
        Map<String, Object> stats = new HashMap<>();

        List<Article> publicArticles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getUserId, Article::getReadCount)
                .eq(Article::getIsDeleted, 0)
                .eq(Article::getEditStatus, EditStatusEnum.PUBLISHED.getCode())
                .eq(Article::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                .eq(Article::getVisibleRange, VisibleRangeEnum.ALL.getCode()));

        Long articleCount = (long) publicArticles.size();

        // 获取用户总数（未删除）
        LambdaQueryWrapper<SysUser> userQuery = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getIsDeleted, 0);
        Long userCount = this.count(userQuery);

        // 获取总阅读量（符合首页公开口径文章的 readCount 求和）
        Long totalViews = publicArticles.stream()
                .map(Article::getReadCount)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();

        Set<Integer> authorUserIds = publicArticles.stream()
                .map(Article::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Long authorCount = 0L;
        if (ObjectUtil.isNotEmpty(authorUserIds)) {
            authorCount = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .in(SysUser::getId, authorUserIds)
                    .eq(SysUser::getIsDeleted, 0));
        }

        stats.put("articleCount", articleCount);
        stats.put("userCount", userCount);
        stats.put("viewCount", totalViews);
        stats.put("authorCount", authorCount);

        return stats;
    }

    @Override
    public List<Map<String, Object>> getHotSearches(Integer limit) {
        // 从 Redis 中获取热门搜索记录
        // Redis ZSet 格式：key = "hot_searches", member = 关键词，score = 搜索次数
        return redisComponent.getHotSearches(limit);
    }

}
