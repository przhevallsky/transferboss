-- Transfer recipients / beneficiaries
CREATE TABLE recipients (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID            NOT NULL,
    full_name           VARCHAR(255)    NOT NULL,
    bank_code           VARCHAR(32),
    account_number      VARCHAR(64),
    iban                VARCHAR(34),
    swift_bic           VARCHAR(11),
    country_code        VARCHAR(2)      NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_recipients_user_id ON recipients (user_id);
