# 文件处理消息可靠性

## 处理流程

```text
MinIO 合并并检查对象
  → MySQL 事务：FileUpload.COMPLETED + file_processing_event.PENDING
  → 清理分片
  → Outbox 定时扫描
  → Kafka 事务投递（acks=all、生产者幂等）
  → MySQL 标记 SENT
  → read_committed 消费
  → 按文件加行锁，检查稳定 taskId 的处理回执
  → 从 MinIO 对象直接读取，清理上次不完整结果，解析、向量化
  → MySQL 事务：分块数据、文件完成状态、任务 COMPLETED
  → 提交消费 offset
```

保证模型为“至少一次投递 + 业务幂等”，不是跨 MySQL、Kafka、MinIO、ES 和外部模型的分布式原子事务。

- 合并成功标志和消息发送意图在同一个 MySQL 事务提交。数据库提交失败时保留分片，允许再次合并。
- Kafka 不可用时任务保留在数据库；每次失败保存次数、原因和下次重试时间，指数退避从 3 秒增加到最多 300 秒，不因发送重试耗尽丢弃任务。
- Kafka 已提交而发送回执未提交时可能再次投递同一 taskId。消费者完成记录与 MySQL 分块数据同事务提交，重复任务直接跳过。
- 发送回执已写为 `SENT`、但在配置的恢复窗口内始终没有完成回执时，Outbox 会重新投递同一 taskId。这会恢复被错误推进的 consumer offset 或 broker 日志丢失；消费者按 taskId 幂等处理，无需人工重置 offset。
- 消费期间持有当前文件行锁，串行处理同一上传者的同一文件。同步重建索引也使用同一文件行锁。数据库连接会在解析和模型调用期间占用，因此应按并发数设置连接池，并控制单文件处理时间。
- 重试时清理该用户该文件的旧分块和 ES 数据；ES 文档 ID 由 userId、fileMd5、chunkId 稳定生成，避免随机 ID 造成重复索引。
- 模型已经响应但进程在数据库提交前崩溃时，模型调用可能重复；外部计费与 ES 写入不属于 MySQL 原子提交范围。若模型供应商支持幂等请求键，可继续在模型调用层使用 taskId 做增强。
- Kafka 中只保存存储定位信息和权限快照，不保存消费所需的临时签名链接。实际读取使用 `merged/{fileMd5}`，通过后端 MinIO 客户端鉴权。
- 历史无 taskId 的消息会映射到初次上传任务；已完成或已有更新任务时跳过。重复调用合并接口可为没有任务回执的历史已合并文件补建任务。
- 删除文档后，旧消息会标记为 SUPERSEDED；重放不会重新创建已删除文档。

## 消费失败与死信重放

正常任务首次失败后每 3 秒重试，最多再试 4 次；耗尽后进入 `file-processing-dlt`。只有 DLT 发送确认成功，才允许恢复原消费进度。DLT 消费者将任务置为 DEAD 并保存异常；数据库不可用时持续重试，不提交该死信 offset。

以下接口沿用 `/api/v1/admin/**` 的 ADMIN 权限：

- `GET /api/v1/admin/file-processing/dead-letters?page=0&size=20`：分页查看失败任务、所属用户/组织和错误原因。
- `POST /api/v1/admin/file-processing/{taskId}/replay`：在 MySQL 事务中创建新的重建任务，返回新的 taskId。

同一死信的重复重放请求返回同一个新任务。旧死信延迟到达不会把新任务置为失败。若文档已有其他更新任务，接口返回冲突，避免旧任务覆盖新结果。普通用户现有 `POST /api/v1/documents/{fileMd5}/vectorization/retry` 也改用 Outbox；已有待处理任务时不会重复创建。

## 配置与部署

项目现有 `ddl-auto=update` 会创建 `file_processing_event` 表，并向 `file_upload` 增加 `processing_task_id` 列。使用受控数据库迁移的环境应在发布代码前执行 `docs/sql/20261021-file-processing-outbox.sql`，再启动应用。保留处理回执，不应直接删除仍可能被 Kafka 重放引用的记录。

本地/dev/docker 默认保持一副本、一 ISR，适合现有单节点环境；**不会自动将现有单机升级成集群**。

现有单机生产部署若继续使用一副本，需要同时显式设置 `SPRING_KAFKA_TOPIC_MIN_INSYNC_REPLICAS=1`；部署环境示例已补齐此项。已有环境文件需要同步补充，避免一副本与生产默认的两个 ISR 不匹配。

生产 profile 默认三副本、最少两个同步副本，需要至少三个可用 broker。生产环境显式配置：

```dotenv
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092,kafka3:9092
SPRING_KAFKA_TOPIC_REPLICATION_FACTOR=3
SPRING_KAFKA_TOPIC_MIN_INSYNC_REPLICAS=2
# 每个应用实例使用不同值；不设置时启动时自动生成 UUID。
INSTANCE_ID=intellidoc-01
FILE_PROCESSING_OUTBOX_ENABLED=true
FILE_PROCESSING_OUTBOX_POLL_INTERVAL_MS=3000
# SENT 但未完成的任务在此窗口后补投；默认 60 秒
FILE_PROCESSING_OUTBOX_SENT_RECOVERY_DELAY_MS=60000
```

所有 broker 的事务与消费进度内部主题也需要可靠副本配置：

```properties
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
unclean.leader.election.enable=false
```

现有 topic 的副本数量不会由 `NewTopic` 自动扩容。已有单副本 topic（包括 DLT 和内部主题）需要通过 Kafka 分区副本重分配工具迁移，并核对实际副本分配和 `min.insync.replicas`，不要仅修改应用环境变量。ISR 少于两个时拒绝写入，任务留在 Outbox 等待恢复。

消费者一次拉取一条消息，默认 `max.poll.interval.ms=1800000`。特别耗时的文档可使用 `spring.kafka.consumer.max-poll-interval-ms` 提高上限。事务生产者使用 `file.processing.transaction-id-prefix`，避免无意启用消费者容器事务；offset 由 RECORD 确认模式在数据库处理提交后推进。

## 验证

```powershell
mvn -q -DskipTests compile
mvn -q '-Dtest=FileProcessingReliabilityTest,UploadControllerTest,UploadServiceTest,DocumentIsolationTest,VectorizationIsolationTest' test
# 使用单独的真实测试 Kafka，自动创建随机命名的测试 topic：
mvn -q '-Dtest=FileProcessingReliabilityTest#realKafkaCommitAbortDuplicateDeadLetterAndReplay' '-Dreliability.kafka.bootstrap=127.0.0.1:19092' test
# 可选：仅针对新建的临时测试库验证真实 MySQL（测试会清理表中数据）：
mvn -q '-Dtest=FileProcessingReliabilityTest' '-Dreliability.kafka.bootstrap=127.0.0.1:19092' '-Dreliability.jdbc.url=jdbc:mysql://127.0.0.1:13306/intellidoc_reliability' test
```

数据库集成测试默认使用 H2，也可通过上述参数在独立 MySQL 测试库执行，验证事务回滚、发送失败持久化、Kafka 提交后 DB 回滚引起的补发、并发重复消费、历史数据修复、死信重放、过期链接和租户隔离。真实 Kafka 测试验证提交可见、回滚不可见、重复投递跳过、五次消费失败进入死信并落库，以及重放成功。MinIO、ES 和模型接口在测试中使用替身；生产三节点故障切换和实际文档链路需在相应环境验证。
