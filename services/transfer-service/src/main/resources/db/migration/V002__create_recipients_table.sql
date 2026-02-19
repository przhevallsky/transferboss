CREATE TABLE recipients (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id         UUID            NOT NULL,

    -- Данные получателя
    first_name        VARCHAR(100)    NOT NULL,
    last_name         VARCHAR(100)    NOT NULL,
    country           CHAR(2)         NOT NULL,

    -- Реквизиты доставки — JSONB т.к. разные delivery methods имеют разный набор полей:
    -- bank_deposit: {"bank_name": "BDO", "account_number": "123", "branch_code": "001"}
    -- mobile_wallet: {"provider": "GCASH", "phone_number": "+639171234567"}
    -- cash_pickup: {"pickup_network": "CEBUANA", "id_type": "PASSPORT"}
    delivery_details  JSONB           NOT NULL,

    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    is_active         BOOLEAN         NOT NULL DEFAULT true
);

-- Partial index: только активных получателей (мягкое удаление через is_active=false)
CREATE INDEX idx_recipients_sender
    ON recipients (sender_id)
    WHERE is_active = true;
