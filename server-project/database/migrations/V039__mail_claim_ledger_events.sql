BEGIN;

-- Keep the logical mail UUID even if old claimed mail is later archived.
ALTER TABLE dalmaru.character_currency_ledger ADD COLUMN mail_event_id UUID;

DO $$
DECLARE
    legacy RECORD;
    candidates UUID[];
BEGIN
    FOR legacy IN SELECT event_id,character_id,source_run_id,copper_delta
                  FROM dalmaru.character_currency_ledger WHERE reason='mail.claim' LOOP
        SELECT array_agg(event_id) INTO candidates FROM dalmaru.character_mailbox
        WHERE character_id=legacy.character_id AND source_run_id=legacy.source_run_id
          AND item_id='currency.copper' AND claimed_at IS NOT NULL AND quantity=legacy.copper_delta;
        IF COALESCE(cardinality(candidates),0) <> 1 THEN
            RAISE EXCEPTION 'Cannot uniquely link legacy mail.claim ledger event %',legacy.event_id;
        END IF;
        UPDATE dalmaru.character_currency_ledger SET mail_event_id=candidates[1] WHERE event_id=legacy.event_id;
    END LOOP;
    IF EXISTS (SELECT 1 FROM dalmaru.character_currency_ledger WHERE reason='mail.claim'
               GROUP BY character_id,mail_event_id HAVING count(*)>1) THEN
        RAISE EXCEPTION 'Duplicate legacy mail.claim events';
    END IF;
END $$;

ALTER TABLE dalmaru.character_currency_ledger DROP CONSTRAINT character_currency_ledger_source_run_id_character_id_reason_key;
CREATE UNIQUE INDEX character_currency_ledger_non_mail_once
    ON dalmaru.character_currency_ledger(source_run_id,character_id,reason) WHERE reason<>'mail.claim';
CREATE UNIQUE INDEX character_currency_ledger_mail_event_once
    ON dalmaru.character_currency_ledger(character_id,mail_event_id) WHERE reason='mail.claim';
ALTER TABLE dalmaru.character_currency_ledger ADD CONSTRAINT character_currency_ledger_mail_event_required
    CHECK ((reason='mail.claim')=(mail_event_id IS NOT NULL));

INSERT INTO dalmaru.schema_migrations(version,description) VALUES(39,'mail claim ledger event identity');
COMMIT;
