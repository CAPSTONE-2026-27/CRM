package com.techcrm.crm.contract;

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
 * Ready-made CRM records for the contract tests.
 *
 * Every object here is valid — a contract generated from them succeeds. Tests
 * that exercise a validation failure take one of these and break exactly the one
 * field they are about, which keeps "what is being tested" visible at the call
 * site instead of buried in a bespoke builder.
 */
public final class ContractFixtures {

    public static final Long ORG_ID = 7L;
    public static final Long USER_ID = 3L;
    public static final Long DEAL_ID = 31L;
    public static final Long ACCOUNT_ID = 9L;
    public static final Long CONTACT_ID = 12L;
    public static final Long OWNER_ID = 5L;
    public static final Long LEAD_ID = 10L;

    private ContractFixtures() {
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
     *  contract schedule instead of the deal-value fallback. */
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

    public static ContractAssembly assembly() {
        return assembly(List.of(new ContractAssembly.ResolvedLineItem(
                1, "Fleet Telematics Unit",
                new BigDecimal("30.00"), new BigDecimal("15000.00"), new BigDecimal("450000.00"),
                ContractLineItem.Source.LEAD_PRODUCT)));
    }

    public static ContractAssembly assembly(List<ContractAssembly.ResolvedLineItem> lineItems) {
        BigDecimal total = lineItems.stream()
                .map(ContractAssembly.ResolvedLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ContractAssembly(
                deal(), account(), contact(), owner(), "TechCRM Solutions",
                lineItems, "INR", total,
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1),
                "50% on execution, 50% on delivery.",
                "Delivery expected within 1 month from the contract start date.");
    }

    public static Contract contract() {
        Contract contract = new Contract();
        contract.setId(42L);
        contract.setOrganizationId(ORG_ID);
        contract.setContractNumber("CTR-000042");
        contract.setDealId(DEAL_ID);
        contract.setOpportunityId(DealStages.opportunityReference(DEAL_ID));
        contract.setAccountId(ACCOUNT_ID);
        contract.setContactId(CONTACT_ID);
        contract.setOwnerId(OWNER_ID);
        contract.setContractType(ContractType.STANDARD_SALES_AGREEMENT);
        contract.setTemplateKey("classpath:contract-templates/standard-sales-agreement.docx");
        contract.setStatus(ContractStatus.GENERATED);
        contract.setCurrency("INR");
        contract.setTotalAmount(new BigDecimal("450000.00"));
        contract.setStartDate(LocalDate.of(2026, 9, 1));
        contract.setEndDate(LocalDate.of(2027, 9, 1));
        contract.setSignerName("Asha Menon");
        contract.setSignerEmail("asha.menon@northwind.example");
        contract.setDocxPath("7/CTR-000042/CTR-000042-20260901-101500.docx");
        contract.setPdfPath("7/CTR-000042/CTR-000042-20260901-101500.pdf");
        return contract;
    }
}
