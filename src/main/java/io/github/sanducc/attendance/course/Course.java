package io.github.sanducc.attendance.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "course")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(nullable = false)
    private String name;

    /** Optional since V3; null when the teacher didn't give one. */
    @Column
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Since V5 (#4): comma-separated Google Workspace domains, see {@link WorkspaceDomains}; null = no lock. */
    @Column(name = "allowed_domains")
    private String allowedDomains;

    protected Course() {
    }

    public Course(Long teacherId, String name, String code, Instant createdAt) {
        this.teacherId = teacherId;
        this.name = name;
        this.code = code;
        this.createdAt = createdAt;
    }

    /** Name, code and domain lock as edited by the teacher; the domains must already be normalized. */
    void update(String name, String code, List<String> allowedDomains) {
        this.name = name;
        this.code = code;
        setAllowedDomains(allowedDomains);
    }

    void setAllowedDomains(List<String> allowedDomains) {
        this.allowedDomains = WorkspaceDomains.toColumn(allowedDomains);
    }

    public Long getId() {
        return id;
    }

    public Long getTeacherId() {
        return teacherId;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The Workspace domains whose accounts may check in; empty = any Google account (rule 7 off). */
    public List<String> getAllowedDomains() {
        return WorkspaceDomains.fromColumn(allowedDomains);
    }

    public boolean isDomainLocked() {
        return !getAllowedDomains().isEmpty();
    }
}
