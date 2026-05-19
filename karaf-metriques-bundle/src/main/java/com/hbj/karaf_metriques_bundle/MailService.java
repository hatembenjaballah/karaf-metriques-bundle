package com.hbj.karaf_metriques_bundle;

public interface MailService {
    void sendHtmlMail(String subject, String htmlBody);
}