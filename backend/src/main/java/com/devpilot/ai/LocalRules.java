package com.devpilot.ai;
import java.util.*;
import java.util.regex.*;
/** Conservative deterministic checks; they are not a substitute for semantic AI review. */
public final class LocalRules {
    private LocalRules() {
    }
    public record Hit(String type,String severity,String title,String file,Integer line,String recommendation) {
    }
    public static List<Hit> scan(String diff) {
        List<Hit> out=new ArrayList<>();
        String file="unknown";
        int line=0;
        boolean test=false,changed=false;
        for(String row:diff.split("\n")) {
            if(row.startsWith("FILE: ")) {
                file=row.substring(6);
                test|=file.toLowerCase(Locale.ROOT).matches(".*(test|spec).*" );
                line=0;
                continue;
            }
            if(row.startsWith("@@")) {
                Matcher m=Pattern.compile("\\+(\\d+)").matcher(row);
                if(m.find())line=Integer.parseInt(m.group(1));
                continue;
            }
            if(row.startsWith("+")&&!row.startsWith("+++")) {
                changed=true;
                String value=row.substring(1);
                Integer location=line>0?line:null;
                if(value.matches(".*(eval\\s*\\(|Runtime\\.getRuntime\\(\\)\\.exec).*"))out.add(new Hit("SECURITY","HIGH","Review dynamic code or command execution",file,location,"Validate input and use a constrained API instead of executing arbitrary strings."));
                if(value.contains("printStackTrace()"))out.add(new Hit("MAINTAINABILITY","MEDIUM","Unstructured exception logging",file,location,"Use structured logging with a request ID; avoid exposing sensitive exception data."));
                if(value.matches(".*catch\\s*\\(.*\\)\\s*\\{\\s*\\}.*"))out.add(new Hit("BUG","HIGH","Exception is silently swallowed",file,location,"Handle the failure explicitly or propagate a domain exception."));
                if(value.matches("(?i).*(password|secret|api_key)\\s*=\\s*[\"'][^\"']{8,}[\"'].*"))out.add(new Hit("SECURITY","HIGH","Possible hardcoded credential",file,location,"Confirm whether this is a real credential; revoke it if exposed and load it from a secret manager."));
                line++;
            }
            else if(row.startsWith(" "))line++;
        }
        if(changed&&!test)out.add(new Hit("TESTING","MEDIUM","No test files in the analyzed change","change-set",null,"Confirm coverage for changed behavior and add regression tests where needed."));
        return out.stream().distinct().limit(50).toList();
    }
}
