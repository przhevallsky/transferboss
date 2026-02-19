// TransferHub: MongoDB initialization
// Executed automatically on first MongoDB start

// Switch to pricing_db
db = db.getSiblingDB('pricing_db');

// Create collections with validation (will be refined later)
db.createCollection('corridor_configs');
db.createCollection('fee_configs');

// Create indexes
db.corridor_configs.createIndex({ "source_country": 1, "dest_country": 1 }, { unique: true });
db.fee_configs.createIndex({ "corridor_key": 1, "delivery_method": 1 }, { unique: true });

// Insert seed data: sample corridor config (US → MX)
db.corridor_configs.insertOne({
    source_country: "US",
    dest_country: "MX",
    source_currency: "USD",
    dest_currency: "MXN",
    is_active: true,
    delivery_methods: ["BANK_DEPOSIT", "CASH_PICKUP", "MOBILE_WALLET"],
    limits: {
        min_amount_usd: 10,
        max_amount_usd: 2999,
        daily_limit_usd: 5000,
        monthly_limit_usd: 15000
    },
    created_at: new Date(),
    updated_at: new Date()
});

// Insert seed data: sample corridor config (US → PH)
db.corridor_configs.insertOne({
    source_country: "US",
    dest_country: "PH",
    source_currency: "USD",
    dest_currency: "PHP",
    is_active: true,
    delivery_methods: ["BANK_DEPOSIT", "MOBILE_WALLET"],
    limits: {
        min_amount_usd: 10,
        max_amount_usd: 2999,
        daily_limit_usd: 5000,
        monthly_limit_usd: 15000
    },
    created_at: new Date(),
    updated_at: new Date()
});

// Insert seed data: fee configs
db.fee_configs.insertOne({
    corridor_key: "US_MX",
    delivery_method: "BANK_DEPOSIT",
    fee_type: "FIXED_PLUS_PERCENTAGE",
    fixed_fee_usd: 3.99,
    percentage_fee: 0.0,
    min_fee_usd: 3.99,
    max_fee_usd: 3.99,
    created_at: new Date(),
    updated_at: new Date()
});

db.fee_configs.insertOne({
    corridor_key: "US_MX",
    delivery_method: "CASH_PICKUP",
    fee_type: "FIXED_PLUS_PERCENTAGE",
    fixed_fee_usd: 5.99,
    percentage_fee: 0.0,
    min_fee_usd: 5.99,
    max_fee_usd: 5.99,
    created_at: new Date(),
    updated_at: new Date()
});

db.fee_configs.insertOne({
    corridor_key: "US_PH",
    delivery_method: "BANK_DEPOSIT",
    fee_type: "FIXED_PLUS_PERCENTAGE",
    fixed_fee_usd: 4.99,
    percentage_fee: 0.0,
    min_fee_usd: 4.99,
    max_fee_usd: 4.99,
    created_at: new Date(),
    updated_at: new Date()
});

// Switch to notification_db
db = db.getSiblingDB('notification_db');

db.createCollection('notification_templates');
db.createCollection('delivery_logs');

db.notification_templates.createIndex({ "event_type": 1, "channel": 1 }, { unique: true });
db.delivery_logs.createIndex({ "transfer_id": 1 });
db.delivery_logs.createIndex({ "created_at": 1 }, { expireAfterSeconds: 2592000 }); // TTL: 30 days

print("MongoDB initialization completed successfully");
