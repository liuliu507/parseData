package com.liuliu.example.decode.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

// 多文件并行处理的线程池。
@Configuration
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    @Value("${scan.concurrency:4}")
    private int concurrency;

    @Value("${scan.threadPool.queueSize:256}")
    private int queueSize;

    @Value("${scan.threadPool.keepAliveSeconds:60}")
    private int keepAliveSeconds;

    private ExecutorService executor;

    @Bean
    public ExecutorService pcapExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "pcap-worker-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        };

        this.executor = new ThreadPoolExecutor(
                concurrency, concurrency,
                keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        log.info("pcap 线程池已创建：concurrency={}, queueSize={}, keepAlive={}s",
                concurrency, queueSize, keepAliveSeconds);
        return this.executor;
    }

    @PreDestroy
    public void shutdown() {
        if (executor == null) return;
        log.info("正在关闭 pcap 线程池...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("30 秒内未能优雅关闭，强制中断剩余任务");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
