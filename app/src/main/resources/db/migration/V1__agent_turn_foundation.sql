CREATE TABLE IF NOT EXISTS agent_turns (
    id BIGSERIAL PRIMARY KEY,
    turn_id VARCHAR(36) NOT NULL,
    session_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    completion_mode VARCHAR(20),
    error_message VARCHAR(1000),
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    lease_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS turn_id VARCHAR(36);
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS session_id BIGINT;
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS completion_mode VARCHAR(20);
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP;
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE agent_turns
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE agent_turns
SET updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP),
    created_at = COALESCE(created_at, updated_at, CURRENT_TIMESTAMP),
    status = COALESCE(status, 'CREATED')
WHERE updated_at IS NULL
   OR created_at IS NULL
   OR status IS NULL;

ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS turn_id BIGINT;

ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS turn_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_turn_session ON agent_turns (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_turn_session_status ON agent_turns (session_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_turn_lease ON agent_turns (lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_agent_turn_created ON agent_turns (created_at);
CREATE INDEX IF NOT EXISTS idx_agent_message_turn ON agent_messages (turn_id);
CREATE INDEX IF NOT EXISTS idx_agent_trace_turn ON agent_step_traces (turn_id);
CREATE INDEX IF NOT EXISTS idx_agent_message_order ON agent_messages (session_id, message_order);
CREATE INDEX IF NOT EXISTS idx_agent_trace_session_step ON agent_step_traces (session_id, step_index);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_turn_session'
    ) THEN
        ALTER TABLE agent_turns
            ADD CONSTRAINT fk_agent_turn_session
            FOREIGN KEY (session_id) REFERENCES agent_sessions (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_agent_turn_turn_id'
    ) THEN
        ALTER TABLE agent_turns
            ADD CONSTRAINT uk_agent_turn_turn_id UNIQUE (turn_id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_message_turn'
    ) THEN
        ALTER TABLE agent_messages
            ADD CONSTRAINT fk_agent_message_turn
            FOREIGN KEY (turn_id) REFERENCES agent_turns (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_trace_turn'
    ) THEN
        ALTER TABLE agent_step_traces
            ADD CONSTRAINT fk_agent_trace_turn
            FOREIGN KEY (turn_id) REFERENCES agent_turns (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_agent_message_session_order'
    ) THEN
        ALTER TABLE agent_messages
            ADD CONSTRAINT uk_agent_message_session_order UNIQUE (session_id, message_order);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_agent_trace_session_step'
    ) THEN
        ALTER TABLE agent_step_traces
            ADD CONSTRAINT uk_agent_trace_session_step UNIQUE (session_id, step_index);
    END IF;
END
$$;
