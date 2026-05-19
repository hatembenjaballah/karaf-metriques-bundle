package com.hbj.karaf_metriques_bundle;

import com.google.gson.Gson;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Component(
    service = Servlet.class,
    property = {
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/metriques/dashboard/*",
        HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=default)"
    },
    configurationPid = "com.hbj.karaf.metrics.alert"
)
public class DashboardServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(DashboardServlet.class);
    private final Gson gson = new Gson();
    private final ReentrantLock fileLock = new ReentrantLock();

    private BundleContext bundleContext;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile ConfigurationAdmin configurationAdmin;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile MailService mailService;

    private AlertConfig alertConfig = new AlertConfig();

    private boolean alertActive = false;
    private long lastMailSentTime = 0;

    @Activate
    protected void activate(BundleContext context, Map<String, Object> properties) {
        this.bundleContext = context;
        updateConfiguration(properties);
    }

    @Modified
    protected void modified(Map<String, Object> properties) {
        updateConfiguration(properties);
    }

    private void updateConfiguration(Map<String, Object> props) {
        if (props != null) {
            alertConfig = AlertConfig.fromProperties(new java.util.Hashtable<>((Map) props));
            LOG.info("Alert configuration updated: enabled={}, cpu={}, memory={}, disk={}, reportFile={}, reportDetail={}, mail={}, cooldown={}min, resolution={}",
                    alertConfig.isEnabled(), alertConfig.getCpuThreshold(),
                    alertConfig.getMemoryThreshold(), alertConfig.getDiskThreshold(),
                    alertConfig.getReportFile(), alertConfig.getReportDetail(),
                    alertConfig.isMailEnabled(), alertConfig.getMailCooldownMinutes(),
                    alertConfig.isSendResolutionMail());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String path = req.getPathInfo();
        if (path == null || path.equals("/")) {
            path = "/dashboard.html";
        }

        switch (path) {
            case "/dashboard.html":
                serveHtml(resp);
                break;
            case "/stream":
                streamMetrics(resp);
                break;
            default:
                if (path.startsWith("/js/") || path.startsWith("/css/")) {
                    serveStaticResource(path, resp);
                } else {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
        }
    }

    private void serveHtml(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html; charset=UTF-8");
        InputStream input = getClass().getClassLoader().getResourceAsStream("dashboard.html");
        if (input == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try (OutputStream out = resp.getOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = input.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        } finally {
            input.close();
        }
    }

    private void streamMetrics(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");

        PrintWriter writer = resp.getWriter();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Map<String, Object> metrics = SystemMetrics.buildFullMetrics(
                        bundleContext,
                        configurationAdmin
                );
                String json = gson.toJson(metrics);
                writer.write("data: " + json + "\n\n");
                writer.flush();

                checkAndAlert(metrics);

                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            writer.close();
        }
    }

    private void checkAndAlert(Map<String, Object> metrics) {
        if (!alertConfig.isEnabled()) return;

        boolean cpuAlert = false, memAlert = false, diskAlert = false;
        double cpuValue = 0, memPercent = 0, diskPercent = 0;

        if (metrics.containsKey("cpu")) {
            cpuValue = ((Number) metrics.get("cpu")).doubleValue();
            if (cpuValue > alertConfig.getCpuThreshold()) cpuAlert = true;
        }
        if (metrics.containsKey("memory")) {
            Map<String, Object> mem = (Map<String, Object>) metrics.get("memory");
            memPercent = ((Number) mem.get("percent")).doubleValue();
            if (memPercent > alertConfig.getMemoryThreshold()) memAlert = true;
        }
        if (metrics.containsKey("disk")) {
            Map<String, Object> disk = (Map<String, Object>) metrics.get("disk");
            diskPercent = ((Number) disk.get("percent")).doubleValue();
            if (diskPercent > alertConfig.getDiskThreshold()) diskAlert = true;
        }

        boolean currentAlert = cpuAlert || memAlert || diskAlert;

        if (currentAlert) {
            if (!alertActive) {
                sendAlert(metrics, cpuAlert, memAlert, diskAlert);
                alertActive = true;
                lastMailSentTime = System.currentTimeMillis();
            } else if (alertConfig.getMailCooldownMinutes() > 0) {
                long cooldownMs = alertConfig.getMailCooldownMinutes() * 60_000L;
                if (System.currentTimeMillis() - lastMailSentTime > cooldownMs) {
                    sendAlert(metrics, cpuAlert, memAlert, diskAlert);
                    lastMailSentTime = System.currentTimeMillis();
                }
            }
        } else {
            if (alertActive && alertConfig.isSendResolutionMail()) {
                sendResolution(metrics);
            }
            alertActive = false;
        }
    }

    private void sendAlert(Map<String, Object> metrics, boolean cpuAlert, boolean memAlert, boolean diskAlert) {
        // Rapport texte pour le fichier
        String textReport;
        if ("full".equalsIgnoreCase(alertConfig.getReportDetail())) {
            textReport = buildFullReport(metrics, cpuAlert, memAlert, diskAlert);
        } else {
            textReport = buildCompactReport(metrics, cpuAlert, memAlert, diskAlert);
        }
        writeAlertReport(textReport);

        // Email HTML via template
        if (mailService != null && alertConfig.isMailEnabled()) {
            try {
                String htmlBody = buildAlertHtml(metrics, cpuAlert, memAlert, diskAlert);
                mailService.sendHtmlMail("ALERTE Karaf Metrics", htmlBody);
            } catch (Exception e) {
                LOG.error("Erreur lors de l'envoi de l'email d'alerte", e);
            }
        }
    }

    private void sendResolution(Map<String, Object> metrics) {
        // Rapport texte
        StringBuilder textReport = new StringBuilder();
        textReport.append("RÉSOLUTION D'ALERTE\n-------------------\n");
        textReport.append("Toutes les métriques sont revenues sous les seuils.\n");
        textReport.append("Dernier état : ").append(metrics.getOrDefault("cpu", "?")).append("% CPU, ");
        Map<String, Object> mem = (Map<String, Object>) metrics.get("memory");
        textReport.append(mem != null ? mem.get("percent") : "?").append("% mémoire, ");
        Map<String, Object> disk = (Map<String, Object>) metrics.get("disk");
        textReport.append(disk != null ? disk.get("percent") : "?").append("% disque");
        writeAlertReport(textReport.toString());

        if (mailService != null && alertConfig.isMailEnabled() && alertConfig.isSendResolutionMail()) {
            try {
                String htmlBody = buildResolutionHtml(metrics);
                mailService.sendHtmlMail("RÉSOLUTION Karaf Metrics", htmlBody);
            } catch (Exception e) {
                LOG.error("Erreur lors de l'envoi de l'email de résolution", e);
            }
        }
    }

    private String buildAlertHtml(Map<String, Object> metrics, boolean cpuAlert, boolean memAlert, boolean diskAlert) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("#cpuAlert", cpuAlert);
        if (metrics.containsKey("cpu")) {
            data.put("cpuValue", String.format("%.1f", ((Number) metrics.get("cpu")).doubleValue()));
        }
        data.put("cpuThreshold", alertConfig.getCpuThreshold());

        data.put("#memAlert", memAlert);
        if (metrics.containsKey("memory")) {
            Map<String, Object> mem = (Map<String, Object>) metrics.get("memory");
            data.put("memPercent", String.format("%.1f", ((Number) mem.get("percent")).doubleValue()));
            data.put("memUsed", mem.get("used").toString());
            data.put("memMax", mem.get("max").toString());
        }
        data.put("memoryThreshold", alertConfig.getMemoryThreshold());

        data.put("#diskAlert", diskAlert);
        if (metrics.containsKey("disk")) {
            Map<String, Object> disk = (Map<String, Object>) metrics.get("disk");
            data.put("diskPercent", String.format("%.1f", ((Number) disk.get("percent")).doubleValue()));
            data.put("diskUsed", disk.get("used").toString());
            data.put("diskTotal", disk.get("total").toString());
        }
        data.put("diskThreshold", alertConfig.getDiskThreshold());

        if (metrics.containsKey("jvm")) {
            Map<String, Object> jvm = (Map<String, Object>) metrics.get("jvm");
            data.put("threadCount", jvm.getOrDefault("threadCount", "?"));
            data.put("peakThreadCount", jvm.getOrDefault("peakThreadCount", "?"));
            long uptimeMs = ((Number) jvm.getOrDefault("uptime", 0L)).longValue();
            long hours = (uptimeMs / 1000) / 3600;
            long minutes = ((uptimeMs / 1000) % 3600) / 60;
            data.put("jvmUptime", hours + "h " + minutes + "m");
            Map<String, Object> gc = (Map<String, Object>) jvm.get("gc");
            data.put("gcCount", gc != null ? gc.getOrDefault("totalCollectionCount", "?") : "?");
            data.put("gcTime", gc != null ? gc.getOrDefault("totalCollectionTime", "?") : "?");
        }

        if (metrics.containsKey("osgi")) {
            Map<String, Object> osgi = (Map<String, Object>) metrics.get("osgi");
            Map<String, Object> bundles = (Map<String, Object>) osgi.get("bundles");
            if (bundles != null) {
                Map<String, Integer> byState = (Map<String, Integer>) bundles.get("byState");
                data.put("activeBundles", byState.getOrDefault("ACTIVE", 0));
                data.put("totalBundles", bundles.getOrDefault("total", 0));
            }
        }

        data.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        String template = TemplateEngine.loadTemplate("alert-template.html");
        return TemplateEngine.processTemplate(template, data);
    }

    private String buildResolutionHtml(Map<String, Object> metrics) throws IOException {
        Map<String, Object> data = new HashMap<>();
        data.put("cpu", metrics.getOrDefault("cpu", "?"));
        Map<String, Object> mem = (Map<String, Object>) metrics.get("memory");
        data.put("memory", mem != null ? mem.get("percent") : "?");
        Map<String, Object> disk = (Map<String, Object>) metrics.get("disk");
        data.put("disk", disk != null ? disk.get("percent") : "?");
        data.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        String template = TemplateEngine.loadTemplate("resolution-template.html");
        return TemplateEngine.processTemplate(template, data);
    }

    private String buildCompactReport(Map<String, Object> metrics, boolean cpuAlert, boolean memAlert, boolean diskAlert) {
        StringBuilder report = new StringBuilder();
        report.append("ALERTE METRIQUES KARAF\n");
        report.append("----------------------\n");

        if (cpuAlert) {
            double cpuValue = ((Number) metrics.get("cpu")).doubleValue();
            report.append(String.format("[CPU] %.1f%% (seuil: %d%%)\n", cpuValue, alertConfig.getCpuThreshold()));
        }
        if (memAlert) {
            Map<String, Object> mem = (Map<String, Object>) metrics.get("memory");
            double memPercent = ((Number) mem.get("percent")).doubleValue();
            report.append(String.format("[Mémoire] %.1f%% utilisé (%s Mo / %s Mo) (seuil: %d%%)\n",
                    memPercent, mem.get("used"), mem.get("max"), alertConfig.getMemoryThreshold()));
        }
        if (diskAlert) {
            Map<String, Object> disk = (Map<String, Object>) metrics.get("disk");
            double diskPercent = ((Number) disk.get("percent")).doubleValue();
            report.append(String.format("[Disque] %.1f%% utilisé (%s Go / %s Go) (seuil: %d%%)\n",
                    diskPercent, disk.get("used"), disk.get("total"), alertConfig.getDiskThreshold()));
        }

        report.append("\n--- État résumé ---\n");

        if (metrics.containsKey("jvm")) {
            Map<String, Object> jvm = (Map<String, Object>) metrics.get("jvm");
            report.append(String.format("Threads actifs: %s (pic: %s)\n",
                    jvm.getOrDefault("threadCount", "?"), jvm.getOrDefault("peakThreadCount", "?")));
            long uptimeMs = ((Number) jvm.getOrDefault("uptime", 0L)).longValue();
            long hours = (uptimeMs / 1000) / 3600;
            long minutes = ((uptimeMs / 1000) % 3600) / 60;
            report.append(String.format("Uptime JVM: %dh %02dm\n", hours, minutes));
        }
        if (metrics.containsKey("osgi")) {
            Map<String, Object> osgi = (Map<String, Object>) metrics.get("osgi");
            Map<String, Object> bundles = (Map<String, Object>) osgi.get("bundles");
            if (bundles != null) {
                Map<String, Integer> byState = (Map<String, Integer>) bundles.get("byState");
                int active = byState.getOrDefault("ACTIVE", 0);
                int total = (Integer) bundles.getOrDefault("total", 0);
                report.append(String.format("Bundles: %d actifs / %d total\n", active, total));
            }
        }
        if (metrics.containsKey("jvm")) {
            Map<String, Object> jvm = (Map<String, Object>) metrics.get("jvm");
            Map<String, Object> gc = (Map<String, Object>) jvm.get("gc");
            if (gc != null) {
                report.append(String.format("GC: %s collectes, %s ms cumulés\n",
                        gc.getOrDefault("totalCollectionCount", "?"),
                        gc.getOrDefault("totalCollectionTime", "?")));
            }
        }

        return report.toString();
    }

    private String buildFullReport(Map<String, Object> metrics, boolean cpuAlert, boolean memAlert, boolean diskAlert) {
       StringBuilder report = new StringBuilder();
        report.append("=== ALERTE KARAF (rapport complet) ===\n");

        if (cpuAlert) {
            double cpuValue = ((Number) metrics.get("cpu")).doubleValue();
            report.append(String.format("[CPU] %.1f%% (seuil: %d%%)\n", cpuValue, alertConfig.getCpuThreshold()));
        }
        if (memAlert) {
            Map<String, Object> mem = (Map<String, Object>) metrics.get("memory");
            double memPercent = ((Number) mem.get("percent")).doubleValue();
            report.append(String.format("[Mémoire] %.1f%% utilisé (%s Mo / %s Mo) (seuil: %d%%)\n",
                    memPercent, mem.get("used"), mem.get("max"), alertConfig.getMemoryThreshold()));
        }
        if (diskAlert) {
            Map<String, Object> disk = (Map<String, Object>) metrics.get("disk");
            double diskPercent = ((Number) disk.get("percent")).doubleValue();
            report.append(String.format("[Disque] %.1f%% utilisé (%s Go / %s Go) (seuil: %d%%)\n",
                    diskPercent, disk.get("used"), disk.get("total"), alertConfig.getDiskThreshold()));
        }

        report.append("\n--- Détails complets ---\n");
        report.append("Métriques JVM : ").append(metrics.getOrDefault("jvm", "N/A")).append("\n");
        report.append("Métriques OSGi : ").append(metrics.getOrDefault("osgi", "N/A")).append("\n");

        return report.toString();
    }

    private void writeAlertReport(String message) {
        String filePath = alertConfig.getReportFile();
        if (!Paths.get(filePath).isAbsolute()) {
            String karafData = System.getProperty("karaf.data");
            if (karafData == null) {
                karafData = System.getProperty("karaf.base", ".");
            }
            filePath = Paths.get(karafData, filePath).toString();
        }

        fileLock.lock();
        try {
            Files.createDirectories(Paths.get(filePath).getParent());
            try (FileWriter fw = new FileWriter(filePath, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                pw.println("[" + timestamp + "] " + message);
                pw.flush();
            }
        } catch (IOException e) {
            LOG.error("Impossible d'écrire dans le fichier d'alertes", e);
        } finally {
            fileLock.unlock();
        }
    }

    private void serveStaticResource(String path, HttpServletResponse resp) throws IOException {
        String resourcePath = path.substring(1);
        InputStream input = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (input == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (path.endsWith(".css")) {
            resp.setContentType("text/css; charset=UTF-8");
        } else if (path.endsWith(".js")) {
            resp.setContentType("application/javascript; charset=UTF-8");
        } else {
            resp.setContentType("application/octet-stream");
        }
        OutputStream out = resp.getOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = input.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        input.close();
    }
}