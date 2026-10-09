# RAG 检索评测与迭代

本轮提供可复现的检索质量基线、回归门槛和融合算法实验入口。默认仍是原来的 Min-Max 加权融合（向量 0.3、BM25 0.7）；RRF 尚未经过真实业务数据评测，不能据此宣称效果提升。

## 1. 离线验证工具

Python 3.10+，只使用标准库，不需要安装依赖。在仓库根目录执行：

```powershell
python -m unittest discover -s scripts/tests -v
python scripts/rag_eval.py --dataset docs/rag-evaluation/cases.example.jsonl --results docs/rag-evaluation/results.example.jsonl --k 2 --label illustrative-only --out .runtime/rag-evaluation/example-report.json
```

示例文件是人工构造的测试数据，文件标识不对应真实知识库。示例输出只验证评测计算和 CLI，不能作为产品效果基线。带有 `example: true` 的样本被禁止用于真实接口评测。

## 2. 建立业务评测集

在 `.runtime/rag-evaluation/cases.jsonl` 中逐行填写 JSON。每个样本至少包含：

```json
{"id":"history-001","query":"聊天历史是否会因为 Redis 过期而丢失？","relevant":[{"fileMd5":"真实文件的MD5","chunkId":12,"grade":3}],"forbidden":[{"fileMd5":"其他组织的私有文件MD5"}],"tokenEnv":"RAG_EVAL_USER_A_TOKEN","tags":["history","tenant-isolation"]}
```

- `id`：唯一且稳定，业务知识与标签修改后重新建立基线。
- `relevant`：人工确认能支持答案的切片。`chunkId` 使用数据库/检索返回的实际编号，`grade` 可取 1、2、3，默认 1。空数组表示当前知识库和用户权限下应当没有相关证据。
- `forbidden`：该用户无权访问的文件/切片；仅填 `fileMd5` 时整个文件都禁止返回。请覆盖不同用户、不同组织、公开/私有和相同 MD5 文件场景。
- `tokenEnv`：存放对应用户 JWT 的环境变量名，默认 `RAG_EVAL_TOKEN`。不要把 token 写入数据集、脚本或版本库。
- `tags`：供人工分类筛选使用；当前汇总按整个数据集计算，不输出分组指标。

建议首批至少标注 30–50 个真实问题，覆盖精确术语、同义改写、跨段信息、PDF 页码、否定/限定条件、无答案及权限边界；保留一组不参与调参的验收样本。应由业务人员复核标签，不能用模型生成答案代替人工证据判断。

## 3. 采集当前实现作为基线

前提：本地 MySQL、Redis、ES、Kafka、MinIO、后端均已正常运行，知识库处理完成；JWT 必须有效且对应样本用户。采集会调用 Embedding 并计入现有配额。

```powershell
python scripts/rag_eval.py --dataset .runtime/rag-evaluation/cases.jsonl --api-base http://localhost:8081/api/v1 --k 5 --label weighted-default-index-v1 --capture .runtime/rag-evaluation/weighted-results.jsonl --out .runtime/rag-evaluation/weighted-report.json
```

报告/采集文件不写入 JWT 或文档正文，只保留文件标识、切片编号、分数、页码、检索模式与耗时。实际业务问题和文件标识仍属于内部数据，应保留在被 Git 忽略的 `.runtime` 下。

当前搜索接口不返回 Token 消耗，因此工具不估算或伪造成本指标。成本分析应结合现有用量仪表盘；多轮 Agent/LLM 端到端回答耗时不包含在检索耗时中。

## 4. 对比候选配置

后端配置入口：

| 环境变量 | 默认值 | 作用 |
| --- | --- | --- |
| `SEARCH_HYBRID_FUSION` | `WEIGHTED` | `WEIGHTED` 或 `RRF` |
| `SEARCH_HYBRID_KNN_WEIGHT` | `0.3` | 加权模式向量权重，范围 0–1 |
| `SEARCH_HYBRID_BM25_WEIGHT` | `0.7` | 加权模式 BM25 权重，范围 0–1，两权重不能同时为 0 |
| `SEARCH_HYBRID_RECALL_MULTIPLIER` | `30` | 每路候选数为 topK × 倍率，范围 1–100 |
| `SEARCH_HYBRID_RRF_RANK_CONSTANT` | `60` | RRF 排名常数，范围 1–1000 |

