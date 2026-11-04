package com.zcx.chenstack.service.impl;

import com.zcx.chenstack.domain.dto.ChangePasswordDto;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SysUserServiceImplChangePasswordTest {
    private SysUserServiceImpl service;
    private SysUserMapper mapper;
    private PasswordEncoder encoder;

    @BeforeEach void setup() {
        service = new SysUserServiceImpl();
        mapper = mock(SysUserMapper.class);
        encoder = mock(PasswordEncoder.class);
        ReflectionTestUtils.setField(service, "sysUserMapper", mapper);
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
        when(mapper.selectById(1)).thenReturn(new SysUser().setId(1).setPassword("old-hash"));
    }

    private static ChangePasswordDto dto(String oldPassword, String newPassword) {
        ChangePasswordDto dto = new ChangePasswordDto();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    @Test void verifiesCurrentPasswordAndUpdatesOnlyTheCurrentUserWithEncodedPassword() {
        when(encoder.matches("old-pass", "old-hash")).thenReturn(true);
        when(encoder.matches("new-pass", "old-hash")).thenReturn(false);
        when(encoder.encode("new-pass")).thenReturn("new-hash");
        when(mapper.updateById(any(SysUser.class))).thenReturn(1);

        service.changePassword(1, dto("old-pass", "new-pass"));

        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getId());
        assertEquals("new-hash", captor.getValue().getPassword());
        assertEquals(null, captor.getValue().getUsername());
        verify(encoder).encode("new-pass");
    }

    @Test void wrongCurrentPasswordIsRejectedWithoutUpdating() {
        when(encoder.matches("wrong", "old-hash")).thenReturn(false);

        BlogException error = assertThrows(BlogException.class,
                () -> service.changePassword(1, dto("wrong", "new-pass")));

        assertEquals("当前密码不正确", error.getMessage());
        verify(mapper, never()).updateById(any(SysUser.class));
        verify(encoder, never()).encode(any());
    }

    @Test void newPasswordEqualToCurrentPasswordIsRejected() {
        when(encoder.matches("old-pass", "old-hash")).thenReturn(true);
        when(encoder.matches("old-pass", "old-hash")).thenReturn(true);

        BlogException error = assertThrows(BlogException.class,
                () -> service.changePassword(1, dto("old-pass", "old-pass")));

        assertEquals("新密码不能与原密码相同", error.getMessage());
        verify(mapper, never()).updateById(any(SysUser.class));
    }

    @Test void missingCurrentUserDoesNotTouchAnyPassword() {
        when(mapper.selectById(1)).thenReturn(null);
        assertThrows(BlogException.class, () -> service.changePassword(1, dto("old-pass", "new-pass")));
        verify(mapper, never()).updateById(any(SysUser.class));
    }
}
