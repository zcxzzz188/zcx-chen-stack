package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.PhotoAuditDto;
import com.zcx.chenstack.domain.dto.PhotoDto;
import com.zcx.chenstack.domain.entity.Photo;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.PhotoVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author zcx
 * @since 2025-07-30
 */
public interface PhotoService extends IService<Photo> {

    // 上传文章图片
    String uploadArticle(MultipartFile file);

    // 上传图片到专栏
    String uploadColumn(MultipartFile file);

    // 上传用户头像
    void uploadAvatar(MultipartFile file);

    // 上传私信图片
    String uploadMessage(MultipartFile file);

    // 管理员删除图片
    void adminDelete(Integer photoId);

    // 管理员批量删除图片
    void adminBatchDelete(List<Integer> photoIds);

    // 审核图片
    void adminAudit(PhotoAuditDto photoAuditDto);

    // 批量审核图片
    void adminAuditBatch(List<PhotoAuditDto> photoAuditDto);

    // 获取图片列表
    PageVo<List<PhotoVo>> listPhotos(Integer pageNum, Integer pageSize);

    // 搜索图片
    PageVo<List<PhotoVo>> adminSearch(PhotoDto photoDto);

}
