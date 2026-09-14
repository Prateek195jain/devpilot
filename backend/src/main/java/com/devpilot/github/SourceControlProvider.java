package com.devpilot.github;
import java.util.*;
public interface SourceControlProvider {
    record Repo(long id,String fullName,String description,String defaultBranch,String language) {
    }
    record Change(String ref,String title,String author,String url,String state) {
    }
    record ChangedFile(String filename,int additions,int deletions,String patch) {
    }
    record Snapshot(String diff,List<ChangedFile> files,boolean truncated) {
    }
    String login(String token);
    List<Repo> repositories(String token,int page);
    Repo repository(String token,String name);
    List<Change> changes(String token,String name,String kind,int page);
    List<String> branches(String token,String name,int page);
    Snapshot snapshot(String token,String name,String kind,String ref);
    String readme(String token,String name);
}
