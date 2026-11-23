-- Lead conversion now produces everything a proposal or contract needs.
--
-- leads.billing_address: captured on the lead form, copied to the account on
-- conversion when the account has none. Same width as accounts.billing_address
-- so a copied value always fits.
--
-- deals.primary_contact_id: the person this deal is with, set on conversion to
-- the contact made from the lead. Proposal and contract generation address this
-- contact first, so a second person added to the account later (a finance
-- manager, say) does not silently become every deal's recipient. Cleared, not
-- cascaded, when the contact is deleted: the deal survives and generation falls
-- back to the account's other contacts.

ALTER TABLE leads
    ADD COLUMN billing_address VARCHAR(500);

ALTER TABLE deals
    ADD COLUMN primary_contact_id BIGINT REFERENCES contacts (id) ON DELETE SET NULL;
