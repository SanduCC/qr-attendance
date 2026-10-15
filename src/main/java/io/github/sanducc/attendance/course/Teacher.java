package io.github.sanducc.attendance.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A teacher is someone an admin added by email. Deactivating (instead of deleting) keeps their courses and
 * attendance history while revoking access.
 */
@Entity
@Table(name = "teacher")
public class Teacher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** From the identity provider; null until the first sign-in. */
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "added_by")
    private String addedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected Teacher() {
    }

    public Teacher(String email, String name, Instant createdAt) {
        this.email = email;
        this.name = name;
        this.createdAt = createdAt;
    }

    /** A teacher added by an admin, before their first sign-in. */
    public static Teacher invited(String email, String addedBy, Instant now) {
        Teacher teacher = new Teacher(email, null, now);
        teacher.addedBy = addedBy;
        return teacher;
    }

    public void recordLogin(String name, Instant now) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        this.lastLoginAt = now;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
