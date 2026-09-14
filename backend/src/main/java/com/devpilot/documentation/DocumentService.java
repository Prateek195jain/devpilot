package com.devpilot.documentation;
import com.devpilot.repository.*;
import com.devpilot.ai.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service public class DocumentService {
    private final DocumentStore docs;
    private final RepositoryStore repos;
    private final AIProvider ai;
    private final ContextService context;
    private final TransactionTemplate tx;
    public DocumentService(DocumentStore d,RepositoryStore r,AIProvider a,ContextService c,TransactionTemplate t) {
        docs=d;
        repos=r;
        ai=a;
        context=c;
        tx=t;
    }
    public GeneratedDocumentView generate(UUID repo,String kind) {
        String content=ai.document(kind,context.build(repo));
        return tx.execute(s-> {
            repos.lock(repo).orElseThrow();GeneratedDocument d=new GeneratedDocument();d.repositoryId=repo;d.kind=kind;d.documentVersion=docs.findFirstByRepositoryIdAndKindOrderByDocumentVersionDesc(repo,kind).map(v->v.documentVersion+1).orElse(1);d.content=ContextService.limit(content,40000);d.source=ai.source();return GeneratedDocumentView.of(docs.save(d));
        }
        );
    }
}
