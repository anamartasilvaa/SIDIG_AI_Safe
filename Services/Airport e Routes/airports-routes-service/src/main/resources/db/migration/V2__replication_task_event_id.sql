-- Id global de cada evento (UUID), enviado como eventId no envelope e como messageId AMQP.
-- Substitui <originNodeId>-<taskId>, que se repetia quando a base de dados era recriada
-- (os consumidores com inbox tomavam eventos novos por duplicados).
ALTER TABLE replication_task ADD COLUMN event_id VARCHAR(36);
