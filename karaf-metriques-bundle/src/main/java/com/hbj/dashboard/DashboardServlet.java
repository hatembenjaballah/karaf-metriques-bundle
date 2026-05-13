package com.hbj.dashboard;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component(
    service = Servlet.class,
    property = {
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/metriques/dashboard/*",
        HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=("
            + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=default)"
    }
)
public class DashboardServlet extends HttpServlet {

    private String htmlTemplate;
    private String cssContent;
    private String jsContent;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void init() throws ServletException {
        try {
            htmlTemplate = loadResource("/dashboard.html");
            cssContent = loadResource("/css/dashboard.css");
            jsContent = loadResource("/js/dashboard.js");
        } catch (IOException e) {
            throw new ServletException("Impossible de charger les ressources statiques", e);
        }
    }

    private String loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Ressource introuvable : " + path);
            return new String(readAllBytes(is), StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String pathInfo = req.getPathInfo(); // ex: /stream, /css/dashboard.css, /js/dashboard.js

        if (pathInfo == null || "/".equals(pathInfo)) {
            // Page HTML principale
            serveHtml(resp);
        } else if ("/stream".equals(pathInfo)) {
            // Flux SSE
            serveSSE(resp);
        } else if ("/css/dashboard.css".equals(pathInfo)) {
            serveCss(resp);
        } else if ("/js/dashboard.js".equals(pathInfo)) {
            serveJs(resp);
        } else {
            resp.sendError(404);
        }
    }

    private void serveHtml(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html; charset=UTF-8");
        String page = htmlTemplate.replace("${timestamp}",
            new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        resp.getWriter().write(page);
    }

    private void serveCss(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/css; charset=UTF-8");
        resp.getWriter().write(cssContent);
    }

    private void serveJs(HttpServletResponse resp) throws IOException {
        resp.setContentType("application/javascript; charset=UTF-8");
        resp.getWriter().write(jsContent);
    }

    private void serveSSE(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");

        PrintWriter writer = resp.getWriter();
        // Premier envoi immédiat
        writer.write("data: " + SystemMetrics.buildMetricsJson() + "\n\n");
        writer.flush();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                writer.write("data: " + SystemMetrics.buildMetricsJson() + "\n\n");
                writer.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 2, 2, TimeUnit.SECONDS);

        // Maintenir la connexion ouverte
        try {
            while (!writer.checkError()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int n;
        byte[] data = new byte[4096];
        while ((n = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        return buffer.toByteArray();
    }
}