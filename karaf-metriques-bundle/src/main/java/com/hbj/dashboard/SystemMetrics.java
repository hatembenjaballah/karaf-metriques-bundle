package com.hbj.dashboard;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.*;
import java.util.*;

public class SystemMetrics {

    public static double getCpuUsagePercent() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        try {
            com.sun.management.OperatingSystemMXBean sunOs =
                (com.sun.management.OperatingSystemMXBean) os;
            return sunOs.getSystemCpuLoad() * 100;
        } catch (Exception e) {
            return -1;
        }
    }

    public static long getUsedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public static long getMaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    public static double getMemoryUsagePercent() {
        long max = getMaxMemory();
        if (max <= 0) return 0;
        return (double) getUsedMemory() / max * 100;
    }

    private static File getFirstRoot() {
        File[] roots = File.listRoots();
        return (roots != null && roots.length > 0) ? roots[0] : null;
    }

    public static long getTotalDisk() {
        File root = getFirstRoot();
        return root != null ? root.getTotalSpace() : 0;
    }

    public static long getUsedDisk() {
        File root = getFirstRoot();
        return root != null ? root.getTotalSpace() - root.getFreeSpace() : 0;
    }

    public static double getDiskUsagePercent() {
        long total = getTotalDisk();
        if (total <= 0) return 0;
        return (double) getUsedDisk() / total * 100;
    }

    public static List<Map<String, Object>> getNetworkInterfaces() {
        List<Map<String, Object>> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface netIf = nets.nextElement();
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", netIf.getName());
                info.put("displayName", netIf.getDisplayName());
                info.put("status", netIf.isUp() ? "UP" : "DOWN");

                byte[] mac = netIf.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) sb.append(":");
                    }
                    info.put("mac", sb.toString());
                } else {
                    info.put("mac", null);
                }

                List<String> addresses = new ArrayList<>();
                Enumeration<InetAddress> inetAddresses = netIf.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress addr = inetAddresses.nextElement();
                    addresses.add(addr.getHostAddress());
                }
                info.put("addresses", addresses);
                interfaces.add(info);
            }
        } catch (Exception ignored) {}
        return interfaces;
    }

    // Sérialisation JSON maison
    public static String toJson(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            sb.append(jsonValue(e.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + escape((String) val) + "\"";
        if (val instanceof Number) return val.toString();
        if (val instanceof Boolean) return val.toString();
        if (val instanceof Map) return toJson((Map) val);
        if (val instanceof Collection) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Collection) val) {
                if (!first) sb.append(",");
                sb.append(jsonValue(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escape(val.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String buildMetricsJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("cpuUsage", String.format("%.1f", getCpuUsagePercent()));
        json.put("memoryUsage", String.format("%.1f", getMemoryUsagePercent()));
        json.put("totalMemory", getMaxMemory());
        json.put("usedMemory", getUsedMemory());
        json.put("diskUsage", String.format("%.1f", getDiskUsagePercent()));
        json.put("totalDisk", getTotalDisk());
        json.put("usedDisk", getUsedDisk());
        json.put("networkInterfaces", getNetworkInterfaces());
        json.put("timestamp", System.currentTimeMillis());
        return toJson(json);
    }
}