package com.competitor.agent.controller;

import com.competitor.agent.common.Result;
import com.competitor.agent.entity.SysUser;
import com.competitor.agent.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理", description = "用户信息查询")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final SysUserService sysUserService;

    @Operation(summary = "健康检查")
    @GetMapping("/hello")
    public Result<?> hello() {
        return Result.success(java.util.Map.of(
            "message", "hello, competitor-agent!",
            "status", "running"
        ));
    }

    @Operation(summary = "获取用户列表")
    @GetMapping("/users")
    public Result<?> listUsers() {
        return Result.success(sysUserService.listUsers());
    }

    @Operation(summary = "获取用户详情")
    @GetMapping("/users/{id}")
    public Result<?> getUser(@PathVariable Long id) {
        return Result.success(sysUserService.getUserById(id));
    }
}
