package com.liuliu.example.decode.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.concurrent.ExecutorService;

/**
 * Micrometer + ThreadPoolExecutor 指标注册。
 * 暴露到 /actuator/prometheus，Prometheus 抓取后可以做 Grafana 看板：
 *   pcap_packets_total                    — 累计处理包数
 *   pcap_files_processed_total{status=}   — 成功/失败文件数
 *   pcap_scan_duration_seconds            — 每轮扫描耗时
 *   executor_pcap_workers_pool_size       — 线程池活跃线程数
 *   executor_pcap_workers_queue_size      — 线程池队列积压
 */
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    /** 统一的应用标签，所有指标都会带上 */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("application", "decode");
    }

    /** 把 Spring 托管的 pcapExecutor 注册成 Micrometer 可观测的 ExecutorService */
    @Bean
    public ExecutorServiceMetrics pcapExecutorMetrics(ExecutorService pcapExecutor) {
        ExecutorServiceMetrics metrics = new ExecutorServiceMetrics(
                pcapExecutor, "pcap.workers", Collections.emptyList());
        log.info("已注册线程池 Micrometer 指标：executor_pcap_workers_pool_size / queue_size 等");
        return metrics;
    }
}
