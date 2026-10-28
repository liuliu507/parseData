package com.liuliu.example.decode.service;

import com.liuliu.example.decode.model.FileMeta;
import com.liuliu.example.decode.parse.PcapParser;
import com.liuliu.example.decode.scan.FileScanner;
import com.liuliu.example.decode.store.ClickHouseClient;
import com.liuliu.example.decode.store.DataWriter;
import com.liuliu.example.decode.store.MetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FileProcessService {

    private static final Logger log = LoggerFactory.getLogger(FileProcessService.class);

    private final FileScanner scanner;
    private final PcapParser parser;
    private final ClickHouseClient ch;
    private final DataWriter dataWriter;
    private final MetaStore metaStore;

    @Value("${ch.table}")
    private String table;

    public FileProcessService(FileScanner scanner, PcapParser parser,
                              ClickHouseClient ch, DataWriter dataWriter,
                              MetaStore metaStore) {
        this.scanner = scanner;
        this.parser = parser;
        this.ch = ch;
        this.dataWriter = dataWriter;
        this.metaStore = metaStore;
    }

    private int retryMax() {
        return metaStore.getRetryMax();
    }

    @Scheduled(fixedDelayString = "${scan.intervalSeconds:300}000",
            initialDelayString = "5000")
    public void scanAndProcess() {
        log.info("===== 开始扫描 =====");
        try {
            Set<String> donePaths = metaStore.listDonePaths();
            List<Path> files = scanner.scan(donePaths);
            log.info("本轮待处理文件数: {}", files.size());
            for (Path p : files) {
                processOne(p);
            }
        } catch (Exception e) {
            log.error("扫描处理异常", e);
        }
        log.info("===== 扫描结束 =====");
    }

    private void processOne(Path path) {
        String filePath = path.toAbsolutePath().toString();
        try {
            LocalDateTime mtime = null;
            long size;
            try {
                size = Files.size(path);
                // 必须截断到微秒：ClickHouse DateTime64(6) 存储时会截断，
                // Windows 文件 mtime 精度为 100ns，不截断会导致每轮比对都不一致、重复处理
                mtime = Files.readAttributes(path, BasicFileAttributes.class)
                        .lastModifiedTime().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDateTime()
                        .truncatedTo(ChronoUnit.MICROS);
            } catch (IOException e) {
                // stat 失败也要写 failed 元数据，否则每轮重试每轮 ERROR 永不收敛
                log.error("无法读取文件属性，标记为 stat_failed: {} ({})", filePath, e.getMessage());
                try {
                    metaStore.markFailed(filePath, 0, LocalDateTime.now(), 1,
                            "stat failed: " + e.getMessage());
                } catch (Exception ex) {
                    log.error("写 stat_failed 元数据也失败了", ex);
                }
                return;
            }

            FileMeta meta = metaStore.findLatest(filePath);

            if (meta != null) {
                if ("permanently_failed".equals(meta.status)) {
                    log.info("跳过（永久失败）: {}", filePath);
                    return;
                }
                if ("done".equals(meta.status) && meta.sameIdentity(filePath, size, mtime)) {
                    log.info("跳过（已处理且未变化）: {}", filePath);
                    return;
                }
            }

            long retryCount = (meta == null) ? 0 : meta.retryCount;
            if (retryCount >= retryMax()) {
                log.warn("跳过（超过重试上限 {}）: {}", retryMax(), filePath);
                return;
            }

            log.info("开始处理: {}（第 {} 次尝试）", filePath, retryCount + 1);

            // 先把元数据翻成 processing 再删旧数据：避免 DELETE 成功但 markProcessing
            // 抛异常后元数据仍为 done，下一轮 sameIdentity 命中永久跳过
            metaStore.markProcessing(filePath, size, mtime);

            String delSql = "DELETE FROM " + table
                    + " WHERE source_file = '" + ClickHouseClient.escape(filePath) + "'";
            ch.execute(delSql);

            AtomicLong count = new AtomicLong(0);
            try {
                long total = parser.parseFile(filePath, batch -> {
                    try {
                        dataWriter.writeBatch(batch, filePath);
                        count.addAndGet(batch.size());
                    } catch (Exception e) {
                        throw new RuntimeException("写入失败", e);
                    }
                });

                metaStore.markDone(filePath, size, mtime, total);
                log.info("处理完成: {}，入库 {} 条", filePath, total);

            } catch (Exception e) {
                long newRetry = retryCount + 1;
                metaStore.markFailed(filePath, size, mtime, newRetry, e.getMessage());
                log.error("处理失败: {}（retry={}）", filePath, newRetry, e);
            }

        } catch (Exception e) {
            log.error("处理文件异常: {}", filePath, e);
        }
    }
}
