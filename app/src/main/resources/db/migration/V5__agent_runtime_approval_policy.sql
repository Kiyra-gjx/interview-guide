CREATE TABLE IF NOT EXISTS agent_approvals (
    id BIGSERIAL PRIMARY KEY,
    approval_id VARCHAR(36) NOT NULL,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    trace_id BIGINT NOT NULL,
    selected_tool VARCHAR(100) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decision_summary VARCHAR(500),
    tool_input_json TEXT,
    latest_user_message TEXT,
    reason VARCHAR(1000),
    expires_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS approval_id VARCHAR(36);
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS session_id BIGINT;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS turn_id BIGINT;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS trace_id BIGINT;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS selected_tool VARCHAR(100);
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS risk_level VARCHAR(32);
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS decision_summary VARCHAR(500);
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS tool_input_json TEXT;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS latest_user_message TEXT;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS reason VARCHAR(1000);
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE agent_approvals
SET status = COALESCE(status, 'PENDING'),
    created_at = COALESCE(created_at, CURRENT_TIMESTAMP),
    updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
WHERE status IS NULL
   OR created_at IS NULL
   OR updated_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_agent_approval_session_status ON agent_approvals (session_id, status);
CREATE INDEX IF NOT EXISTS idx_agent_approval_turn ON agent_approvals (turn_id);
CREATE INDEX IF NOT EXISTS idx_agent_approval_expires_at ON agent_approvals (expires_at);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_agent_approval_approval_id'
    ) THEN
        ALTER TABLE agent_approvals
            ADD CONSTRAINT uk_agent_approval_approval_id UNIQUE (approval_id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_approval_session'
    ) THEN
        ALTER TABLE agent_approvals
            ADD CONSTRAINT fk_agent_approval_session
            FOREIGN KEY (session_id) REFERENCES agent_sessions (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_approval_turn'
    ) THEN
        ALTER TABLE agent_approvals
            ADD CONSTRAINT fk_agent_approval_turn
            FOREIGN KEY (turn_id) REFERENCES agent_turns (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_approval_trace'
    ) THEN
        ALTER TABLE agent_approvals
            ADD CONSTRAINT fk_agent_approval_trace
            FOREIGN KEY (trace_id) REFERENCES agent_step_traces (id);
    END IF;
END
$$;
