package com.liuliu.example.decode.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Component
public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    @Value("${scan.rootDir}")
    private String rootDir;

    @Value("${scan.maxDepth:3}")
    private int maxDepth;

    @Value("${file.stableCheck:true}")
    private boolean stableCheck;

    // key = 绝对路径, value = 上一轮扫描时观察到的文件大小
    private final Map<String, Long> lastScanSize = new HashMap<>();

    /**
     * 扫描目录，返回本轮"稳定"的待处理文件。
     *
     * @param forcePassPaths  这些路径会跳过稳定性检查直接返回，
     *                        典型用途：已 done 且 size/mtime 没变的文件。
     */
    public List<Path> scan(Set<String> forcePassPaths) throws IOException {
        List<Path> result = new ArrayList<>();
        Set<String> seenThisRound = new HashSet<>();
        Path root = Paths.get(rootDir);
        if (!Files.isDirectory(root)) {
            return result;
        }

        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                if (!Files.isRegularFile(p)) continue;
                if (!isPcapFile(p)) continue;

                long size;
                try {
                    size = Files.size(p);
                } catch (IOException e) {
                    log.warn("无法 stat 文件，跳过: {} ({})", p, e.getMessage());
                    continue;
                }

                String key = p.toAbsolutePath().toString();
                seenThisRound.add(key);

                // done 文件在 forcePass 里 → 跳过 stableCheck，
                // 让 FileProcessService 走 sameIdentity 快速路径
                if (forcePassPaths != null && forcePassPaths.contains(key)) {
                    result.add(p);
                    continue;
                }

                if (stableCheck) {
                    Long last = lastScanSize.get(key);
                    lastScanSize.put(key, size);
                    if (last == null || last != size) {
                        continue;   // 第一次见到 or 大小变化 → 等下一轮
                    }
                }
                result.add(p);
            }
        }

        // 清理本轮没见到的 key，防止文件被删/改名后 map 只增不减
        lastScanSize.keySet().retainAll(seenThisRound);

        return result;
    }

    /** 签名保留兼容：等同于 scan(null) */
    public List<Path> scan() throws IOException {
        return scan(null);
    }

    private boolean isPcapFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".pcap") || name.endsWith(".pcapng");
    }
}
