package io.github.sanducc.attendance.checkin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Keyed on (provider, provider_subject). The student sets the names once, at first check-in; after that only a
 * teacher can correct them ({@link AttendanceRecordService#renameEntry}), and the change applies everywhere.
 */
@Entity
@Table(name = "student")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Provider provider;

    @Column(name = "provider_subject", nullable = false)
    private String providerSubject;

    private String email;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Student() {
    }

    public Student(Provider provider, String providerSubject, String email, String firstName, String lastName,
                   Instant createdAt) {
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    /** Teacher correction; the caller has validated and normalized the names. */
    void rename(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
