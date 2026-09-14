package com.devpilot.documentation;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="generated_documents") public class GeneratedDocument extends BaseEntity {
    @Column(name="repository_id") public UUID repositoryId;
    @Column(name="kind",length=50) public String kind;
    @Column(name="document_version") public int documentVersion;
    @Column(name="content",columnDefinition="text") public String content;
    @Column(name="source",length=40) public String source;
}
