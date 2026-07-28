package com.techcrm.crm.rpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "rpa_bots")
@Getter
@Setter
public class RpaBot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    /** UIPATH | AUTOMATION_ANYWHERE | BLUE_PRISM */
    @Column(nullable = false, length = 30)
    private String platform = "UIPATH";

    /** ATTENDED | UNATTENDED */
    @Column(name = "bot_type", nullable = false, length = 20)
    private String botType = "UNATTENDED";

    @Column(name = "trigger_source")
    private String triggerSource;

    @Column(name = "credential_vault_ref")
    private String credentialVaultRef;

    private String environment;
    private String region;
    private String version;

    /** REGISTERED | SCHEDULED | RUNNING | ERROR | DEPLOYED */
    @Column(nullable = false, length = 20)
    private String status = "REGISTERED";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
