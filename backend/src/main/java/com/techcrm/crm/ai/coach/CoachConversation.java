package com.techcrm.crm.ai.coach;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** One deal coach chat, owned by one user (see V24). */
@Entity
@Table(name = "coach_conversations")
@Getter
@Setter
public class CoachConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Set explicitly on every message rather than by @UpdateTimestamp: adding a
     *  message doesn't modify this row, so Hibernate would never bump it. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
