package com.techcrm.crm.marketing.campaign;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, Long> {

    List<MarketingCampaign> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<MarketingCampaign> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Row lock for status transitions, so two threads can't derive and write a status at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MarketingCampaign c where c.id = :id")
    Optional<MarketingCampaign> findForUpdate(@Param("id") Long id);

    /** Cheap status read between sends, so a pause takes effect mid-batch. */
    @Query("select c.status from MarketingCampaign c where c.id = :id")
    CampaignStatus currentStatus(@Param("id") Long id);

    @Query("select c.leadPipelineRun from MarketingCampaign c where c.id = :id")
    Integer currentLeadRun(@Param("id") Long id);

    @Modifying
    @Query("update MarketingCampaign c set c.leadPipelineTotal = :total, c.leadPipelineDone = 0 "
            + "where c.id = :id and c.leadPipelineRun = :run")
    int startLeadProgress(@Param("id") Long id, @Param("run") int run, @Param("total") int total);

    List<MarketingCampaign> findByStatusIn(Collection<CampaignStatus> statuses);

    List<MarketingCampaign> findByStatusAndScheduledAtLessThanEqual(CampaignStatus status, OffsetDateTime at);

    List<MarketingCampaign> findByLeadPipelineStatusOrContentPipelineStatus(PipelineStatus lead, PipelineStatus content);

    /* Progress writes from the pipeline workers go through targeted updates
     * rather than a load-modify-save of the whole entity, so a worker can
     * never overwrite a concurrent edit made by the Marketing Head. Each one
     * is guarded by the run number: a superseded run updates zero rows. */

    @Modifying
    @Query("update MarketingCampaign c set c.leadPipelineDone = :done where c.id = :id and c.leadPipelineRun = :run")
    int updateLeadProgress(@Param("id") Long id, @Param("run") int run, @Param("done") int done);

    @Modifying
    @Query("update MarketingCampaign c set c.leadPipelineStatus = :status, c.leadPipelineError = :error "
            + "where c.id = :id and c.leadPipelineRun = :run")
    int finishLeadPipeline(@Param("id") Long id, @Param("run") int run,
                           @Param("status") PipelineStatus status, @Param("error") String error);

    @Modifying
    @Query("update MarketingCampaign c set c.contentPipelineStatus = :status, c.contentPipelineError = :error "
            + "where c.id = :id and c.contentPipelineRun = :run")
    int finishContentPipeline(@Param("id") Long id, @Param("run") int run,
                              @Param("status") PipelineStatus status, @Param("error") String error);
}