标准 RRF 的每路贡献为 `1 / (rankConstant + rank)`，按排名融合，忽略原始分数及加权模式权重。本项目在 Java 中实现融合，不依赖 ES 的新版本 retriever API。算法参考：[Elastic RRF 文档](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)。

配置通过 Spring 启动时绑定，不是管理页面中的动态开关。按仓库运行约定由 IDE 使配置生效；不要在没有确认运行方式时直接重启服务。改动一项参数后使用同一用户、索引快照、模型版本和数据集重新采集：

```powershell
python scripts/rag_eval.py --dataset .runtime/rag-evaluation/cases.jsonl --api-base http://localhost:8081/api/v1 --k 5 --label rrf-index-v1 --capture .runtime/rag-evaluation/rrf-results.jsonl --baseline .runtime/rag-evaluation/weighted-report.json --max-regression 0.02 --out .runtime/rag-evaluation/rrf-report.json
```

记录每次使用的模型、索引版本、配置和样本修订，写入 `--label` 或配套实验记录，避免索引更新和参数变更混在一起。加权融合与 RRF 并非天然优劣关系，应依据业务验收集决定是否上线。新增重排序模型前先保留当前两种方案的可复现结果，再评估增加的调用成本与延迟。

离线重算同一份采集：

```powershell
python scripts/rag_eval.py --dataset .runtime/rag-evaluation/cases.jsonl --results .runtime/rag-evaluation/rrf-results.jsonl --k 5 --baseline .runtime/rag-evaluation/weighted-report.json --out .runtime/rag-evaluation/replay-report.json
```

## 5. 指标与门槛

- Recall@K：前 K 个不同切片命中的相关标签数 / 相关标签总数。
- Precision@K：前 K 个不同切片的相关数 / K；少于 K 个结果仍以 K 为分母。
- MRR@K：首个相关切片排名的倒数；没有命中为 0。
- nDCG@K：使用 `2^grade - 1` 的增益和对数折扣，除以理想排序的 DCG。
- 无结果准确率：无相关标签样本中成功返回空结果的比例；请求失败不计为正确无答案。
- 权限泄漏：检查返回的所有切片，包含 K 以外的结果；任何命中 forbidden 都失败。
- P95 延迟：成功请求耗时的 nearest-rank 分位数；失败请求不进入延迟指标。
- 引用准确率/召回率：只有采集行显式提供 `citations`（同样为 fileMd5/chunkId 列表）时才计算；搜索接口不会自动产生回答引用，不能把检索结果冒充引用。

Recall、Precision、MRR、nDCG 只在有相关标签的样本上平均；无答案样本单独计算。没有对应样本或引用标签的指标为 null，不伪装为 0。重复切片只计算一次。

成功退出码为 0；任何接口错误、权限泄漏或相对基线的质量指标下降超过 `--max-regression` 都返回 1；输入不合法/基线数据集、K 或 schema 不匹配返回 2。比较使用指标的绝对差值，例如 0.02 是下降 2 个百分点。**没有 baseline 时 gatePassed 仅表示请求及权限检查通过，并不意味着质量达标。**

## 6. 完整回答质量与验收限制

检索指标不能证明最终回答正确。业务验收仍需人工检查答案是否受证据支持、引用是否对应具体论断、页码是否正确，以及无答案时是否编造。可为录制结果补充真实回答使用的 citations，再离线计算引用指标。

本轮未在真实知识库上跑分：本机相关服务没有监听。已验证 Java 融合/权限过滤/故障处理、H2 数据隔离、评测器指标和本地模拟 HTTP 采集。真实浏览器、MySQL/Redis/ES/MinIO 联调与业务效果基线仍需运行环境恢复后完成。
