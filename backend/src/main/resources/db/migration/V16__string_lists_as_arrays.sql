-- Store the deal-flow string lists as text[] rather than jsonb.
--
-- V15 created these as jsonb, which does not survive contact with Hibernate:
-- mapping a List<String> field with @JdbcTypeCode(JSON) updates the recommended
-- JDBC type for the List<String> Java descriptor process-wide, and
-- users.permissions — a genuine text[] column with an explicit
-- @JdbcTypeCode(ARRAY) — then fails schema validation because Hibernate has
-- started expecting JSON there too. One entity silently redefines the mapping
-- for an unrelated one.
--
-- text[] is also simply the right column type here. These are flat lists of
-- short strings, never queried by position or nested, and users.permissions
-- already establishes the convention in this schema. jsonb bought nothing.
--
-- Dropped and re-added rather than converted in place. A jsonb -> text[] cast
-- needs a subquery over jsonb_array_elements, which Postgres rejects inside an
-- ALTER COLUMN ... USING transform. Discarding the contents is safe here and
-- only here: these columns were created by V15, and the entity mapping that
-- would write to them fails schema validation, so the application has never
-- started against them. There is nothing to lose.

ALTER TABLE deal_predictions
    DROP COLUMN positive_factors,
    DROP COLUMN negative_factors,
    ADD COLUMN positive_factors TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN negative_factors TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

ALTER TABLE deal_feature_sets
    DROP COLUMN imputed_fields,
    ADD COLUMN imputed_fields TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
