package com.competitor.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API限流注解 - 基于用户ID的滑动窗口限流
 * 标记在Controller方法上，限制每用户每分钟的最大请求次数
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 每用户每分钟允许的最大请求次数 */
    int permits() default 5;

    /** 限流描述，用于日志和错误提示 */
    String message() default "请求过于频繁，请稍后再试";
}
