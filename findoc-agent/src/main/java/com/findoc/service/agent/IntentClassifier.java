package com.findoc.service.agent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class IntentClassifier {
    private static final Pattern COMPARE = Pattern.compile("\\b(compare|comparison|differ(?:ence|ent)?|contrast|versus|vs)\\b");
    private static final Pattern REPORT = Pattern.compile("\\b(report|analysis|findings|results)\\b");
    private static final Pattern SUMMARISE = Pattern.compile("\\b(summari[sz](?:e|ed|ing|ation)?|summary|overview|recap)\\b");

    public String classify(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (COMPARE.matcher(normalized).find()) {
            return "COMPARE";
        }
        if (REPORT.matcher(normalized).find()) {
            return "REPORT";
        }
        if (SUMMARISE.matcher(normalized).find()) {
            return "SUMMARISE";
        }
        return "LOOKUP";
    }
}