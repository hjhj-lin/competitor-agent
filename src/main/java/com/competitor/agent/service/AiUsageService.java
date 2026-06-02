package com.competitor.agent.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.competitor.agent.common.BusinessException;
import com.competitor.agent.entity.AiUsageDaily;
import com.competitor.agent.mapper.AiUsageDailyMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private static final int DAILY_LIMIT = 100;

    private final AiUsageDailyMapper aiUsageDailyMapper;

    public void checkDailyLimit(Long userId) {
        LocalDate today = LocalDate.now();
        AiUsageDaily usage = getTodayUsage(userId, today);

        if (usage != null && usage.getCallCount() >= DAILY_LIMIT) {
            log.warn("[限额拦截] userId={} 今日已调用{}次，超过限额{}", userId, usage.getCallCount(), DAILY_LIMIT);
            throw BusinessException.badRequest("今日AI调用次数已达上限(" + DAILY_LIMIT + "次)，请明天再试");
        }
    }

    public void addUsage(Long userId, int callCount) {
        if (callCount <= 0) return;

        LocalDate today = LocalDate.now();
        AiUsageDaily usage = getTodayUsage(userId, today);

        if (usage == null) {
            usage = new AiUsageDaily();
            usage.setUserId(userId);
            usage.setUsageDate(today);
            usage.setCallCount(callCount);
            aiUsageDailyMapper.insert(usage);
            log.info("[用量记录] userId={} date={} count={}", userId, today, callCount);
        } else {
            usage.setCallCount(usage.getCallCount() + callCount);
            aiUsageDailyMapper.updateById(usage);
            log.info("[用量累加] userId={} date={} count={} total={}", userId, today, callCount, usage.getCallCount());
        }
    }

    public int getTodayCount(Long userId) {
        AiUsageDaily usage = getTodayUsage(userId, LocalDate.now());
        return usage != null ? usage.getCallCount() : 0;
    }

    private AiUsageDaily getTodayUsage(Long userId, LocalDate date) {
        LambdaQueryWrapper<AiUsageDaily> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiUsageDaily::getUserId, userId)
               .eq(AiUsageDaily::getUsageDate, date);
        return aiUsageDailyMapper.selectOne(wrapper);
    }
}
