ALTER TABLE agent_approvals
    ADD COLUMN IF NOT EXISTS assembled_context_json TEXT;
