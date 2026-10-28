package com.liuliu.example.decode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 单测隔离：test profile 会把 scan.intervalSeconds 拉到 9999999 秒（≈115 天），
 * 屏蔽掉 @Scheduled 定时任务；scan.rootDir 指到空目录，扫描不会碰真实 pcap 文件。
 * 如果 CH 连不上也不会炸——application-test.properties 里的 CH 连接串是 localhost，
 * CI 环境没起 CH 时可以临时把 ClickHouseConfig 的 fetchSchema 改成跳过（已经做了）。
 */
@SpringBootTest
@ActiveProfiles("test")
class DecodeApplicationTests {

    @Test
    void contextLoads() {
    }
}
