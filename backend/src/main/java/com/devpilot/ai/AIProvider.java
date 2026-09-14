package com.devpilot.ai;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public interface AIProvider {
    record Issue(@NotNull @Pattern(regexp="BUG|SECURITY|TESTING|ARCHITECTURE|PERFORMANCE|MAINTAINABILITY|API")String type,@NotNull @Pattern(regexp="CRITICAL|HIGH|MEDIUM|LOW")String severity,@NotBlank @Size(max=200)String title,@NotBlank @Size(max=5000)String description,@NotBlank @Size(max=500)String file,@Min(1)Integer line,@NotBlank @Size(max=5000)String recommendation,@DecimalMin("0") @DecimalMax("1")double confidence) {
    }
    record Review(@NotBlank @Size(max=10000)String summary,@NotNull @Size(max=50)List<@Valid Issue> findings) {
    }
    record Turn(String role,String content) {
    }
    Review analyze(String diff,String context);
    String answer(String context,List<Turn> history,String question);
    String document(String kind,String context);
    String source();
}
