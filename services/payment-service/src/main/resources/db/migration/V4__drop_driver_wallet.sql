-- Driver wallets, earnings and platform commission move to the payment provider (a simulated
-- third party): payment_db keeps only whether each charge succeeded or failed, plus refunds.
-- wallet.events.v1 (DriverEarningPosted, WalletAdjusted) is no longer published.

DROP TABLE wallet_adjustments;
DROP TABLE wallet_entries;
DROP TABLE wallets;
DROP TABLE commission_rules;

-- Only the two ledger triggers above used it; audit_records has its own forbid_modification().
DROP FUNCTION reject_ledger_change();
