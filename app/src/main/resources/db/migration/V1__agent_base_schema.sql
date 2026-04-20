CREATE TABLE IF NOT EXISTS agent_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    goal TEXT NOT NULL,
    resume_id BIGINT,
    knowledge_base_ids_json TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    memory_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(36);
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS goal TEXT;
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS resume_id BIGINT;
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS knowledge_base_ids_json TEXT;
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS memory_json TEXT;
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE agent_sessions
SET session_id = COALESCE(session_id, md5(random()::text || clock_timestamp()::text)),
    title = COALESCE(title, 'Agent Session'),
    goal = COALESCE(goal, ''),
    status = COALESCE(status, 'CREATED'),
    created_at = COALESCE(created_at, updated_at, CURRENT_TIMESTAMP),
    updated_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
WHERE session_id IS NULL
   OR title IS NULL
   OR goal IS NULL
   OR status IS NULL
   OR created_at IS NULL
   OR updated_at IS NULL;

CREATE TABLE IF NOT EXISTS agent_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    message_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS session_id BIGINT;
ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS role VARCHAR(20);
ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS message_order INTEGER;
ALTER TABLE agent_messages
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE agent_messages
SET role = COALESCE(role, 'USER'),
    content = COALESCE(content, ''),
    message_order = COALESCE(message_order, 1),
    created_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE role IS NULL
   OR content IS NULL
   OR message_order IS NULL
   OR created_at IS NULL;

CREATE TABLE IF NOT EXISTS agent_step_traces (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    step_index INTEGER NOT NULL,
    decision_summary VARCHAR(500),
    selected_tool VARCHAR(100),
    tool_input_json TEXT,
    tool_output_json TEXT,
    observation_summary VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS session_id BIGINT;
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS step_index INTEGER;
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS decision_summary VARCHAR(500);
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS selected_tool VARCHAR(100);
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS tool_input_json TEXT;
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS tool_output_json TEXT;
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS observation_summary VARCHAR(500);
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);
ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE agent_step_traces
SET step_index = COALESCE(step_index, 1),
    status = COALESCE(status, 'CREATED'),
    created_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE step_index IS NULL
   OR status IS NULL
   OR created_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_agent_session_session ON agent_sessions (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_session_updated ON agent_sessions (updated_at);
CREATE INDEX IF NOT EXISTS idx_agent_message_session ON agent_messages (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_message_order ON agent_messages (session_id, message_order);
CREATE INDEX IF NOT EXISTS idx_agent_trace_session ON agent_step_traces (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_trace_session_step ON agent_step_traces (session_id, step_index);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_agent_session_session_id'
    ) THEN
        ALTER TABLE agent_sessions
            ADD CONSTRAINT uk_agent_session_session_id UNIQUE (session_id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_message_session'
    ) THEN
        ALTER TABLE agent_messages
            ADD CONSTRAINT fk_agent_message_session
            FOREIGN KEY (session_id) REFERENCES agent_sessions (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_agent_trace_session'
    ) THEN
        ALTER TABLE agent_step_traces
            ADD CONSTRAINT fk_agent_trace_session
            FOREIGN KEY (session_id) REFERENCES agent_sessions (id);
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
