package com.competitor.agent.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.competitor.agent.common.Result;
import com.competitor.agent.dto.CreateTaskRequest;
import com.competitor.agent.service.AnalysisTaskService;
import com.competitor.agent.service.SseEmitterService;
import com.competitor.agent.vo.AnalysisTaskListVO;
import com.competitor.agent.vo.AnalysisTaskVO;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AnalysisTaskService analysisTaskService;
    private final SseEmitterService sseEmitterService;

    @PostMapping
    public Result<AnalysisTaskVO> createTask(
            @RequestAttribute("currentUserId") Long userId,
            @Valid @RequestBody CreateTaskRequest request) {
        AnalysisTaskVO vo = analysisTaskService.createTask(userId, request);
        return Result.success(vo);
    }

    @GetMapping
    public Result<IPage<AnalysisTaskListVO>> listTasks(
            @RequestAttribute("currentUserId") Long userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1) pageSize = 10;
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        IPage<AnalysisTaskListVO> page = analysisTaskService.listTasks(userId, pageNum, pageSize);
        return Result.success(page);
    }

    @GetMapping("/{id}")
    public Result<AnalysisTaskVO> getTaskById(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long id) {
        AnalysisTaskVO vo = analysisTaskService.getTaskById(userId, id);
        return Result.success(vo);
    }

    @GetMapping("/{id}/stream")
    public SseEmitter streamTask(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long id) {
        analysisTaskService.getTaskById(userId, id);
        return sseEmitterService.createEmitter(id);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteTask(
            @RequestAttribute("currentUserId") Long userId,
            @PathVariable Long id) {
        analysisTaskService.deleteTask(userId, id);
        return Result.success();
    }
}
