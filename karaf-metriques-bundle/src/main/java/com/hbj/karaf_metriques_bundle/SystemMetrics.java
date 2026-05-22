package com.hbj.karaf_metriques_bundle;

import com.sun.management.OperatingSystemMXBean;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.File;
import java.lang.management.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SystemMetrics {

    // ----- Cache pour le delta CPU -----
    private static Map<Long, Long> previousCpuTimes = new HashMap<>();

    // ----- Cache classe -> bundle (pour findBundleByClass) -----
    private static final Map<String, Long> classToBundleCache = new ConcurrentHashMap<>();

    // ----- Métriques globales (inchangées) -----
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
        } catch (Exception e) { /* ignore */ }
        return interfaces;
    }

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

    // ----- Nouveaux collecteurs par bundle -----

    /**
     * Retourne le nombre de threads actifs pour chaque bundle (instantané).
     */
    private static Map<Long, Integer> getBundleActiveThreads(BundleContext bc) {
        Map<Long, Integer> counts = new HashMap<>();
        if (bc == null) return counts;

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, 3);

        for (ThreadInfo info : threadInfos) {
            if (info == null) continue;
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length == 0) continue;
            String className = stack[0].getClassName();
            Bundle owner = findBundleByClass(bc, className);
            if (owner != null) {
                long bid = owner.getBundleId();
                counts.merge(bid, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Retourne le temps CPU cumulé (en ms) pour chaque bundle.
     */
    private static Map<Long, Long> getBundleCpuTime(BundleContext bc) {
        Map<Long, Long> cpuTimes = new HashMap<>();
        if (bc == null) return cpuTimes;

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (!threadMXBean.isThreadCpuTimeSupported()) return cpuTimes;
        threadMXBean.setThreadCpuTimeEnabled(true);

        long[] threadIds = threadMXBean.getAllThreadIds();
        for (long tid : threadIds) {
            long cpuNs = threadMXBean.getThreadCpuTime(tid);
            if (cpuNs <= 0) continue;

            ThreadInfo info = threadMXBean.getThreadInfo(tid, 3);
            if (info == null) continue;
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length == 0) continue;
            String className = stack[0].getClassName();

            Bundle owner = findBundleByClass(bc, className);
            if (owner != null) {
                long bid = owner.getBundleId();
                cpuTimes.merge(bid, cpuNs / 1_000_000L, Long::sum); // ns → ms
            }
        }
        return cpuTimes;
    }

    /**
     * Compte le nombre de classes dans chaque bundle.
     */
    private static Map<Long, Integer> getBundleClassCounts(BundleContext bc) {
        Map<Long, Integer> counts = new HashMap<>();
        if (bc == null) return counts;

        for (Bundle b : bc.getBundles()) {
            BundleWiring wiring = b.adapt(BundleWiring.class);
            if (wiring == null) continue;
            Collection<String> resources = wiring.listResources("/", "*.class",
                    BundleWiring.LISTRESOURCES_RECURSE);
            counts.put(b.getBundleId(), resources != null ? resources.size() : 0);
        }
        return counts;
    }

    /**
     * Trouve le bundle propriétaire d'une classe, avec cache.
     */
    private static Bundle findBundleByClass(BundleContext bc, String className) {
        Long bundleId = classToBundleCache.get(className);
        if (bundleId != null) {
            Bundle b = bc.getBundle(bundleId);
            if (b != null && b.getState() == Bundle.ACTIVE) return b;
        }
        for (Bundle b : bc.getBundles()) {
            try {
                b.loadClass(className);
                classToBundleCache.put(className, b.getBundleId());
                return b;
            } catch (ClassNotFoundException e) { /* ignore */ }
        }
        return null;
    }

    /**
     * Retourne la taille du fichier JAR sur le disque.
     * Cherche d'abord l'URL file:, puis essaie de trouver le JAR dans le cache Karaf.
     */
    private static long getBundleDiskSize(Bundle b) {
        String loc = b.getLocation();
        // 1) URL file:
        if (loc != null && loc.startsWith("file:")) {
            try {
                File f = new File(new URL(loc).toURI());
                return f.length();
            } catch (Exception e) { /* ignore */ }
        }
        // 2) Chercher dans le cache Karaf (data/cache)
        try {
            String karafData = System.getProperty("karaf.data");
            if (karafData != null) {
                File cacheDir = new File(karafData, "cache");
                // Le bundle est stocké dans un répertoire nommé "bundle<id>" ou "bundle<id>_<version>"
                File[] candidates = cacheDir.listFiles((dir, name) ->
                    name.startsWith("bundle" + b.getBundleId()));
                if (candidates != null) {
                    for (File dir : candidates) {
                        // Cherche le JAR dans ce répertoire (souvent bundle.jar)
                        File jar = new File(dir, "bundle.jar");
                        if (jar.exists()) return jar.length();
                        // Ou bien il peut être directement extrait
                        File revision = new File(dir, "revision");
                        if (revision.exists() && revision.isDirectory()) {
                            // Taille totale du répertoire de révision
                            return dirSize(revision);
                        }
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return 0;
    }

    private static long dirSize(File dir) {
        long size = 0;
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    size += dirSize(f);
                }
            }
        } else {
            size = dir.length();
        }
        return size;
    }

    /**
     * Calcule la liste des métriques par bundle avec delta CPU.
     */
    public static List<Map<String, Object>> getBundleMetrics(BundleContext bc) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (bc == null) return list;

        // Collecte des compteurs courants
        Map<Long, Integer> activeThreads = getBundleActiveThreads(bc);
        Map<Long, Integer> classCounts = getBundleClassCounts(bc);
        Map<Long, Long> currentCpuTimes = getBundleCpuTime(bc);

        // Calcul du delta CPU (entre l'appel précédent et maintenant)
        Map<Long, Long> deltaCpu = new HashMap<>();
        for (Map.Entry<Long, Long> entry : currentCpuTimes.entrySet()) {
            long bundleId = entry.getKey();
            long currentCpu = entry.getValue();
            Long previousCpu = previousCpuTimes.get(bundleId);
            long delta = (previousCpu != null) ? (currentCpu - previousCpu) : 0;
            if (delta < 0) delta = 0; // sécurité si le bundle a redémarré
            deltaCpu.put(bundleId, delta);
        }

        // Mise à jour du cache pour le prochain appel
        previousCpuTimes = currentCpuTimes;

        // Construction de la liste pour chaque bundle
        for (Bundle b : bc.getBundles()) {
            Map<String, Object> info = new LinkedHashMap<>();
            long id = b.getBundleId();
            info.put("id", id);
            info.put("symbolicName", b.getSymbolicName() != null ? b.getSymbolicName() : "N/A");
            info.put("state", getStateName(b.getState()));

            // Disque (Mo)
            long diskSize = getBundleDiskSize(b);
            info.put("diskSize", diskSize);

            // Threads actifs
            info.put("activeThreads", activeThreads.getOrDefault(id, 0));

            // Classes chargées
            int classCount = classCounts.getOrDefault(id, 0);
            info.put("classCount", classCount);

            // CPU récent (ms)
            info.put("cpuTime", deltaCpu.getOrDefault(id, 0L));

            // Mémoire estimée (octets) : taille jar * 1.5 + classes * 1 Ko
            long estimatedMemory = (long)(diskSize * 1.5) + (classCount * 1024L);
            info.put("estimatedMemory", estimatedMemory);

            list.add(info);
        }
        return list;
    }

    private static String getStateName(int state) {
        switch (state) {
            case Bundle.ACTIVE:   return "ACTIVE";
            case Bundle.INSTALLED:return "INSTALLED";
            case Bundle.RESOLVED: return "RESOLVED";
            case Bundle.STARTING: return "STARTING";
            case Bundle.STOPPING: return "STOPPING";
            default:              return "UNINSTALLED";
        }
    }

    // ----- Méthodes OSGi globales -----
    private static int getServiceCount(BundleContext bc) {
        try {
            return bc.getAllServiceReferences(null, null).length;
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<Map<String, String>> getConfigurations(ConfigurationAdmin ca) {
        List<Map<String, String>> list = new ArrayList<>();
        if (ca != null) {
            try {
                for (org.osgi.service.cm.Configuration cfg : ca.listConfigurations(null)) {
                    Map<String, String> c = new LinkedHashMap<>();
                    c.put("pid", cfg.getPid());
                    c.put("factoryPid", cfg.getFactoryPid() != null ? cfg.getFactoryPid() : "");
                    list.add(c);
                }
            } catch (Exception e) { /* ignore */ }
        }
        return list;
    }

    public static Map<String, Object> buildFullMetrics(BundleContext bc, ConfigurationAdmin ca) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("cpu", getCpuLoad());
        all.put("memory", getMemory());
        all.put("disk", getDisk());
        all.put("network", getNetwork());

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("threadCount", getThreadCount());
        jvm.put("peakThreadCount", getPeakThreadCount());
        jvm.put("gc", getGarbageCollectors());
        jvm.put("classes", getClassLoading());
        jvm.put("uptime", getJvmUptime());
        all.put("jvm", jvm);

        Map<String, Object> osgi = new LinkedHashMap<>();
        osgi.put("serviceCount", getServiceCount(bc));
        osgi.put("configurations", getConfigurations(ca));
        Map<String, Object> bundles = new LinkedHashMap<>();
        Map<String, Integer> byState = new LinkedHashMap<>();
        for (Bundle b : bc.getBundles()) {
            String state = getStateName(b.getState());
            byState.merge(state, 1, Integer::sum);
        }
        bundles.put("byState", byState);
        bundles.put("total", bc.getBundles().length);
        osgi.put("bundles", bundles);
        all.put("osgi", osgi);

        all.put("bundleMetrics", getBundleMetrics(bc));

        return all;
    }
}