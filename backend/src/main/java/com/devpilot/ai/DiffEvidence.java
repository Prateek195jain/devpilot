package com.devpilot.ai;
import java.util.*;
import java.util.regex.*;
/** Only admits locations actually present in the bounded GitHub diff sent to the model. */
public final class DiffEvidence {
    private DiffEvidence() {
    }
    public static boolean contains(String diff,String file,Integer line) {
        if(file.equals("change-set"))return line==null;
        boolean found=false;
        int current=0;
        for(String row:diff.split("\n")) {
            if(row.startsWith("FILE: ")) {
                found=row.substring(6).equals(file);
                current=0;
                if(found&&line==null)return true;
                continue;
            }
            if(!found)continue;
            if(row.startsWith("@@")) {
                Matcher m=Pattern.compile("\\+(\\d+)").matcher(row);
                if(m.find())current=Integer.parseInt(m.group(1));
                continue;
            }
            if((row.startsWith("+")&&!row.startsWith("+++"))||row.startsWith(" ")) {
                if(line!=null&&current==line)return true;
                current++;
            }
        }
        return false;
    }
}
