package com.liuliu.example.sandbox;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import org.pcap4j.core.*;
import org.pcap4j.packet.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class clientV2Insert {

    // ======================== 数据结构 ========================
    public static class PacketInfo {
        public String srcMac;        // 源 MAC
        public String dstMac;        // 目的 MAC
        public String srcAddr;       // 源 IP / ARP 地址
        public String dstAddr;       // 目的 IP / ARP 地址
        public int srcPort;          // 源端口（非 TCP/UDP 时为 0）
        public int dstPort;          // 目的端口（非 TCP/UDP 时为 0）
        public String type;          // EtherType，如 "0800"、"0806"、"0888"、"88D7"
        public String data;          // 十六进制字符串
        public LocalDateTime time;   // 绝对时间，如 2024-09-02 10:06:52.436347

        @Override
        public String toString() {
            return "PacketInfo{" +
                    "srcMac='" + srcMac + '\'' +
                    ", dstMac='" + dstMac + '\'' +
                    ", srcAddr='" + srcAddr + '\'' +
                    ", dstAddr='" + dstAddr + '\'' +
                    ", srcPort=" + srcPort +
                    ", dstPort=" + dstPort +
                    ", type='" + type + '\'' +
                    ", data='" + data + '\'' +
                    ", time=" + time +
                    '}';
        }
    }


    // ClickHouse client-v2 连接信息
    private static final String CH_ENDPOINT  = "http://localhost:8123";
    private static final String CH_USER      = "default";
    private static final String CH_PASS      = "123456";
    private static final String CH_DATABASE  = "pcap_db";
    private static final String CH_TABLE     = "parseData";

    // 分批写入大小
    private static final int BATCH_SIZE = 500;

    // 时间格式化，保留 6 位小数
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    // ======================== main ========================
    public static void main(String[] args) {
        String pcapFilePath = "C:\\Users\\liuliu\\Desktop\\parseData\\a_00200_20240902100651_127484700.pcap";

        PcapHandle handle = null;
        try {
            handle = Pcaps.openOffline(pcapFilePath);
            System.out.println("文件打开成功，开始解析...");

            List<PacketInfo> resultList = new ArrayList<>();
            Packet packet;
            int count = 0;
            int skipped = 0;

            while ((packet = handle.getNextPacket()) != null) {
                count++;

                PacketInfo info = parsePacket(packet);

                // parsePacket 返回 null 表示该包需要跳过
                if (info == null) {
                    skipped++;
                    continue;
                }

                // 取当前包的绝对时间戳，转成 LocalDateTime，并截断到微秒（6 位）
                info.time = handle.getTimestamp().toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS);

                resultList.add(info);

                if (count % 1000 == 0) {
                    System.out.println("已解析 " + count + " 个包...");
                }
            }

            System.out.println("解析完成，共读取 " + count + " 个数据包，跳过 " + skipped + " 个，入库 " + resultList.size() + " 个。");

            saveToClickHouse(resultList);

        } catch (PcapNativeException e) {
            System.err.println("打开文件失败！请检查文件路径或 Npcap/libpcap 是否安装。");
            e.printStackTrace();
        } catch (NotOpenException e) {
            e.printStackTrace();
        } finally {
            if (handle != null && handle.isOpen()) {
                handle.close();
            }
        }
    }

    // ======================== 写入 ClickHouse（client-v2 + SQL 拼接） ========================
    private static void saveToClickHouse(List<PacketInfo> resultList) {
        if (resultList.isEmpty()) {
            System.out.println("没有数据需要写入。");
            return;
        }

        try (Client client = new Client.Builder()
                .addEndpoint(CH_ENDPOINT)
                .setUsername(CH_USER)
                .setPassword(CH_PASS)
                .setDefaultDatabase(CH_DATABASE)
                .compressClientRequest(true)
                .setConnectTimeout(30_000)
                .setSocketTimeout(120_000)
                .setMaxConnections(20)
                .setConnectionRequestTimeout(30, ChronoUnit.SECONDS)
                .build()) {

            System.out.println("client-v2 初始化成功。");

            // 注意：不再自动 TRUNCATE parseData 表——这是 sandbox 试验代码，
            // 误跑会清空线上数据。如需清空，请手动在 ClickHouse 执行。

            // 第二步：分批拼接 INSERT SQL 并执行
            System.out.println("开始写入 ClickHouse...");

            int total = resultList.size();
            int written = 0;

            for (int start = 0; start < total; start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, total);
                List<PacketInfo> batch = resultList.subList(start, end);

                String sql = buildInsertSql(batch);
//                client.query(sql).get(); // 把sql发给Clickhouse，并等它执行完成
                try (QueryResponse ignored = client.query(sql).get()) {
                    written += batch.size();
                    System.out.println("已写入 " + written + " / " + total + " 条...");
                }
            }

            System.out.println("写入完成，共 " + written + " 条数据。");

        } catch (Exception e) {
            System.err.println("写入 ClickHouse 失败：");
            e.printStackTrace();
        }
    }

    // ======================== 构建批量 INSERT SQL ========================
    private static String buildInsertSql(List<PacketInfo> batch) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(CH_TABLE)
                .append(" (srcMac, dstMac, srcAddr, dstAddr, srcPort, dstPort, type, data, time) VALUES ");

        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sql.append(", ");

            PacketInfo p = batch.get(i);
            sql.append("('")
                    .append(escape(p.srcMac)).append("','")
                    .append(escape(p.dstMac)).append("','")
                    .append(escape(p.srcAddr)).append("','")
                    .append(escape(p.dstAddr)).append("',")
                    .append(p.srcPort).append(",")
                    .append(p.dstPort).append(",'")
                    .append(escape(p.type)).append("','")
                    .append(escape(p.data)).append("','")
                    .append(formatTime(p.time)).append("')");
        }

        return sql.toString();
    }

    // ======================== 解析单个包 ========================
    private static PacketInfo parsePacket(Packet packet) {
        PacketInfo info = new PacketInfo();
        info.srcPort = 0;
        info.dstPort = 0;

        EthernetPacket eth = packet.get(EthernetPacket.class);
        if (eth == null) return null;

        short typeValue = eth.getHeader().getType().value();
        if (typeValue != (short) 0x0800 && typeValue != (short) 0x0888 && typeValue != (short) 0x88D7) {
            return null;
        }

        info.srcMac = eth.getHeader().getSrcAddr().toString();
        info.dstMac = eth.getHeader().getDstAddr().toString();
        info.type = String.format("%04X", typeValue);

        byte[] eRaw = eth.getRawData();

        try {
            // 跳过 14 字节以太网头
            IpV4Packet ipV4 = IpV4Packet.newPacket(eRaw, 14, eRaw.length - 14);

            info.srcAddr = ipV4.getHeader().getSrcAddr().getHostAddress();
            info.dstAddr = ipV4.getHeader().getDstAddr().getHostAddress();

            // 只要 UDP
            if (ipV4.getHeader().getProtocol().value() != 17) {
                return null;
            }

            UdpPacket udp = ipV4.get(UdpPacket.class);
            if (udp != null) {
                info.srcPort = udp.getHeader().getSrcPort().valueAsInt();
                info.dstPort = udp.getHeader().getDstPort().valueAsInt();
            }
            info.data = bytesToHexString(eRaw);
        } catch (Exception e) {
            return null;
        }

        return info;
    }

    // ======================== 工具方法 ========================

    // 转成十六进制字符串
    private static String bytesToHexString(Packet payload) {
        if (payload == null) return "";
        return bytesToHexString(payload.getRawData());
    }

    private static String bytesToHexString(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    // 转义，防止sql语句本身还有\\与'
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "''");
    }

    // 时间格式化，保留 6 位小数
    private static String formatTime(LocalDateTime t) {
        if (t == null) return "1970-01-01 00:00:00.000000";
        return t.format(TIME_FMT);
    }
}
