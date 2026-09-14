package com.devpilot.analysis;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="findings") public class Finding extends BaseEntity {
    @Column(name="analysis_id") public UUID analysisId;
    @Column(name="repository_id") public UUID repositoryId;
    @Column(name="type",length=50) public String type;
    @Column(name="severity",length=20) public String severity;
    @Column(name="title",length=200) public String title;
    @Column(name="description",columnDefinition="text") public String description;
    @Column(name="file",length=500) public String file;
    @Column(name="line_number") public Integer line;
    @Column(name="recommendation",columnDefinition="text") public String recommendation;
    @Column(name="confidence") public double confidence;
    @Column(name="source",length=40) public String source;
    @Column(name="fingerprint",length=64) public String fingerprint;
}
