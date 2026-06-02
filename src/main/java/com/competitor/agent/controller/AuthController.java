package com.competitor.agent.controller;

import com.competitor.agent.common.Result;
import com.competitor.agent.dto.LoginRequest;
import com.competitor.agent.dto.RegisterRequest;
import com.competitor.agent.service.SysUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService sysUserService;

    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(sysUserService.register(request));
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(sysUserService.login(request));
    }
}
