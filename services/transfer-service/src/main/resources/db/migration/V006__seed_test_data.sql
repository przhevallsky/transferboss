-- ================================================
-- SEED DATA: тестовые данные для разработки
-- ================================================

-- Тестовый получатель
INSERT INTO recipients (id, sender_id, first_name, last_name, country, delivery_details, created_at, updated_at)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    '00000000-0000-0000-0000-000000000001',
    'Maria', 'Santos', 'PH',
    '{"bank_name": "BDO", "account_number": "1234567890", "branch_code": "001"}'::jsonb,
    now(), now()
) ON CONFLICT (id) DO NOTHING;

-- 50 тестовых переводов с разными статусами и датами
DO $$
DECLARE
    i INTEGER;
    ci INTEGER;
    statuses TEXT[] := ARRAY['CREATED', 'COMPLIANCE_CHECK', 'PAYMENT_CAPTURED', 'DELIVERING', 'COMPLETED', 'CANCELLED', 'FAILED'];
    delivery_methods TEXT[] := ARRAY['BANK_DEPOSIT', 'CASH_PICKUP', 'MOBILE_WALLET'];
    src_countries TEXT[] := ARRAY['US', 'US', 'GB'];
    dst_countries TEXT[] := ARRAY['PH', 'MX', 'IN'];
    send_currencies TEXT[] := ARRAY['USD', 'USD', 'GBP'];
    recv_currencies TEXT[] := ARRAY['PHP', 'MXN', 'INR'];
    transfer_id UUID;
    status TEXT;
    dm TEXT;
    amount NUMERIC;
    created TIMESTAMPTZ;
BEGIN
    FOR i IN 1..50 LOOP
        transfer_id := gen_random_uuid();
        ci := 1 + (i % 3);
        status := statuses[1 + (i % array_length(statuses, 1))];
        dm := delivery_methods[1 + (i % array_length(delivery_methods, 1))];
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
            send_currencies[ci],
            amount * 56.20,
            recv_currencies[ci],
            56.2000,
            4.99,
            send_currencies[ci],
            src_countries[ci],
            dst_countries[ci],
            dm,
            '11111111-1111-1111-1111-111111111111',
            status,
            0,
            created,
            created
        );
    END LOOP;
END $$;
