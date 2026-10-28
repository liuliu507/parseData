package com.liuliu.example.sandbox;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.data_formats.RowBinaryFormatWriter;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import org.pcap4j.core.*;
import org.pcap4j.packet.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class RowBinaryInsert {

    public static class PacketInfo {
        public String srcMac;
        public String dstMac;
        public String srcAddr;
        public String dstAddr;
        public int srcPort;
        public int dstPort;
        public String type;
        public String data;
        public LocalDateTime time;
    }

    private static final String CH_ENDPOINT  = "http://localhost:8123";
    private static final String CH_USER      = "default";
    private static final String CH_PASS      = "123456";
    private static final String CH_DATABASE  = "pcap_db";
    private static final String CH_TABLE     = "parseData";
    private static final int BATCH_SIZE      = 500;

    public static void main(String[] args) {
        String pcapFilePath = "C:\\Users\\liuliu\\Desktop\\parseData\\a_00200_20240902100651_127484700.pcap";

        PcapHandle handle = null;
        try {
            handle = Pcaps.openOffline(pcapFilePath);
            System.out.println("文件打开成功，开始解析...");

            List<PacketInfo> resultList = new ArrayList<>();
            Packet packet;
            int count = 0, skipped = 0;

            while ((packet = handle.getNextPacket()) != null) {
                count++;
                PacketInfo info = parsePacket(packet);
                if (info == null) { skipped++; continue; }

                info.time = handle.getTimestamp().toInstant()
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                        .truncatedTo(ChronoUnit.MICROS);
                resultList.add(info);
            }

            System.out.println("解析完成，共读取 " + count + " 个数据包，跳过 " + skipped + " 个，入库 " + resultList.size() + " 个。");
            saveToClickHouse(resultList);

        } catch (PcapNativeException | NotOpenException e) {
            e.printStackTrace();
        } finally {
            if (handle != null && handle.isOpen()) handle.close();
        }
    }

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

            // 手动构建 TableSchema
            TableSchema schema = new TableSchema();
            schema.addColumn("srcMac", "String");
            schema.addColumn("dstMac", "String");
            schema.addColumn("srcAddr", "String");
            schema.addColumn("dstAddr", "String");
            schema.addColumn("srcPort", "UInt16");
            schema.addColumn("dstPort", "UInt16");
            schema.addColumn("type", "String");
            schema.addColumn("data", "String");
            schema.addColumn("time", "DateTime64(6)");

            // 设置 buffer 大小（解决 Cannot read all data）
            InsertSettings insertSettings = new InsertSettings()
                    .setInputStreamCopyBufferSize(7 * 65500);

            System.out.println("开始写入 ClickHouse（RowBinaryWithDefaults）...");
            int total = resultList.size();
            int written = 0;

            for (int start = 0; start < total; start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, total);
                List<PacketInfo> batch = resultList.subList(start, end);

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                RowBinaryFormatWriter writer = new RowBinaryFormatWriter(
                        buffer, schema, ClickHouseFormat.RowBinaryWithDefaults);

                for (PacketInfo p : batch) {
                    writer.setValue("srcMac", p.srcMac);
                    writer.setValue("dstMac", p.dstMac);
                    writer.setValue("srcAddr", p.srcAddr);
                    writer.setValue("dstAddr", p.dstAddr);
                    writer.setValue("srcPort", p.srcPort);
                    writer.setValue("dstPort", p.dstPort);
                    writer.setValue("type", p.type);
                    writer.setValue("data", p.data);
                    writer.setValue("time", p.time);
                    writer.commitRow();
                }

                ByteArrayInputStream inputStream = new ByteArrayInputStream(buffer.toByteArray());
                try (InsertResponse ignore = client.insert(
                        CH_TABLE, inputStream, ClickHouseFormat.RowBinaryWithDefaults, insertSettings).get()) {
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
    private static PacketInfo parsePacket(Packet packet) {
        PacketInfo info = new PacketInfo();
        info.srcPort = 0; info.dstPort = 0;

        EthernetPacket eth = packet.get(EthernetPacket.class);
        if (eth == null) return null;

        short typeValue = eth.getHeader().getType().value();
        if (typeValue != (short) 0x0800 && typeValue != (short) 0x0888 && typeValue != (short) 0x88D7) return null;

        info.srcMac = eth.getHeader().getSrcAddr().toString();
        info.dstMac = eth.getHeader().getDstAddr().toString();
        info.type = String.format("%04X", typeValue);

        byte[] eRaw = eth.getRawData();

        try {
            IpV4Packet ipV4 = IpV4Packet.newPacket(eRaw, 14, eRaw.length - 14);
            info.srcAddr = ipV4.getHeader().getSrcAddr().getHostAddress();
            info.dstAddr = ipV4.getHeader().getDstAddr().getHostAddress();

            if (ipV4.getHeader().getProtocol().value() != 17) return null;

            UdpPacket udp = ipV4.get(UdpPacket.class);
            if (udp != null) {
                info.srcPort = udp.getHeader().getSrcPort().valueAsInt();
                info.dstPort = udp.getHeader().getDstPort().valueAsInt();
            }
            info.data = bytesToHexString(eRaw);
        } catch (Exception e) { return null; }

        return info;
    }

    private static String bytesToHexString(Packet payload) {
        if (payload == null) return "";
        return bytesToHexString(payload.getRawData());
    }

    private static String bytesToHexString(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
