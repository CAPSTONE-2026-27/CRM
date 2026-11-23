package com.techcrm.crm.marketing.activity;

import com.techcrm.crm.audit.AuditLogService;
import com.techcrm.crm.marketing.activity.CampaignActivity.ActorType;
import com.techcrm.crm.marketing.common.MarketingJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes the campaign audit trail. Every AI result, human change and system
 * transition goes through here, so "who decided this, and was it the model or
 * a person?" always has an answer.
 *
 * The activity row joins the caller's transaction: if the action rolls back,
 * the trail does not claim it happened. Human actions are also mirrored to the
 * organization-wide audit log (Security &amp; audit screen), and that mirror is
 * deferred until after commit for the same reason.
 */
@Service
public class ActivityService {

    private static final int SUMMARY_MAX = 500;

    private final CampaignActivityRepository repository;
    private final AuditLogService auditLogService;

    public ActivityService(CampaignActivityRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    public void human(Long orgId, Long campaignId, Long userId, String action, String summary, Object detail) {
        record(orgId, campaignId, ActorType.HUMAN, userId, action, summary, detail);
        mirrorToAuditLog(orgId, userId, action, campaignId, summary);
    }

    public void ai(Long orgId, Long campaignId, String action, String summary, Object detail) {
        record(orgId, campaignId, ActorType.AI, null, action, summary, detail);
    }

    public void system(Long orgId, Long campaignId, String action, String summary, Object detail) {
        record(orgId, campaignId, ActorType.SYSTEM, null, action, summary, detail);
    }

    private void record(Long orgId, Long campaignId, ActorType actor, Long userId,
                        String action, String summary, Object detail) {
        CampaignActivity entry = new CampaignActivity();
        entry.setOrganizationId(orgId);
        entry.setCampaignId(campaignId);
        entry.setActorType(actor);
        entry.setActorUserId(userId);
        entry.setAction(action);
        entry.setSummary(clip(summary));
        entry.setDetailJson(detail == null ? null : MarketingJson.write(detail));
        repository.save(entry);
    }

    private void mirrorToAuditLog(Long orgId, Long userId, String action, Long campaignId, String summary) {
        Runnable write = () -> auditLogService.record(orgId, userId, "MARKETING_" + action,
                "MARKETING_CAMPAIGN", String.valueOf(campaignId), clip(summary));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    private static String clip(String value) {
        if (value == null) return "";
        return value.length() <= SUMMARY_MAX ? value : value.substring(0, SUMMARY_MAX - 1) + "…";
    }
}
