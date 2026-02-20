-- ================================================
-- SEED DATA: тестовые данные для разработки
-- ================================================

-- Тестовый получатель
INSERT INTO recipients (id, sender_id, first_name, last_name, country, created_at, updated_at)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    '00000000-0000-0000-0000-000000000001',
    'Maria', 'Santos', 'PH',
    now(), now()
) ON CONFLICT (id) DO NOTHING;

-- 50 тестовых переводов с разными статусами и датами
DO $$
DECLARE
    i INTEGER;
    statuses TEXT[] := ARRAY['CREATED', 'COMPLIANCE_CHECK', 'PAYMENT_CAPTURED', 'DELIVERING', 'COMPLETED', 'CANCELLED', 'FAILED'];
    delivery_methods TEXT[] := ARRAY['BANK_DEPOSIT', 'CASH_PICKUP', 'MOBILE_WALLET'];
    corridors TEXT[][] := ARRAY[ARRAY['US','PH','USD','PHP'], ARRAY['US','MX','USD','MXN'], ARRAY['GB','IN','GBP','INR']];
    corridor TEXT[];
    transfer_id UUID;
    status TEXT;
    dm TEXT;
    amount NUMERIC;
    created TIMESTAMPTZ;
BEGIN
    FOR i IN 1..50 LOOP
        transfer_id := gen_random_uuid();
        status := statuses[1 + (i % array_length(statuses, 1))];
        dm := delivery_methods[1 + (i % array_length(delivery_methods, 1))];
        corridor := corridors[1 + (i % array_length(corridors, 1))];
        amount := 50.00 + (i * 17.50);
        created := now() - (i || ' hours')::INTERVAL;

        INSERT INTO transfers (
            id, idempotency_key, sender_id, quote_id,
            send_amount, send_currency, receive_amount, receive_currency,
            exchange_rate, fee_amount, fee_currency,
            source_country, dest_country, delivery_method,
            recipient_id, status, version,
            created_at, updated_at
        ) VALUES (
            transfer_id,
            gen_random_uuid(),
            '00000000-0000-0000-0000-000000000001',
            gen_random_uuid(),
            amount,
            corridor[3],
            amount * 56.20,
            corridor[4],
            56.2000,
            4.99,
            corridor[3],
            corridor[1],
            corridor[2],
            dm,
            '11111111-1111-1111-1111-111111111111',
            status,
            0,
            created,
            created
        );
    END LOOP;
END $$;