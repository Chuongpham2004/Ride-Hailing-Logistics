-- Keeps drivers from getting stuck in OFFERED (README §5.3):
-- accepted_offer_id: the offer that led to the current or last trip. A DriverOfferCreated for it
--   that arrives after TripAccepted (different topic, no ordering) is ignored.
-- current_offer_expires_at: when the held offer runs out; a driver still OFFERED well past it is
--   returned to AVAILABLE by StaleOfferSweeper, in case the closing event never arrives.
ALTER TABLE driver_profiles
    ADD COLUMN accepted_offer_id        UUID,
    ADD COLUMN current_offer_expires_at TIMESTAMPTZ;

CREATE INDEX ix_driver_profiles_offered ON driver_profiles (current_offer_expires_at)
    WHERE availability = 'OFFERED';
