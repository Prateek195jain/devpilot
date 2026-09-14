package com.devpilot.ai;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(name="app.ai.mode",havingValue="local",matchIfMissing=true) public class LocalAIProvider implements AIProvider {
    public String source() {
        return "LOCAL_RULES";
    }
    public Review analyze(String diff,String context) {
        var issues=LocalRules.scan(diff).stream().map(h->new Issue(h.type(),h.severity(),h.title(),"Deterministic pattern match; validate this finding in context.",h.file(),h.line(),h.recommendation(),0.65)).toList();
        return new Review("Local rules checked the supplied diff and identified "+issues.size()+" potential issues. Semantic and architectural analysis requires an LLM provider.",issues);
    }
    public String answer(String context,List<Turn> history,String question) {
        return "Local evidence mode — no LLM is configured. I cannot infer an answer to arbitrary code questions. Here is the stored repository context to inspect:\n\n"+context;
    }
    public String document(String kind,String context) {
        return "# "+kind.replace('_',' ')+"\n\n> Evidence draft generated locally. Unobserved architecture and APIs require verification.\n\n"+context;
    }
}
