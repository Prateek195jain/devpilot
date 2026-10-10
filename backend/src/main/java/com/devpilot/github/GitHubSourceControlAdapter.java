package com.devpilot.github;
import com.devpilot.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
@Component public class GitHubSourceControlAdapter implements SourceControlProvider {
    private final WebClient web;
    public GitHubSourceControlAdapter() {
        this(WebClient.builder().baseUrl("https://api.github.com").codecs(c->c.defaultCodecs().maxInMemorySize(2_097_152)).defaultHeader("X-GitHub-Api-Version","2022-11-28").build());
    }
    GitHubSourceControlAdapter(WebClient web) {
        this.web=web;
    }
    private String sha(String value) {
        if(value==null||!value.matches("[a-fA-F0-9]{40}"))throw Problem.bad("Expected full commit or object SHA");
        return value.toLowerCase(Locale.ROOT);
    }
    public String commitTree(String token,String name,String commitSha) {
        String expected=sha(commitSha);
        JsonNode n=get(token,root(name)+"/git/commits/"+expected);
        if(!expected.equals(text(n,"sha")))throw Problem.upstream("GitHub commit mismatch");
        return sha(text(n.path("tree"),"sha"));
    }
    public List<TreeEntry> tree(String token,String name,String treeSha) {
        String expected=sha(treeSha);
        JsonNode n=get(token,root(name)+"/git/trees/"+expected);
        if(!expected.equals(text(n,"sha"))||!n.path("tree").isArray()||!n.path("truncated").isBoolean()||n.path("truncated").asBoolean())
            throw Problem.upstream("GitHub incomplete tree");
        List<TreeEntry> entries=new ArrayList<>();
        n.path("tree").forEach(e->entries.add(new TreeEntry(text(e,"path"),text(e,"type"),text(e,"mode"),sha(text(e,"sha")),e.path("size").asLong(-1))));
        return List.copyOf(entries);
    }
    public byte[] blob(String token,String name,String blobSha) {
        String expected=sha(blobSha);
        JsonNode n=get(token,root(name)+"/git/blobs/"+expected);
        if(!expected.equals(text(n,"sha"))||!"base64".equals(text(n,"encoding")))throw Problem.upstream("GitHub invalid blob");
        try {
            byte[] bytes=Base64.getDecoder().decode(text(n,"content").replace("\n","").replace("\r",""));
            if(bytes.length!=n.path("size").asLong(-1))throw Problem.upstream("GitHub blob size mismatch");
            return bytes;
        } catch(IllegalArgumentException e) {
            throw Problem.upstream("GitHub invalid blob encoding");
        }
    }
    private JsonNode get(String token,String path,Object... variables) {
        try {
            return web.get().uri(path,variables).headers(h-> {
                h.setBearerAuth(token);h.set("Accept","application/vnd.github+json");
            }
            ).retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(20)).block();
        }
        catch(RuntimeException e) {
            throw Problem.upstream("GitHub");
        }
    }
    private String root(String name) {
        if(!name.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw Problem.bad("Use owner/repository format");
        return "/repos/"+name;
    }
    private String text(JsonNode n,String key) {
        return n.path(key).isNull()?"":n.path(key).asText("");
    }
    private Repo repo(JsonNode n) {
        return new Repo(n.path("id").asLong(),text(n,"full_name"),text(n,"description"),text(n,"default_branch"),text(n,"language"));
    }
    public String login(String t) {
        return text(get(t,"/user"),"login");
    }
    public List<Repo> repositories(String t,int page) {
        List<Repo> out=new ArrayList<>();
        get(t,"/user/repos?per_page=30&sort=updated&page="+page).forEach(n->out.add(repo(n)));
        return out;
    }
    public Repo repository(String t,String name) {
        return repo(get(t,root(name)));
    }
    public String latestCommitSha(String token,String name,String branch) {
        if(branch==null||branch.isBlank())throw Problem.upstream("GitHub default branch unavailable");
        JsonNode commit=get(token,root(name)+"/commits/{branch}",branch);
        String value=text(commit,"sha");
        if(!value.matches("[a-fA-F0-9]{40}"))throw Problem.upstream("GitHub invalid commit SHA");
        return value.toLowerCase(Locale.ROOT);
    }
    public List<String> branches(String t,String name,int page) {
        List<String> out=new ArrayList<>();
        get(t,root(name)+"/branches?per_page=30&page="+page).forEach(n->out.add(text(n,"name")));
        return out;
    }
    public List<Change> changes(String t,String name,String kind,int page) {
        List<Change> out=new ArrayList<>();
        boolean pr=kind.equals("PR");
        get(t,root(name)+(pr?"/pulls?state=all&":"/commits?")+"per_page=30&page="+page).forEach(n->out.add(new Change(pr?n.path("number").asText():text(n,"sha"),pr?text(n,"title"):text(n.path("commit"),"message"),pr?text(n.path("user"),"login"):text(n.path("commit").path("author"),"name"),text(n,"html_url"),pr?text(n,"state"):"committed")));
        return out;
    }
    public Snapshot snapshot(String t,String name,String kind,String ref) {
        boolean pr=kind.equals("PR");
        if(pr&&!ref.matches("[1-9][0-9]{0,9}")||!pr&&!ref.matches("[a-fA-F0-9]{7,40}"))throw Problem.bad("Expected PR number or commit SHA");
        List<ChangedFile> files=new ArrayList<>();
        StringBuilder diff=new StringBuilder();
        boolean truncated=false;
        for(int page=1;page<=3;page++) {
            JsonNode data=get(t,root(name)+(pr?"/pulls/"+ref+"/files":"/commits/"+ref)+"?per_page=100&page="+page);
            JsonNode items=pr?data:data.path("files");
            for(JsonNode f:items) {
                String patch=text(f,"patch");
                String file=text(f,"filename");
                String part="FILE: "+file+"\n"+patch+"\n";
                if(diff.length()+part.length()>60000) {
                    truncated=true;
                    break;
                }
                if(patch.isEmpty())truncated=true;
                diff.append(part);
                files.add(new ChangedFile(file,f.path("additions").asInt(),f.path("deletions").asInt(),patch));
            }
            if(truncated||items.size()<100)break;
            if(page==3)truncated=true;
        }
        return new Snapshot(diff.toString(),List.copyOf(files),truncated);
    }
    public String readme(String t,String name) {
        try {
            JsonNode n=get(t,root(name)+"/readme");
            String value=new String(Base64.getMimeDecoder().decode(text(n,"content")),StandardCharsets.UTF_8);
            return value.substring(0,Math.min(value.length(),12000));
        }
        catch(RuntimeException e) {
            return "README unavailable from GitHub.";
        }
    }
}
