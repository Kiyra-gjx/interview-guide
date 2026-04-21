ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS memory_before_json TEXT;

ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS memory_after_json TEXT;
