# IntelliDoc 迭代记录

## 2026-10-09 · P0 会话归属校验

- 历史查询、会话切换、归档和取消归档均使用当前用户 ID 校验会话归属；无权限与不存在统一返回 404。
- 服务层对会话初始化、消息持久化增加归属校验，阻止旧 Redis 当前会话指针写入其他人的会话。
- 保留每条回答已有的引用映射与页码信息；管理员跨用户查询继续使用独立管理接口。
- 验证：ConversationServiceTest 与 ConversationOwnershipTest 共 12 项通过，含 HTTP 控制器边界、服务层越权及历史引用保留。
- 限制：本地 8081/9527 和基础服务未监听；尚未验证真实 JWT 过滤链、浏览器网络及 MySQL/Redis 数据。控制器测试使用 MockMvc 和模拟 JWT。

## 2026-10-09 · P0 同文件跨用户隔离

- 删除仅清理当前上传者的 FileUpload、DocumentVector、Elasticsearch 文档和 Redis 上传进度。
- 同 MD5 的文件引用在数据库事务中按稳定顺序加锁；其他用户仍引用时保留共享 MinIO 原文件、分片元数据和预览缓存，最后一个引用删除时才清理共享内容。
- 同步/异步索引重建、向量读取及任务状态回写均携带上传者身份；管理员重建保留明确的文件拥有者和额度请求者。
- 索引删除失败、超时或版本冲突将中断操作，保留数据库引用供重试；不再按旧文件名兜底删除，避免同名不同内容被误删。
- 上传、下载、删除统一使用配置中的 MinIO bucket。同步更新两项已落后于实现的上传测试断言，保留存储完整性校验。
- 验证：DocumentIsolationTest、DocumentIsolationRepositoryTest、VectorizationIsolationTest、UploadServiceTest、UploadControllerTest、BootstrapKnowledgeInitializerTest 共 20 项通过；H2 实测删除用户 1 后用户 2 的文件和向量仍保留。
- 限制：H2 未覆盖 MySQL InnoDB 的并发间隙锁语义；ES/MinIO 使用 mock，需在真实环境验证并发删除与上传。外部存储不属于 JPA 原子事务；旧文件名对象保守保留，后续通过审计清理。

## 2026-10-09 · P1 Embedding 模型变更保护

- 对激活 Embedding 配置的 provider、模型名、向量维度统一检查；同 provider 下更换模型或维度同样返回 409，并明确提示需要重嵌入。
- 校验发生在任何数据库写入之前，拒绝操作不改变内存配置；维度省略时沿用当前值，provider 和模型名按保存规则标准化。
- 允许密钥轮换、规范化后的同一 API 地址保存、未激活 provider 的配置编辑，LLM 配置行为保持不变。
- 验证：ModelProviderConfigServiceTest 共 10 项通过，包括原有 LLM 配置/连接测试和新增模型、维度、维度省略、密钥轮换及非激活 provider 场景。
- 限制：本轮提供变更保护，不自动执行重嵌入或切换索引；真实模型连接需服务环境恢复后验证。
