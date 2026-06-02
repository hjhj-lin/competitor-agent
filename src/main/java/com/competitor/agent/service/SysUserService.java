package com.competitor.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.competitor.agent.common.BusinessException;
import com.competitor.agent.dto.LoginRequest;
import com.competitor.agent.dto.RegisterRequest;
import com.competitor.agent.entity.SysUser;
import com.competitor.agent.mapper.SysUserMapper;
import com.competitor.agent.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public List<SysUser> listUsers() {
        return sysUserMapper.selectList(null);
    }

    public SysUser getUserById(Long id) {
        return sysUserMapper.selectById(id);
    }

    public SysUser getUserByUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        return sysUserMapper.selectOne(wrapper);
    }

    public Map<String, Object> register(RegisterRequest request) {
        if (getUserByUsername(request.getUsername()) != null) {
            throw BusinessException.badRequest("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());

        sysUserMapper.insert(user);

        log.info("[用户注册] userId={} username={}", user.getId(), user.getUsername());

        return Map.of(
            "id", user.getId(),
            "username", user.getUsername()
        );
    }

    public Map<String, Object> login(LoginRequest request) {
        SysUser user = getUserByUsername(request.getUsername());
        if (user == null) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        log.info("[用户登录] userId={} username={}", user.getId(), user.getUsername());

        return Map.of(
            "token", token,
            "username", user.getUsername()
        );
    }
}
