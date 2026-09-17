package com.devpilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.ai.mode",havingValue="openai")
public class OpenAIProvider extends OpenAICompatibleProvider {
    public OpenAIProvider(@Value("${app.ai.api-key:}")String key,
                          @Value("${app.ai.base-url:}")String url,
                          @Value("${app.ai.model:}")String model,ObjectMapper mapper,Validator validator) {
        super(key,url.isBlank()?"https://api.openai.com/v1":url,
                model.isBlank()?"gpt-4.1-mini":model,mapper,validator,"openai");
    }

    public String source() {
        return "OPENAI";
    }
}
