package com.zcx.chenstack.service;

import com.zcx.chenstack.config.ChenStackConfig;
import com.zcx.chenstack.domain.entity.Photo;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.result.AuditResult;
import com.zcx.chenstack.mapper.PhotoMapper;
import com.zcx.chenstack.service.impl.PhotoServiceImpl;
import com.zcx.chenstack.utils.ImageAuditUtils;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhotoMessageAuditTest {
    @Test
    void asynchronousImageAuditPersistsResultThenCompletesPrivateMessageDelivery() {
        PhotoServiceImpl photos = new PhotoServiceImpl();
        PhotoMapper mapper = mock(PhotoMapper.class);
        PrivateMessageService messages = mock(PrivateMessageService.class);
        ImageAuditUtils imageAudit = mock(ImageAuditUtils.class);
        ChenStackConfig config = mock(ChenStackConfig.class);
        ReflectionTestUtils.setField(photos, "photoMapper", mapper);
        ReflectionTestUtils.setField(photos, "privateMessageService", messages);
        ReflectionTestUtils.setField(photos, "imageAuditUtils", imageAudit);
        ReflectionTestUtils.setField(photos, "chenStackConfig", config);
        when(config.isPhotoAutoAudit()).thenReturn(true);
        when(imageAudit.auditImageWithDetails(anyString()))
                .thenReturn(new AuditResult(ExamineStatusEnum.PASS.getCode(), "测试通过"));
        String url = "https://example.invalid/message/test.png";
        ReflectionTestUtils.invokeMethod(photos, "savePhotoAndAudit", 7, url, false);
        InOrder order = inOrder(mapper, messages);
        order.verify(mapper).insert(any(Photo.class));
        order.verify(mapper).updateById(argThat((Photo photo) -> photo.getExamineStatus().equals(ExamineStatusEnum.PASS.getCode())));
        order.verify(messages).syncImageAuditStatus(List.of(url), ExamineStatusEnum.PASS.getCode());
    }
}
