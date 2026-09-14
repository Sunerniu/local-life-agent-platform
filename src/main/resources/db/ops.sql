CREATE TABLE IF NOT EXISTS hmdp_ops_incident (
  id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, environment VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), report TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS hmdp_ops_action (
  id VARCHAR(36) PRIMARY KEY, incident_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL,
  environment VARCHAR(64) NOT NULL, batch_size INT NOT NULL, status VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  decided_by BIGINT, result TEXT, verification TEXT
);
CREATE TABLE IF NOT EXISTS hmdp_ops_event (
  id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  environment VARCHAR(64) NOT NULL DEFAULT 'local',
  correlation_id VARCHAR(64) NOT NULL, outcome VARCHAR(32) NOT NULL, code VARCHAR(64) NOT NULL,
  action_id VARCHAR(36), order_id BIGINT,
  INDEX ops_event_time (created_at), INDEX ops_event_action (action_id)
);
CREATE TABLE IF NOT EXISTS hmdp_ops_replay_item (
  action_id VARCHAR(36) NOT NULL, sequence_no INT NOT NULL, order_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL, PRIMARY KEY(action_id, sequence_no)
);
CREATE TABLE IF NOT EXISTS hmdp_ops_lock (
  resource VARCHAR(64) PRIMARY KEY, action_id VARCHAR(36)
);
INSERT INTO hmdp_ops_lock(resource, action_id)
SELECT 'seckill-dlq', NULL WHERE NOT EXISTS (SELECT 1 FROM hmdp_ops_lock WHERE resource='seckill-dlq');
