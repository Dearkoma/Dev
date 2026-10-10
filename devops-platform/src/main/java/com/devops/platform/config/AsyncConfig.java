package com.devops.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置
 * 替换 Spring 默认的 SimpleAsyncTaskExecutor（无界线程），
 * 使用有界线程池 + 有界队列，防止构建任务打满系统资源。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 构建执行专用线程池
     * - corePoolSize=4:    常驻 4 个工作线程（适合大多数开发环境）
     * - maxPoolSize=8:     峰值最多 8 个构建并发
     * - queueCapacity=20:  最多排队 20 个待执行构建
     * - 拒绝策略: CallerRunsPolicy — 队列满时由调用线程执行（触发构建的 HTTP 线程），
     *   自然形成背压，不会丢任务
     */
    @Bean(name = "buildExecutor")
    public Executor buildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("build-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return buildExecutor();
    }
}
