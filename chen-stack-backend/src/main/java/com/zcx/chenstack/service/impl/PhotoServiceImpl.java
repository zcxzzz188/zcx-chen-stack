package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.config.ChenStackConfig;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.constants.MessageConstants;
import com.zcx.chenstack.domain.constants.RabbitMQConstants;
import com.zcx.chenstack.domain.dto.MessageDto;
import com.zcx.chenstack.domain.dto.PhotoAuditDto;
import com.zcx.chenstack.domain.dto.PhotoDto;
import com.zcx.chenstack.domain.entity.Photo;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.enums.MessageTypeEnum;
import com.zcx.chenstack.domain.enums.UploadEnum;
import com.zcx.chenstack.domain.result.AuditResult;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.PhotoVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.PhotoMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.service.MessageService;
import com.zcx.chenstack.service.PhotoService;
import com.zcx.chenstack.utils.FileUploadUtils;
import com.zcx.chenstack.utils.ImageAuditUtils;
import com.zcx.chenstack.utils.MyThreadFactory;
import com.zcx.chenstack.utils.SecurityUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-07-30
 */
@Service
@Slf4j
public class PhotoServiceImpl extends ServiceImpl<PhotoMapper, Photo> implements PhotoService {

    ExecutorService executorService = new ThreadPoolExecutor(
            2, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500),
            new MyThreadFactory("PhotoServiceImpl"));

    @Resource
    private FileUploadUtils fileUploadUtils;
    @Resource
    private PhotoMapper photoMapper;
    @Resource
    private ImageAuditUtils imageAuditUtils;
    @Resource
    private SysUserMapper sysUserMapper;
    @Resource
    private MessageService messageService;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ChenStackConfig chenStackConfig;

    private static @NotNull Photo setPhotoExamineStatus(Photo photo, Integer status) {
        Photo updatePhoto = new Photo();
        updatePhoto.setId(photo.getId());
        if (status.equals(ExamineStatusEnum.PASS.getCode())) {
            // 审核通过，设置审核状态为审核通过
            updatePhoto.setExamineStatus(ExamineStatusEnum.PASS.getCode());
        } else if (status.equals(ExamineStatusEnum.NO_PASS.getCode())) {
            // 审核未通过，设置审核状态为审核未通过
            updatePhoto.setExamineStatus(ExamineStatusEnum.NO_PASS.getCode());
        } else if (status.equals(ExamineStatusEnum.WAIT.getCode())) {
            // 需要人工审核, 设置审核状态为待审核
            updatePhoto.setExamineStatus(ExamineStatusEnum.WAIT.getCode());
        } else {
            // 状态错误
            throw new BlogException(BlogConstants.ExamineStatusError);
        }
        return updatePhoto;
    }

    public void passPhotosByUrls(Collection<String> urls) {
        if (ObjectUtil.isEmpty(urls)) {
            return;
        }

        List<String> validUrls = urls.stream()
                .filter(ObjectUtil::isNotEmpty)
                .distinct()
                .collect(Collectors.toList());
        if (ObjectUtil.isEmpty(validUrls)) {
            return;
        }

        LambdaUpdateWrapper<Photo> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(Photo::getUrl, validUrls)
                .ne(Photo::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                .set(Photo::getExamineStatus, ExamineStatusEnum.PASS.getCode());
        photoMapper.update(null, updateWrapper);
    }

    public AuditResult auditPhotoUrlsByStatus(Collection<String> urls) {
        if (ObjectUtil.isEmpty(urls)) {
            return new AuditResult(ExamineStatusEnum.PASS.getCode(), "文章无关联图片");
        }

        List<String> validUrls = urls.stream()
                .filter(ObjectUtil::isNotEmpty)
                .distinct()
                .collect(Collectors.toList());
        if (ObjectUtil.isEmpty(validUrls)) {
            return new AuditResult(ExamineStatusEnum.PASS.getCode(), "文章无关联图片");
        }

        List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
                .select(Photo::getId, Photo::getUrl, Photo::getExamineStatus)
                .in(Photo::getUrl, validUrls)
                .orderByDesc(Photo::getId));

        Map<String, Photo> photoMap = photos.stream()
                .collect(Collectors.toMap(Photo::getUrl, photo -> photo, (current, ignored) -> current));

        List<String> missingUrls = new java.util.ArrayList<>();
        List<String> waitUrls = new java.util.ArrayList<>();

        for (String url : validUrls) {
            Photo photo = photoMap.get(url);
            if (ObjectUtil.isEmpty(photo)) {
                missingUrls.add(url);
                continue;
            }
            if (ExamineStatusEnum.NO_PASS.getCode().equals(photo.getExamineStatus())) {
                return new AuditResult(ExamineStatusEnum.NO_PASS.getCode(), "关联图片未通过审核: " + url);
            }
            if (!ExamineStatusEnum.PASS.getCode().equals(photo.getExamineStatus())) {
                waitUrls.add(url);
            }
        }

        if (ObjectUtil.isNotEmpty(missingUrls)) {
            return new AuditResult(ExamineStatusEnum.WAIT.getCode(), "关联图片记录不存在，需人工审核: " + missingUrls.get(0));
        }

        if (ObjectUtil.isNotEmpty(waitUrls)) {
            return new AuditResult(ExamineStatusEnum.WAIT.getCode(), "关联图片待审核，需人工审核: " + waitUrls.get(0));
        }

        return new AuditResult(ExamineStatusEnum.PASS.getCode(), "关联图片审核通过");
    }

    @Override
    public String uploadArticle(MultipartFile file) {
        Integer userId = SecurityUtils.getUserId();
        String url = fileUploadUtils.upload(UploadEnum.ARTICLE, file);
        auditAndUpdate(userId, url);
        return url;
    }

    @Override
    public String uploadColumn(MultipartFile file) {
        Integer userId = SecurityUtils.getUserId();
        String url = fileUploadUtils.upload(UploadEnum.COLUMN, file);
        auditAndUpdate(userId, url);
        return url;
    }

    @Override
    public void uploadAvatar(MultipartFile file) {
        Integer userId = SecurityUtils.getUserId();
        String url = fileUploadUtils.upload(UploadEnum.USER_AVATAR, file);
        auditAvatarAndUpdate(userId, url);
    }

    @Override
    public String uploadMessage(MultipartFile file) {
        Integer userId = SecurityUtils.getUserId();

        // 上传图片
        String url = fileUploadUtils.upload(UploadEnum.MESSAGE, file);

        // 异步记录到图片表（不阻塞返回，不做审核）
        recordMessagePhotoAsync(userId, url);

        return url;
    }

    /**
     * 异步记录私信图片到图片表
     * 仅用于追溯和管理，不做审核
     */
    private void recordMessagePhotoAsync(Integer userId, String url) {
        executorService.execute(() -> {
            try {
                Photo photo = new Photo();
                photo.setUserId(userId);
                photo.setUrl(url);
                photo.setExamineStatus(ExamineStatusEnum.WAIT.getCode()); // 直接标记为待审核，不做审核
                photoMapper.insert(photo);
            } catch (Exception e) {
                // 记录失败不影响用户使用，只记录日志
                log.error("记录私信图片失败，userId: {}, url: {}", userId, url, e);
            }
        });
    }

    // 图片审核
    private void auditAndUpdate(Integer userId, String url) {
        executorService.execute(() -> {
            try {
                // 保存图片信息到数据库
                Photo photo = new Photo();
                photo.setUserId(userId);
                photo.setUrl(url);
                photo.setExamineStatus(ExamineStatusEnum.WAIT.getCode());// 初始设置为待审核状态
                photo.setCreateTime(new Date()); // 异步线程中手动设置时间，MyBatis-Plus 自动填充不生效
                photo.setUpdateTime(new Date());
                photoMapper.insert(photo);

                Integer status = 0;
                // 图片自动审核
                if (chenStackConfig.isPhotoAutoAudit()) {
                    AuditResult imageAuditResult = imageAuditUtils.auditImageWithDetails(url);
                    status = imageAuditResult.getStatus();
                    // 根据审核结果设置审核状态
                    Photo updatePhoto = setPhotoExamineStatus(photo, status);
                    // 更新图片审核状态
                    photoMapper.updateById(updatePhoto);
                }

                if (!chenStackConfig.isPhotoAutoAudit() || status.equals(ExamineStatusEnum.WAIT.getCode())) {
                    // 需要人工审核，发送消息给管理员
                    String text = MessageConstants.ImageNeedReview(photo.getId());
                    MessageDto messageDto = new MessageDto();
                    messageDto.setType(MessageTypeEnum.SYSTEM.getCode());
                    messageDto.setContent(text);
                    messageService.sendToAdmin(messageDto);
                    // 发送邮件给管理员
                    HashMap<String, Object> sendEmail = new HashMap<>();
                    sendEmail.put("text", String.format(MessageConstants.IMAGE_NEED_REVIEW, photo.getId()));
                    rabbitTemplate.convertAndSend(RabbitMQConstants.Examine_Exchange,
                            RabbitMQConstants.Examine_Routing_Key, sendEmail);
                }

            } catch (Exception e) {
                log.error("图片异步处理失败", e);
            }
        });
    }

    // 头像审核专用方法
    private void auditAvatarAndUpdate(Integer userId, String url) {
        executorService.execute(() -> {
            try {
                // 保存图片信息到数据库
                Photo photo = new Photo();
                photo.setUserId(userId);
                photo.setUrl(url);
                photo.setExamineStatus(ExamineStatusEnum.WAIT.getCode());
                photo.setCreateTime(new Date()); // 异步线程中手动设置时间，MyBatis-Plus 自动填充不生效
                photo.setUpdateTime(new Date());
                photoMapper.insert(photo);

                Integer status = 0;
                // 图片自动审核
                if (chenStackConfig.isPhotoAutoAudit()) {
                    AuditResult imageAuditResult = imageAuditUtils.auditImageWithDetails(url);
                    status = imageAuditResult.getStatus();
                    Photo updatePhoto = setPhotoExamineStatus(photo, status);
                    photoMapper.updateById(updatePhoto);

                    // 审核通过，更新用户头像
                    if (status.equals(ExamineStatusEnum.PASS.getCode())) {
                        updateUserAvatar(userId, url);
                    }
                }

                if (!chenStackConfig.isPhotoAutoAudit() || status.equals(ExamineStatusEnum.WAIT.getCode())) {
                    // 需要人工审核，发送消息给管理员
                    String text = MessageConstants.AvatarNeedReview(userId, photo.getId());
                    MessageDto messageDto = new MessageDto();
                    messageDto.setType(MessageTypeEnum.SYSTEM.getCode());
                    messageDto.setContent(text);
                    messageService.sendToAdmin(messageDto);

                    // 发送邮件
                    HashMap<String, Object> sendEmail = new HashMap<>();
                    sendEmail.put("text", String.format(MessageConstants.AVATAR_NEED_REVIEW, userId, photo.getId()));
                    rabbitTemplate.convertAndSend(RabbitMQConstants.Examine_Exchange,
                            RabbitMQConstants.Examine_Routing_Key, sendEmail);
                }
            } catch (Exception e) {
                log.error("头像异步审核失败，用户ID: {}", userId, e);
            }
        });
    }

    // 审核通过后更新用户头像
    private void updateUserAvatar(Integer userId, String url) {
        LambdaUpdateWrapper<SysUser> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SysUser::getId, userId)
                .set(SysUser::getAvatar, url);
        sysUserMapper.update(null, updateWrapper);
    }

    @Override
    public void adminDelete(Integer photoId) {
        Photo photo = photoMapper.selectById(photoId);
        if (ObjectUtil.isEmpty(photo)) {
            throw new BlogException(BlogConstants.NotFoundPhoto);
        }

        photoMapper.deleteById(photoId);
        fileUploadUtils.deleteFiles(List.of(fileUploadUtils.getObjectName(photo.getUrl())));
    }

    @Override
    public void adminBatchDelete(List<Integer> photoIds) {
        List<Photo> photos = photoMapper.selectBatchIds(photoIds);
        if (ObjectUtil.isEmpty(photos)) {
            throw new BlogException(BlogConstants.NotFoundPhoto);
        }

        photoMapper.deleteBatchIds(photoIds);
        List<String> filePaths = photos.stream()
                .map(Photo::getUrl)
                .map(fileUploadUtils::getObjectName)
                .toList();
        fileUploadUtils.deleteFiles(filePaths);
    }

    @Override
    public void adminAudit(PhotoAuditDto photoAuditDto) {
        // 更新照片审核状态
        LambdaUpdateWrapper<Photo> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Photo::getId, photoAuditDto.getPhotoId());
        updateWrapper.set(Photo::getExamineStatus, photoAuditDto.getExamineStatus());
        photoMapper.update(null, updateWrapper);

        // 如果是审核通过
        if (photoAuditDto.getExamineStatus() == 1) {
            Photo photo = photoMapper.selectById(photoAuditDto.getPhotoId());
            if (ObjectUtil.isEmpty(photo)) {
                return;
            }

            // 头像审核通过后更新用户头像
            if (photo.getUrl().contains("/user/avatar/")) {
                updateUserAvatar(photo.getUserId(), photo.getUrl());
            }
        }
    }

    @Override
    public void adminAuditBatch(List<PhotoAuditDto> photoAuditDto) {
        List<Integer> photoIds = photoAuditDto.stream().map(PhotoAuditDto::getPhotoId).collect(Collectors.toList());
        // 更新照片审核状态
        LambdaUpdateWrapper<Photo> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.in(Photo::getId, photoIds);
        updateWrapper.set(Photo::getExamineStatus, photoAuditDto.get(0).getExamineStatus());
        photoMapper.update(null, updateWrapper);

        for (PhotoAuditDto dto : photoAuditDto) {
            if (dto.getExamineStatus() == 1) {
                Photo photo = photoMapper.selectById(dto.getPhotoId());
                if (ObjectUtil.isNotEmpty(photo) && photo.getUrl().contains("/user/avatar/")) {
                    updateUserAvatar(photo.getUserId(), photo.getUrl());
                }
            }
        }
    }

    @Override
    public PageVo<List<PhotoVo>> listPhotos(Integer pageNum, Integer pageSize) {
        Page<Photo> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Photo> queryWrapper = new LambdaQueryWrapper<Photo>().orderByDesc(Photo::getId);
        List<Photo> photos = photoMapper.selectPage(page, queryWrapper).getRecords();
        if (ObjectUtil.isEmpty(photos)) {
            return new PageVo<>(List.of(), page.getTotal());
        }
        List<Integer> userIds = photos.stream().map(Photo::getUserId).collect(Collectors.toList());
        List<SysUser> sysUsers = sysUserMapper.selectBatchIds(userIds);
        Map<Integer, String> userMap = sysUsers.stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getUsername));

        List<PhotoVo> photoVos = BeanUtil.copyToList(photos, PhotoVo.class);
        // 填充用户名
        photoVos.forEach(photoVo -> photoVo.setUsername(userMap.get(photoVo.getUserId())));
        return new PageVo<>(photoVos, page.getTotal());
    }

    @Override
    public PageVo<List<PhotoVo>> adminSearch(PhotoDto photoDto) {
        Page<Photo> page = new Page<>(photoDto.getPageNum(), photoDto.getPageSize());
        // 管理员搜索照片 (
        LambdaQueryWrapper<Photo> queryWrapper = new LambdaQueryWrapper();
        queryWrapper
                .eq(ObjectUtil.isNotEmpty(photoDto.getUserId()), Photo::getUserId, photoDto.getUserId())
                .eq(ObjectUtil.isNotEmpty(photoDto.getExamineStatus()), Photo::getExamineStatus,
                        photoDto.getExamineStatus())
                .ge(ObjectUtil.isNotEmpty(photoDto.getCreateTimeStart()), Photo::getCreateTime,
                        photoDto.getCreateTimeStart())
                .le(ObjectUtil.isNotEmpty(photoDto.getCreateTimeEnd()), Photo::getCreateTime,
                        photoDto.getCreateTimeEnd())
                .orderByDesc(Photo::getId);
        List<Photo> photos = photoMapper.selectPage(page, queryWrapper).getRecords();
        if (ObjectUtil.isEmpty(photos)) {
            return new PageVo<>(List.of(), page.getTotal());
        }
        List<Integer> userIds = photos.stream().map(Photo::getUserId).collect(Collectors.toList());
        List<SysUser> sysUsers = sysUserMapper.selectBatchIds(userIds);
        Map<Integer, String> userMap = sysUsers.stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getUsername));

        List<PhotoVo> photoVos = BeanUtil.copyToList(photos, PhotoVo.class);
        // 填充用户名
        photoVos.forEach(photoVo -> photoVo.setUsername(userMap.get(photoVo.getUserId())));
        return new PageVo<>(photoVos, page.getTotal());
    }

}
