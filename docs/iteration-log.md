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

## 2026-10-09 · P2 评测驱动 RAG

- 新增标准库评测 CLI，支持按用户 JWT 采集真实检索接口、离线回放、同数据集基线比较及非零退出码回归门槛；采集不保存 JWT 或文档正文。
- 指标覆盖 Recall@K、Precision@K、MRR、nDCG、无结果准确率、权限泄漏及 P95 延迟；仅在显式提供真实回答引用时计算引用准确率/召回率。
- 抽离检索融合算法并增加受校验的配置；默认保留原有 Min-Max 加权融合（向量 0.3 / BM25 0.7，召回倍率 30），提供 RRF 实验选项和确定性排序。
- 检索接口要求当前用户身份并校验 query/topK；全部检索路径失败时返回业务错误，防止将故障误计为正确的无结果样本。
- 增加示例数据与评测手册，明确真实业务标签、固定索引/模型/用户、基线比较和人工答案验收流程；示例数据禁止用于真实接口跑分。
- 验证：最终后端回归 95 项全部通过，Python 评测测试 13 项全部通过（含本地模拟 HTTP 鉴权采集）；离线 CLI 与基线回放通过；`mvn -q -DskipTests compile` 通过，`git diff --check` 通过。
- 后端回归命令：`mvn -q '-Dtest=*,!IntelliDocApplicationTests,!ParseServiceTest,!UploadServicePerformanceTest' test`。上述三个依赖完整运行环境/外部工具的测试类未执行；将既有解析测试中的临时路径断言改为 `Path.toString()`，修复 Windows 路径差异，不改变解析实现。
- 限制：本地 9527、8081、3306、6379、9092、9000、9200 均未监听，未启动或重启服务；浏览器、真实存储并发联调、有效 JWT 过滤链和业务知识库评测仍待环境恢复。未宣称 RRF 或本轮配置能提升真实业务质量，也未生成虚假的效果基线。
- 使用说明：[RAG 评测与迭代](rag-evaluation/README.md)。本轮四项按独立提交交付，提交标识以 Git 历史为准。
