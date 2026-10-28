package com.liuliu.example.decode.parse;

import com.liuliu.example.decode.model.PacketInfo;
import org.pcap4j.core.*;
import org.pcap4j.packet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class PcapParser {

    private static final Logger log = LoggerFactory.getLogger(PcapParser.class);

    @Value("${batch.size:500}")
    private int batchSize;

    /**
     * 解析 pcap/pcapng 文件，分批回调 consumer。
     * 内部会把非 ASCII 路径的文件先拷到临时目录再打开（Windows 下 pcap4j JNA 走 ANSI），
     * 时间戳按 UTC 解释避免时区偏移。
     */
    public long parseFile(String filePath, Consumer<List<PacketInfo>> consumer)
            throws PcapNativeException, NotOpenException, IOException {

        Path src = Paths.get(filePath);

        // pcap4j 在 Windows 下 JNA → wpcap.dll 走 ANSI 字符串，
        // 中文路径会乱码打不开，所以非 ASCII 路径先拷一份 ASCII 临时副本
        Path staging = null;
        boolean isAsciiPath = src.toString().matches("[\\x00-\\x7F]+");
        if (!isAsciiPath) {
            String ext = filePath.toLowerCase().endsWith(".pcapng") ? ".pcapng" : ".pcap";
            staging = Files.createTempFile("pcap4j_", ext);
            Files.copy(src, staging, StandardCopyOption.REPLACE_EXISTING);
            log.debug("中文路径中转：{} -> {}", filePath, staging);
        }
        Path target = staging == null ? src : staging;

        PcapHandle handle = Pcaps.openOffline(target.toString());
        long total = 0;
        long skipped = 0;
        long read = 0;
        try {
            List<PacketInfo> batch = new ArrayList<>(batchSize);
            Packet packet;
            while ((packet = handle.getNextPacket()) != null) {
                read++;
                PacketInfo info = parsePacket(packet);
                if (info == null) {
                    skipped++;
                    continue;
                }

                // pcap 时间戳是 UTC epoch，必须按 UTC 转 LocalDateTime，
                // 否则 Asia/Shanghai 等非 UTC 机器会把时间整体偏移 8 小时
                info.time = handle.getTimestamp().toInstant()
                        .atZone(ZoneId.of("UTC"))
                        .toLocalDateTime()
                        .truncatedTo(ChronoUnit.MICROS);
                batch.add(info);

                if (batch.size() >= batchSize) {
                    consumer.accept(batch);
                    total += batch.size();
                    batch = new ArrayList<>(batchSize);
                }
            }
            if (!batch.isEmpty()) {
                consumer.accept(batch);
                total += batch.size();
            }
            log.info("解析统计：读取 {} 包 / 入库 {} / 跳过 {}，文件 {}", read, total, skipped, filePath);
            return total;
        } finally {
            if (handle.isOpen()) handle.close();
            if (staging != null) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private PacketInfo parsePacket(Packet packet) {
        PacketInfo info = new PacketInfo();
        info.srcPort = 0; info.dstPort = 0;

        EthernetPacket eth = packet.get(EthernetPacket.class);
        if (eth == null) return null;

        short typeValue = eth.getHeader().getType().value();
        if (typeValue != (short) 0x0800 && typeValue != (short) 0x0888
                && typeValue != (short) 0x88D7) return null;

        info.srcMac = eth.getHeader().getSrcAddr().toString();
        info.dstMac = eth.getHeader().getDstAddr().toString();
        info.type = String.format("%04X", typeValue);

        try {
            IpV4Packet ipV4 = IpV4Packet.newPacket(
                    eth.getRawData(), 14, eth.getRawData().length - 14);
            info.srcAddr = ipV4.getHeader().getSrcAddr().getHostAddress();
            info.dstAddr = ipV4.getHeader().getDstAddr().getHostAddress();

            if (ipV4.getHeader().getProtocol().value() != 17) return null;

            UdpPacket udp = ipV4.get(UdpPacket.class);
            if (udp != null) {
                info.srcPort = udp.getHeader().getSrcPort().valueAsInt();
                info.dstPort = udp.getHeader().getDstPort().valueAsInt();
            }
            // 保持原逻辑：data 存整个以太网帧的十六进制
            info.data = bytesToHexString(eth.getRawData());
        } catch (Exception e) {
            return null;
        }
        return info;
    }

    private String bytesToHexString(Packet payload) {
        if (payload == null) return "";
        return bytesToHexString(payload.getRawData());
    }

    private String bytesToHexString(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
