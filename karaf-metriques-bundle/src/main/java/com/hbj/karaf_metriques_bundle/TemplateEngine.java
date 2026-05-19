package com.hbj.karaf_metriques_bundle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TemplateEngine {

    public static String loadTemplate(String resourcePath) throws IOException {
        InputStream input = TemplateEngine.class.getClassLoader().getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IOException("Template introuvable : " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /**
     * Remplace les placeholders {{key}} ou les blocs conditionnels {{#key}}...{{/key}}
     * Si la clé conditionnelle est présente et vaut true, le bloc est conservé (sans les balises).
     * Sinon, tout le bloc est supprimé.
     */
    public static String processTemplate(String template, Map<String, Object> data) {
        String result = template;
        for (String key : data.keySet()) {
            if (key.startsWith("#")) {
                String condKey = key.substring(1);
                boolean show = data.get(key) != null && Boolean.parseBoolean(data.get(key).toString());
                String startTag = "{{#" + condKey + "}}";
                String endTag = "{{/" + condKey + "}}";
                if (show) {
                    result = result.replace(startTag, "").replace(endTag, "");
                } else {
                    result = result.replaceAll("(?s)" + Pattern.quote(startTag) + ".*?" + Pattern.quote(endTag), "");
                }
            }
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!entry.getKey().startsWith("#")) {
                result = result.replace("{{" + entry.getKey() + "}}",
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }
        return result;
    }
}