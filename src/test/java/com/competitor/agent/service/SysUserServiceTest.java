package com.competitor.agent.service;

import com.competitor.agent.common.BusinessException;
import com.competitor.agent.dto.LoginRequest;
import com.competitor.agent.dto.RegisterRequest;
import com.competitor.agent.entity.SysUser;
import com.competitor.agent.mapper.SysUserMapper;
import com.competitor.agent.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysUserServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private JwtUtil jwtUtil;

    private SysUserService sysUserService;
    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        sysUserService = new SysUserService(sysUserMapper, jwtUtil);
    }

    // ========== 注册测试 ==========

    @Test
    void register_success() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        when(sysUserMapper.insert(any())).thenAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(1L); // 模拟MyBatis自动回填ID
            return 1;
        });

        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setPassword("pass123");
        request.setEmail("test@test.com");

        Map<String, Object> result = sysUserService.register(request);

        assertEquals("testuser", result.get("username"));
        assertEquals(1L, result.get("id"));
        verify(sysUserMapper).insert(any());
    }

    @Test
    void register_duplicateUsername_throwsException() {
        SysUser existing = new SysUser();
        existing.setUsername("testuser");
        when(sysUserMapper.selectOne(any())).thenReturn(existing);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("testuser");
        request.setPassword("pass123");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysUserService.register(request));
        assertEquals(400, ex.getCode());
        verify(sysUserMapper, never()).insert(any());
    }

    // ========== 登录测试 ==========

    @Test
    void login_success() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("admin123"));

        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(jwtUtil.generateToken(1L, "admin")).thenReturn("mock-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        Map<String, Object> result = sysUserService.login(request);

        assertEquals("mock-token", result.get("token"));
        assertEquals("admin", result.get("username"));
    }

    @Test
    void login_userNotFound_throws401() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);

        LoginRequest request = new LoginRequest();
        request.setUsername("nouser");
        request.setPassword("pass");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysUserService.login(request));
        assertEquals(401, ex.getCode());
    }

    @Test
    void login_wrongPassword_throws401() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode("admin123"));

        when(sysUserMapper.selectOne(any())).thenReturn(user);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrongpass");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysUserService.login(request));
        assertEquals(401, ex.getCode());
    }
}
