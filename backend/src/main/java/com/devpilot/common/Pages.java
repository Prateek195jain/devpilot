package com.devpilot.common;
import org.springframework.data.domain.*;
public final class Pages {
    private Pages() {
    }
    public static Pageable of(int page,int size) {
        if(page<0||size<1||size>100)throw Problem.bad("Page must be nonnegative; size must be 1–100");
        return PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt"));
    }
}
