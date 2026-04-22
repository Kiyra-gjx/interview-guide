ALTER TABLE agent_step_traces
    ADD COLUMN IF NOT EXISTS guardrail_results_json TEXT;
