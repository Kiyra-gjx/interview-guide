DO $$
BEGIN
    IF to_regclass('public.knowledge_bases') IS NOT NULL THEN
        ALTER TABLE knowledge_bases
            ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

        ALTER TABLE knowledge_bases
            ADD COLUMN IF NOT EXISTS delete_requested_at TIMESTAMP;

        UPDATE knowledge_bases
        SET lifecycle_status = 'ACTIVE'
        WHERE lifecycle_status IS NULL;

        CREATE INDEX IF NOT EXISTS idx_kb_lifecycle_status
            ON knowledge_bases (lifecycle_status);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS knowledge_base_delete_tasks (
    id BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    storage_key VARCHAR(500),
    claim_token VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    next_retry_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT;
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS storage_key VARCHAR(500);
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64);
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(500);
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE knowledge_base_delete_tasks
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_kb_delete_task_due
    ON knowledge_base_delete_tasks (status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_kb_delete_task_kb
    ON knowledge_base_delete_tasks (knowledge_base_id);
