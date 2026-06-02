package com.competitor.agent.controller;

import com.competitor.agent.common.Result;
import com.competitor.agent.entity.SysUser;
import com.competitor.agent.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final SysUserService sysUserService;

    @GetMapping("/hello")
    public Result<?> hello() {
        return Result.success(java.util.Map.of(
            "message", "hello, competitor-agent!",
            "status", "running"
        ));
    }

    @GetMapping("/users")
    public Result<?> listUsers() {
        return Result.success(sysUserService.listUsers());
    }

    @GetMapping("/users/{id}")
    public Result<?> getUser(@PathVariable Long id) {
        return Result.success(sysUserService.getUserById(id));
    }
}
