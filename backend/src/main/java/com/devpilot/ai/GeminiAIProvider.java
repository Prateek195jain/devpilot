package com.devpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.ai.mode",havingValue="gemini")
public class GeminiAIProvider extends OpenAICompatibleProvider {
    public GeminiAIProvider(@Value("${app.ai.api-key:}")String key,
                            @Value("${app.ai.base-url:}")String url,
                            @Value("${app.ai.model:}")String model,ObjectMapper mapper,Validator validator) {
        super(key,url.isBlank()?"https://generativelanguage.googleapis.com/v1beta/openai":url,
                model.isBlank()?"gemini-3.8-flash":model,mapper,validator,"gemini");
    }

    public Review analyze(String diff,String context) {
        Review review=super.analyze(diff,context);
        // Match the persistence fingerprint so a finding is counted only once.
        Map<List<String>,Issue> merged=new LinkedHashMap<>();
        for(Issue issue:review.findings())add(merged,issue);
        for(var hit:LocalRules.scan(diff)) {
            add(merged,new Issue(hit.type(),hit.severity(),hit.title(),
                    "Deterministic pattern match; validate this finding in context.",
                    hit.file(),hit.line(),hit.recommendation(),0.65));
        }
        return new Review(review.summary(),merged.values().stream().limit(50).toList());
    }

    private static void add(Map<List<String>,Issue> merged,Issue issue) {
        merged.putIfAbsent(List.of(issue.type(),issue.file(),issue.title().strip().toLowerCase(Locale.ROOT)),issue);
    }

    public String source() {
        return "GEMINI_AND_LOCAL_RULES";
    }
}
