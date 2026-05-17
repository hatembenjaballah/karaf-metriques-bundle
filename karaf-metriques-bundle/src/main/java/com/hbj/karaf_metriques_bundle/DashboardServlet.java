package com.hbj.karaf_metriques_bundle;

import com.google.gson.Gson;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Map;

@Component(
    service = Servlet.class,
    property = {
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/metriques/dashboard/*",
        HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT + "=(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=default)"
    }
)
public class DashboardServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private final Gson gson = new Gson();

    private BundleContext bundleContext;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile ConfigurationAdmin configurationAdmin;

    @Activate
    protected void activate(BundleContext context) {
        this.bundleContext = context;
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

    // Nouvelle méthode : lit le fichier dashboard.html depuis le classpath
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

    // Méthode SSE inchangée (avec boucle bloquante)
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

                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            writer.close();
        }
    }

    // Sert les fichiers statiques depuis le classpath (css/, js/)
    private void serveStaticResource(String path, HttpServletResponse resp) throws IOException {
        // path commence par "/", on retire le premier caractère
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