CREATE TABLE IF NOT EXISTS llm_providers (
    id VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(100),
    base_url VARCHAR(500) NOT NULL,
    api_key_nonce BYTEA,
    api_key_ciphertext BYTEA,
    model VARCHAR(100) NOT NULL,
    embedding_model VARCHAR(100),
    embedding_dimensions INTEGER,
    supports_embedding BOOLEAN DEFAULT FALSE,
    temperature DOUBLE PRECISION DEFAULT 0.2,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS llm_global_settings (
    id VARCHAR(50) PRIMARY KEY DEFAULT 'global',
    default_chat_provider_id VARCHAR(50) REFERENCES llm_providers(id),
    default_embedding_provider_id VARCHAR(50) REFERENCES llm_providers(id)
);
