package com.hbj.karaf_metriques_bundle;

import java.io.File;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.management.OperatingSystemMXBean;

public class SystemMetrics {

    // ------------------------------------------------------------------------
    //  Métriques système existantes (CPU, mémoire, disque, réseau)
    // ------------------------------------------------------------------------
    public static double getCpuLoad() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        return osBean.getSystemCpuLoad() * 100;
    }

    public static Map<String, Object> getMemory() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        long used = memBean.getHeapMemoryUsage().getUsed();
        long max = memBean.getHeapMemoryUsage().getMax();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("used", used / (1024 * 1024));
        mem.put("max", max / (1024 * 1024));
        mem.put("percent", (max > 0) ? (used * 100 / max) : 0);
        return mem;
    }

    public static Map<String, Object> getDisk() {
        File[] roots = File.listRoots();
        Map<String, Object> disk = new LinkedHashMap<>();
        if (roots.length > 0) {
            File root = roots[0];
            long total = root.getTotalSpace();
            long free = root.getFreeSpace();
            long used = total - free;
            disk.put("total", total / (1024 * 1024 * 1024));
            disk.put("used", used / (1024 * 1024 * 1024));
            disk.put("percent", (total > 0) ? (used * 100 / total) : 0);
        }
        return disk;
    }

    public static List<Map<String, String>> getNetwork() {
        List<Map<String, String>> interfaces = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets = java.net.NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface net = nets.nextElement();
                Map<String, String> iface = new LinkedHashMap<>();
                iface.put("name", net.getName());
                iface.put("displayName", net.getDisplayName());
                iface.put("up", net.isUp() ? "UP" : "DOWN");
                List<String> ips = new ArrayList<>();
                for (java.net.InterfaceAddress addr : net.getInterfaceAddresses()) {
                    ips.add(addr.getAddress().getHostAddress());
                }
                iface.put("ips", String.join(", ", ips));
                byte[] mac = net.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
                    }
                    iface.put("mac", sb.toString());
                }
                interfaces.add(iface);
            }
        } catch (Exception e) {
            // ignore
        }
        return interfaces;
    }

    // ------------------------------------------------------------------------
    //  NOUVELLES MÉTRIQUES JVM
    // ------------------------------------------------------------------------
    public static int getThreadCount() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    public static long getPeakThreadCount() {
        return ManagementFactory.getThreadMXBean().getPeakThreadCount();
    }

    public static Map<String, Object> getGarbageCollectors() {
        List<Map<String, Object>> gcs = new ArrayList<>();
        long totalCollectionCount = 0;
        long totalCollectionTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gcInfo = new LinkedHashMap<>();
            gcInfo.put("name", gc.getName());
            gcInfo.put("collectionCount", gc.getCollectionCount());
            gcInfo.put("collectionTime", gc.getCollectionTime());
            gcs.add(gcInfo);
            totalCollectionCount += gc.getCollectionCount();
            totalCollectionTime += gc.getCollectionTime();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collectors", gcs);
        result.put("totalCollectionCount", totalCollectionCount);
        result.put("totalCollectionTime", totalCollectionTime);
        return result;
    }

    public static Map<String, Object> getClassLoading() {
        ClassLoadingMXBean classBean = ManagementFactory.getClassLoadingMXBean();
        Map<String, Object> cl = new LinkedHashMap<>();
        cl.put("loadedCount", classBean.getLoadedClassCount());
        cl.put("totalLoadedCount", classBean.getTotalLoadedClassCount());
        cl.put("unloadedCount", classBean.getUnloadedClassCount());
        return cl;
    }

    public static long getJvmUptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    // ------------------------------------------------------------------------
    //  MÉTRIQUES OSGi/KARAF (nécessitent BundleContext et ConfigurationAdmin)
    // ------------------------------------------------------------------------
    public static Map<String, Object> buildOsgiMetrics(
            org.osgi.framework.BundleContext bundleContext,
            org.osgi.service.cm.ConfigurationAdmin configAdmin) {

        Map<String, Object> osgi = new LinkedHashMap<>();

        // ---- Bundles ----
        if (bundleContext != null) {
            org.osgi.framework.Bundle[] bundles = bundleContext.getBundles();
            int total = bundles.length;
            Map<String, Integer> byState = new LinkedHashMap<>();
            byState.put("ACTIVE", 0);
            byState.put("INSTALLED", 0);
            byState.put("RESOLVED", 0);
            byState.put("STARTING", 0);
            byState.put("STOPPING", 0);
            byState.put("UNINSTALLED", 0);

            List<Map<String, Object>> bundleList = new ArrayList<>();
            for (org.osgi.framework.Bundle b : bundles) {
                String state = getStateName(b.getState());
                byState.merge(state, 1, Integer::sum);
                Map<String, Object> bInfo = new LinkedHashMap<>();
                bInfo.put("id", b.getBundleId());
                bInfo.put("symbolicName", b.getSymbolicName());
                bInfo.put("state", state);
                bundleList.add(bInfo);
            }
            Map<String, Object> bundlesMap = new LinkedHashMap<>();
            bundlesMap.put("total", total);
            bundlesMap.put("byState", byState);
            bundlesMap.put("list", bundleList);
            osgi.put("bundles", bundlesMap);

            // ---- Services ----
            org.osgi.framework.ServiceReference<?>[] refs = null;
            try {
                refs = bundleContext.getAllServiceReferences(null, null);
            } catch (Exception e) { }
            osgi.put("serviceCount", (refs != null) ? refs.length : 0);
        }

        // ---- Configurations Admin ----
        if (configAdmin != null) {
            try {
                org.osgi.service.cm.Configuration[] configs = configAdmin.listConfigurations(null);
                List<Map<String, String>> configList = new ArrayList<>();
                if (configs != null) {
                    for (org.osgi.service.cm.Configuration cfg : configs) {
                        Map<String, String> c = new LinkedHashMap<>();
                        c.put("pid", cfg.getPid());
                        c.put("factoryPid", cfg.getFactoryPid() != null ? cfg.getFactoryPid() : "");
                        if (cfg.getBundleLocation() != null) {
                            c.put("bundleLocation", cfg.getBundleLocation());
                        }
                        configList.add(c);
                    }
                }
                osgi.put("configurations", configList);
            } catch (Exception e) {
                // ignore
            }
        }
        return osgi;
    }

    private static String getStateName(int state) {
        switch (state) {
            case org.osgi.framework.Bundle.ACTIVE:   return "ACTIVE";
            case org.osgi.framework.Bundle.INSTALLED:return "INSTALLED";
            case org.osgi.framework.Bundle.RESOLVED: return "RESOLVED";
            case org.osgi.framework.Bundle.STARTING: return "STARTING";
            case org.osgi.framework.Bundle.STOPPING: return "STOPPING";
            default:                                  return "UNINSTALLED";
        }
    }

    // ------------------------------------------------------------------------
    //  Assemblage complet pour le flux SSE
    // ------------------------------------------------------------------------
    public static Map<String, Object> buildFullMetrics(
            org.osgi.framework.BundleContext bc,
            org.osgi.service.cm.ConfigurationAdmin ca) {

        Map<String, Object> all = new LinkedHashMap<>();
        all.put("cpu", getCpuLoad());
        all.put("memory", getMemory());
        all.put("disk", getDisk());
        all.put("network", getNetwork());

        // JVM
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("threadCount", getThreadCount());
        jvm.put("peakThreadCount", getPeakThreadCount());
        jvm.put("gc", getGarbageCollectors());
        jvm.put("classes", getClassLoading());
        jvm.put("uptime", getJvmUptime());
        all.put("jvm", jvm);

        // OSGi/Karaf
        all.put("osgi", buildOsgiMetrics(bc, ca));

        return all;
    }
}