package com.techcrm.crm.ai.coach;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** One turn of a user's deal coach conversation (see V23). */
@Entity
@Table(name = "coach_messages")
@Getter
@Setter
public class CoachMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** user | assistant */
    @Column(nullable = false, length = 10)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
