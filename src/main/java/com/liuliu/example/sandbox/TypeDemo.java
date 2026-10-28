package com.liuliu.example.sandbox;

import org.pcap4j.core.*;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.namednumber.EtherType;

import java.util.HashMap;
import java.util.Map;

public class TypeDemo {

    public static void main(String[] args) {
        String pcapFilePath = "C:\\Users\\liuliu\\Desktop\\parseData\\a_00200_20240902100651_127484700.pcap";

        PcapHandle handle = null;
        try {
            handle = Pcaps.openOffline(pcapFilePath);
            System.out.println("文件打开成功，开始统计 EtherType...");

            Map<String, Integer> typeCount = new HashMap<>();
            Packet packet;
            int total = 0;

            while ((packet = handle.getNextPacket()) != null) {
                total++;

                // 只取以太网层，不递归往下钻
                EthernetPacket eth = packet.get(EthernetPacket.class);
                if (eth != null) {
                    EtherType type = eth.getHeader().getType();
                    String key = String.format("0x%04X", type.value());
                    typeCount.put(key, typeCount.getOrDefault(key, 0) + 1);
                } else {
                    typeCount.put("非以太网帧", typeCount.getOrDefault("非以太网帧", 0) + 1);
                }
            }

            System.out.println("解析完成，共读取 " + total + " 个数据包。");
            System.out.println("========= EtherType 统计结果 =========");

            typeCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> System.out.printf("%-12s 出现 %d 次%n", e.getKey(), e.getValue()));

            System.out.println("=====================================");
            System.out.println("总共 " + typeCount.size() + " 种 EtherType。");

        } catch (PcapNativeException e) {
            System.err.println("打开文件失败！");
            e.printStackTrace();
        } catch (NotOpenException e) {
            e.printStackTrace();
        } finally {
            if (handle != null && handle.isOpen()) {
                handle.close();
            }
        }
    }
}
