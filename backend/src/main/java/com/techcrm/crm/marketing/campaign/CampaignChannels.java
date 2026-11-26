package com.techcrm.crm.marketing.campaign;

import com.techcrm.crm.marketing.abtest.AbTest;
import com.techcrm.crm.marketing.content.ContentChannel;

import java.util.List;

/**
 * Which content a campaign needs. Marketing Automation is email-only, so the
 * answer is always one email (variant A, plus variant B under an A/B test),
 * whatever an older campaign's stored configuration lists.
 */
public final class CampaignChannels {

    public static final List<ContentChannel> EMAIL_ONLY = List.of(ContentChannel.EMAIL);

    private CampaignChannels() {
    }

    /** The channels a campaign sends on. */
    public static List<ContentChannel> configured(MarketingCampaign campaign) {
        return EMAIL_ONLY;
    }

    /** Everything content generation produces for a campaign. */
    public static List<ContentChannel> generated(MarketingCampaign campaign) {
        return EMAIL_ONLY;
    }

    /** The channel an A/B test varies: every supported element is part of the email. */
    public static ContentChannel testedChannel(AbTest.Element element, List<ContentChannel> configured) {
        return ContentChannel.EMAIL;
    }
}
