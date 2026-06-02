package com.competitor.agent.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.competitor.agent.common.BusinessException;
import com.competitor.agent.dto.CreateTaskRequest;
import com.competitor.agent.entity.AnalysisTask;
import com.competitor.agent.enums.TaskStatus;
import com.competitor.agent.mapper.AnalysisTaskMapper;
import com.competitor.agent.vo.AnalysisTaskListVO;
import com.competitor.agent.vo.AnalysisTaskVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisTaskService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final PipelineExecutionService pipelineExecutionService;
    private final AiUsageService aiUsageService;

    public AnalysisTaskVO createTask(Long userId, CreateTaskRequest request) {
        // 检查每日AI调用限额
        aiUsageService.checkDailyLimit(userId);

        AnalysisTask task = new AnalysisTask();
        task.setUserId(userId);
        task.setCompanyName(request.getCompanyName());
        task.setStatus(TaskStatus.RUNNING.getCode());
        task.setCurrentAgent("pipeline");
        task.setAiCallCount(0);

        analysisTaskMapper.insert(task);

        log.info("[创建任务] taskId={} userId={} company={}", task.getId(), userId, request.getCompanyName());

        pipelineExecutionService.executePipeline(task.getId(), request.getCompanyName(), userId);

        return toVO(task);
    }

    public IPage<AnalysisTaskListVO> listTasks(Long userId, int pageNum, int pageSize) {
        Page<AnalysisTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getUserId, userId)
               .orderByDesc(AnalysisTask::getCreatedAt);

        Page<AnalysisTask> taskPage = analysisTaskMapper.selectPage(page, wrapper);

        return taskPage.convert(this::toListVO);
    }

    public AnalysisTaskVO getTaskById(Long userId, Long taskId) {
        AnalysisTask task = checkOwnership(userId, taskId);
        return toVO(task);
    }

    public void deleteTask(Long userId, Long taskId) {
        checkOwnership(userId, taskId);
        analysisTaskMapper.deleteById(taskId);
        log.info("[删除任务] taskId={} userId={}", taskId, userId);
    }

    private AnalysisTask checkOwnership(Long userId, Long taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.notFound("任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw BusinessException.unauthorized("无权操作该任务");
        }
        return task;
    }

    private AnalysisTaskVO toVO(AnalysisTask task) {
        AnalysisTaskVO vo = new AnalysisTaskVO();
        vo.setId(task.getId());
        vo.setCompanyName(task.getCompanyName());
        vo.setStatus(task.getStatus());
        vo.setCurrentAgent(task.getCurrentAgent());
        vo.setAiCallCount(task.getAiCallCount());
        vo.setResult(task.getResult());
        vo.setCreatedAt(task.getCreatedAt());
        return vo;
    }

    private AnalysisTaskListVO toListVO(AnalysisTask task) {
        AnalysisTaskListVO vo = new AnalysisTaskListVO();
        vo.setId(task.getId());
        vo.setCompanyName(task.getCompanyName());
        vo.setStatus(task.getStatus());
        vo.setCurrentAgent(task.getCurrentAgent());
        vo.setAiCallCount(task.getAiCallCount());
        vo.setCreatedAt(task.getCreatedAt());
        return vo;
    }
}
