package com.techcrm.crm.proposal;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.contact.Contact;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Ready-made CRM records for the proposal tests.
 *
 * Every object here is valid — a proposal generated from them succeeds. Tests
 * that exercise a validation failure take one of these and break exactly the one
 * field they are about, which keeps "what is being tested" visible at the call
 * site instead of buried in a bespoke builder.
 *
 * Separate from {@code ContractFixtures} rather than shared: the two modules'
 * assemblies carry different commercial fields, and a shared fixture would have
 * to satisfy both, which is how a fixture stops describing anything in
 * particular.
 */
public final class ProposalFixtures {

    public static final Long ORG_ID = 7L;
    public static final Long USER_ID = 3L;
    public static final Long DEAL_ID = 31L;
    public static final Long ACCOUNT_ID = 9L;
    public static final Long CONTACT_ID = 12L;
    public static final Long OWNER_ID = 5L;
    public static final Long LEAD_ID = 10L;
    public static final Long PROPOSAL_ID = 42L;

    private ProposalFixtures() {
    }

    public static AuthenticatedUser caller() {
        return new AuthenticatedUser(USER_ID, ORG_ID, Role.SALES_REP, List.of("pipeline"), false);
    }

    public static Deal deal() {
        Deal deal = new Deal();
        deal.setId(DEAL_ID);
        deal.setOrganizationId(ORG_ID);
        deal.setName("Northwind ERP rollout");
        deal.setAccountId(ACCOUNT_ID);
        deal.setValue(new BigDecimal("450000.00"));
        deal.setCurrency("INR");
        deal.setStage(DealStages.PROPOSAL);
        deal.setOpportunityId(DealStages.opportunityReference(DEAL_ID));
        deal.setOwnerId(OWNER_ID);
        return deal;
    }

    public static Account account() {
        Account account = new Account();
        account.setId(ACCOUNT_ID);
        account.setOrganizationId(ORG_ID);
        account.setName("Northwind Traders Pvt Ltd");
        account.setIndustry("Logistics");
        account.setBillingAddress("4th Floor, Prestige Tower\nMG Road\nBengaluru 560001");
        return account;
    }

    public static Contact contact() {
        Contact contact = new Contact();
        contact.setId(CONTACT_ID);
        contact.setOrganizationId(ORG_ID);
        contact.setAccountId(ACCOUNT_ID);
        contact.setFullName("Asha Menon");
        contact.setJobTitle("Head of Operations");
        contact.setEmail("asha.menon@northwind.example");
        contact.setPhone("+91 80 4000 1234");
        contact.setPrimary(true);
        return contact;
    }

    public static User owner() {
        User user = new User();
        user.setId(OWNER_ID);
        user.setOrganizationId(ORG_ID);
        user.setFullName("Ravi Kulkarni");
        user.setEmail("ravi.kulkarni@techcrm.example");
        user.setPhone("+91 98450 00000");
        user.setRole(Role.SALES_REP);
        return user;
    }

    /** A lead that names a product, which is what puts a real quantity on the
     *  proposal's pricing instead of the deal-value fallback. */
    public static Lead lead() {
        Lead lead = new Lead();
        lead.setId(LEAD_ID);
        lead.setOrganizationId(ORG_ID);
        lead.setFullName("Asha Menon");
        lead.setCompany("Northwind Traders Pvt Ltd");
        lead.setProduct("Fleet Telematics Unit");
        lead.setProductQuantity(30);
        lead.setPurchaseTimeline("Within 1 Month");
        return lead;
    }

    public static ProposalAssembly assembly() {
        return assembly(List.of(new ProposalAssembly.ResolvedLineItem(
                1, "Fleet Telematics Unit",
                new BigDecimal("30.00"), new BigDecimal("15000.00"), new BigDecimal("450000.00"),
                ProposalLineItem.Source.LEAD_PRODUCT)));
    }

    public static ProposalAssembly assembly(List<ProposalAssembly.ResolvedLineItem> lineItems) {
        BigDecimal total = lineItems.stream()
                .map(ProposalAssembly.ResolvedLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProposalAssembly(
                deal(), account(), contact(), owner(), "TechCRM Solutions",
                lineItems, "INR", total,
                LocalDate.now().plusDays(30),
                "50% on acceptance, 50% on delivery.",
                "Delivery expected within 1 month from proposal acceptance.");
    }

    /** A finished proposal: DRAFT, with both documents recorded. */
    public static Proposal proposal() {
        Proposal proposal = new Proposal();
        proposal.setId(PROPOSAL_ID);
        proposal.setOrganizationId(ORG_ID);
        proposal.setProposalNumber("PRP-000042");
        proposal.setDealId(DEAL_ID);
        proposal.setOpportunityId(DealStages.opportunityReference(DEAL_ID));
        proposal.setAccountId(ACCOUNT_ID);
        proposal.setContactId(CONTACT_ID);
        proposal.setOwnerId(OWNER_ID);
        proposal.setProposalType(ProposalType.STANDARD_SALES_PROPOSAL);
        proposal.setTemplateKey("classpath:proposal-templates/standard-sales-proposal.docx");
        proposal.setStatus(ProposalStatus.GENERATED);
        proposal.setCurrency("INR");
        proposal.setTotalAmount(new BigDecimal("450000.00"));
        proposal.setValidUntil(LocalDate.now().plusDays(30));
        proposal.setSignerName("Asha Menon");
        proposal.setSignerEmail("asha.menon@northwind.example");
        proposal.setDocxPath("7/PRP-000042/PRP-000042-20260901-101500.docx");
        proposal.setPdfPath("7/PRP-000042/PRP-000042-20260901-101500.pdf");
        return proposal;
    }
}
