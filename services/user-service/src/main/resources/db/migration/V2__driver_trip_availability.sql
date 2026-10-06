-- OFFERED / BUSY projected from trip-service events (README §4.8, §5.3).
-- current_offer_id / current_trip_id tie each step to the offer or trip that caused it, so a late
-- or replayed event for another offer or trip cannot move the driver (dispatch.offers.v1 and
-- trip.events.v1 are separate topics, with no ordering between them).
-- online_service_types keeps what the driver chose when going online, so returning to AVAILABLE
-- after a trip restores the same set instead of every service on the profile.
ALTER TABLE driver_profiles
    ADD COLUMN current_offer_id     UUID,
    ADD COLUMN current_trip_id      UUID,
    ADD COLUMN online_service_types VARCHAR(40);
