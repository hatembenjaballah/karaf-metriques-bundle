package com.hbj.karaf_metriques_bundle;

import java.util.Dictionary;

public class AlertConfig {
    private boolean enabled = false;
    private int cpuThreshold = 80;
    private int memoryThreshold = 80;
    private int diskThreshold = 80;
    private String reportFile = "data/metrics-alerts.log";
    private String reportDetail = "compact";

    // Mail via API Mailjet
    private boolean mailEnabled = false;
    private String apiKey;
    private String apiSecret;
    private String fromEmail;
    private String fromName = "Karaf Metrics";
    private String toEmail;
    private String toName = "Admin";
    private String subject = "Karaf Metrics Alert";
    private int mailCooldownMinutes = 30;
    private boolean sendResolutionMail = true;

    // Getters / Setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCpuThreshold() { return cpuThreshold; }
    public void setCpuThreshold(int cpuThreshold) { this.cpuThreshold = cpuThreshold; }
    public int getMemoryThreshold() { return memoryThreshold; }
    public void setMemoryThreshold(int memoryThreshold) { this.memoryThreshold = memoryThreshold; }
    public int getDiskThreshold() { return diskThreshold; }
    public void setDiskThreshold(int diskThreshold) { this.diskThreshold = diskThreshold; }
    public String getReportFile() { return reportFile; }
    public void setReportFile(String reportFile) { this.reportFile = reportFile; }
    public String getReportDetail() { return reportDetail; }
    public void setReportDetail(String reportDetail) { this.reportDetail = reportDetail; }

    public boolean isMailEnabled() { return mailEnabled; }
    public void setMailEnabled(boolean mailEnabled) { this.mailEnabled = mailEnabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getToEmail() { return toEmail; }
    public void setToEmail(String toEmail) { this.toEmail = toEmail; }
    public String getToName() { return toName; }
    public void setToName(String toName) { this.toName = toName; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public int getMailCooldownMinutes() { return mailCooldownMinutes; }
    public void setMailCooldownMinutes(int mailCooldownMinutes) { this.mailCooldownMinutes = mailCooldownMinutes; }
    public boolean isSendResolutionMail() { return sendResolutionMail; }
    public void setSendResolutionMail(boolean sendResolutionMail) { this.sendResolutionMail = sendResolutionMail; }

    public static AlertConfig fromProperties(Dictionary<String, Object> props) {
        AlertConfig config = new AlertConfig();
        if (props == null) return config;

        // enabled
        Object enabled = props.get("enabled");
        if (enabled != null) config.setEnabled(Boolean.parseBoolean(enabled.toString()));

        // cpu.threshold
        Object cpu = props.get("cpu.threshold");
        if (cpu != null) config.setCpuThreshold(Integer.parseInt(cpu.toString()));

        // memory.threshold
        Object mem = props.get("memory.threshold");
        if (mem != null) config.setMemoryThreshold(Integer.parseInt(mem.toString()));

        // disk.threshold
        Object disk = props.get("disk.threshold");
        if (disk != null) config.setDiskThreshold(Integer.parseInt(disk.toString()));

        // report.file
        Object file = props.get("report.file");
        if (file != null) config.setReportFile(file.toString());

        // report.detail
        Object detail = props.get("report.detail");
        if (detail != null) {
            String val = detail.toString().trim().toLowerCase();
            if (val.equals("full") || val.equals("compact")) {
                config.setReportDetail(val);
            }
        }

        // mail.enabled
        Object mailEnabled = props.get("mail.enabled");
        if (mailEnabled != null) config.setMailEnabled(Boolean.parseBoolean(mailEnabled.toString()));

        // Clé API (mail.username) et secret (mail.password)
        config.setApiKey((String) props.get("mail.username"));
        config.setApiSecret((String) props.get("mail.password"));

        // Expéditeur
        config.setFromEmail((String) props.get("mail.from"));
        Object fromName = props.get("mail.from.name");
        config.setFromName(fromName != null ? fromName.toString() : "Karaf Metrics");

        // Destinataire
        config.setToEmail((String) props.get("mail.to"));
        Object toName = props.get("mail.to.name");
        config.setToName(toName != null ? toName.toString() : "Admin");

        // Sujet
        Object subject = props.get("mail.subject");
        config.setSubject(subject != null ? subject.toString() : "Karaf Metrics Alert");

        // Cooldown
        Object cooldown = props.get("mail.cooldown.minutes");
        if (cooldown != null) config.setMailCooldownMinutes(Integer.parseInt(cooldown.toString()));

        // Résolution
        Object sendRes = props.get("mail.send.resolution");
        if (sendRes != null) config.setSendResolutionMail(Boolean.parseBoolean(sendRes.toString()));

        return config;
    }
}

