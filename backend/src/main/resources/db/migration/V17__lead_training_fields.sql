-- Capture the two lead-scoring factors the fine-tuned model was trained on but
-- the CRM never recorded.
--
-- The model (Llama3_CRM/outputs/lead_management_llama3_lora) scores five factors
-- at up to 20 points each: Employees Count, Product Quantity, Deal Value,
-- Purchase Timeline and Customer Requirement. The leads table already carried
-- three of them (employee_count, estimated_deal_value, notes), so a lead could
-- never score above 60/100 and the serving layer had to rescale the result.
-- With these two columns the model sees all five and its score is directly
-- comparable to the training data again.

ALTER TABLE leads ADD COLUMN product_quantity  INTEGER;

-- VARCHAR rather than an enum type: the accepted values are a fixed list that
-- must match the training data's strings *exactly* (an exact-key lookup in
-- scripts/prompt_format.py -- "Within 1 month" silently misses "Within 1 Month"),
-- and a Postgres enum would make adding a value a migration rather than a
-- one-line change in two places. The constraint below is what actually enforces
-- the list.
ALTER TABLE leads ADD COLUMN purchase_timeline VARCHAR(30);

-- Rejects a misspelled or lowercase value at write time instead of letting it
-- reach the model, where it would silently score 0 points for urgency rather
-- than failing visibly. NULL stays allowed: existing leads have no timeline,
-- and the field is optional on the form.
ALTER TABLE leads
    ADD CONSTRAINT leads_purchase_timeline_allowed
    CHECK (purchase_timeline IS NULL OR purchase_timeline IN (
        'Immediately',
        'Within 15 Days',
        'Within 1 Month',
        'Within 2 Months',
        'Within 3 Months',
        'More than 3 Months'
    ));

-- Product quantity is a unit count, never negative. Zero is allowed and is
-- meaningful -- it is not the same as "not recorded", which is NULL.
ALTER TABLE leads
    ADD CONSTRAINT leads_product_quantity_non_negative
    CHECK (product_quantity IS NULL OR product_quantity >= 0);

COMMENT ON COLUMN leads.product_quantity IS
    'Units the lead intends to buy. Scoring factor: 0-10 units=0pts, 11-50=5, 51-101=10, 102-500=15, 500+=20.';
COMMENT ON COLUMN leads.purchase_timeline IS
    'When the lead intends to buy. Must match the model''s trained strings exactly -- see leads_purchase_timeline_allowed.';
