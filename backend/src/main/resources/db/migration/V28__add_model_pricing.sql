-- ADR-0017: model pricing metadata + usage cost snapshot.
-- Prices are USD per 1M tokens; NULL = unknown (conservative: never
-- manufactured, upstream #7899 model_billing principle).

ALTER TABLE provider_models ADD COLUMN IF NOT EXISTS input_price DOUBLE PRECISION NULL;
ALTER TABLE provider_models ADD COLUMN IF NOT EXISTS output_price DOUBLE PRECISION NULL;

-- Cost snapshot taken at turn-record time; NULL when the model has no
-- complete price pair (historical ledger keeps the price-at-time).
ALTER TABLE token_usage ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION NULL;
ALTER TABLE token_usage_turns ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION NULL;
