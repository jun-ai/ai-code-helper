# 🤖 AI 编程小助手

> 基于 LangChain4j + 智谱 GLM + Milvus + MySQL 的 AI 编程学习与求职辅导机器人；前端 Vue 3 + Naive UI 全栈重塑。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.3.4-4FC08D.svg)](https://vuejs.org/)
[![Naive UI](https://img.shields.io/badge/Naive%20UI-2.38-2d8cf0.svg)](https://www.naiveui.com/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.1.0-blue.svg)](https://github.com/langchain4j/langchain4j)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)

> 本仓库在原教程基础上做了生产化改造：通义千问切换为**智谱 GLM（免费 glm-4-flash）**，RAG 升级为 **Milvus 向量库 + 6 阶段链路**，会话记忆落 **MySQL**，并补充了**多模态 RAG、视频帧抽问答、PDF 上传即总结、Grafana 监控**等工程化能力；前端从单文件 App.vue 重塑为 Naive UI + 设计 token 的现代极简风格。


## ✨ 项目介绍

### 定位
- 编程学习导师：清晰的学习路线规划和个性化建议
- 求职面试助手：简历优化、面试技巧、高频题目解析
- 代码答疑专家：实时解答编程技术问题
- 多模态问答：图片描述入库、视频关键帧视觉问答、PDF/Word 上传即总结

### 技术栈

#### AI 服务
- **LangChain4j 1.1.0**：AI 应用框架
- **智谱 GLM 对话模型**：OpenAI 兼容端点，glm-4-flash 免费
- **MiniMax embo-01 向量**：1536 维 embedding
- **GLM-4V 视觉模型**：图片 caption 入库 + 视频关键帧 OCR + 扫描版 PDF

#### RAG 检索增强（6 阶段）
- **标题感知切分**：按 Markdown 标题分节，每段前缀文件名+标题便于溯源
- **Query Rewrite**：LLM 生成 N 个变体，覆盖口语化表达
- **向量 + BM25 双路召回**：COSINE 向量召回 + CJK bigram BM25，RRF 融合
- **LLM-as-Rerank**：候选 ≥5 时调 LLM 按 0-10 打分重排
- **Contextual Compression**：每段压缩到 ≤200 字，过滤无关内容
- **可配置**：每阶段都可通过 `rag.*` 开关降级

#### 多模态
- **图片入库**：上传时 GLM-4V 生成 ≤200 字中文 caption 入库，metadata 带 type=image
- **视频帧抽**：外部 ffmpeg 抽 I 帧（默认 12 帧，缩 640px），多图视觉问答
- **PDF 上传即问**：调 LLM 生成 ≤200 字 summary，前端展示「💡 让 AI 总结刚刚上传的文档？」chip
- **扫描版 PDF OCR**：PDFBox 抽空 → 按页转 PNG → GLM-4V OCR（默认上限 8 页）

#### 前端
- **Vue 3 + Naive UI 2.38**：组件库 + Pinia 状态 + `@vicons/ionicons5` 图标
- **设计 token 双主题**：`tokens.css` 颜色/间距/圆角/阴影全 token 化，亮/暗主题切换
- **多会话侧栏**：今天/昨天/更早分组、自动命名、删除、导出 JSON
- **设置抽屉**：API Key / 模型选择 / RAG 开关
- **Vite 按需加载**：`unplugin-vue-components` + `naive-ui-resolver`

#### 数据与记忆
- **MySQL 会话记忆**：chat_memory 表持久化，多轮对话重启不丢
- **窗口记忆**：每会话保留最近 10 条，防 token 爆炸

#### 安全
- **输入安全防护**：检测敏感内容
- **API Key 鉴权**：X-API-Key 请求头校验，未配置密钥则全部拒绝
- **IP + Key 双维限流**：每 API Key 每分钟 10 次，SSE 单 IP 并发 5

#### 可观测性
- **Micrometer + Prometheus**：`/actuator/prometheus` 暴露指标
- **Grafana 大盘**：QPS / 错误率 / RAG 兜底率 / SSE 活跃数 / Embedding 缓存命中 / Token 消耗 / 检索延迟
- **自动 provisioning**：docker-compose up -d 后 `localhost:3001`（admin/admin）自动加载 dashboard
- **SSE 心跳保活**：避免反向代理误判超时

#### 工具集成
- **MCP 协议**：智谱联网搜索（按量计费）


## 🚀 快速开始

### 环境要求

- **Java**：JDK 21+
- **Node.js**：16.0+
- **Maven**：3.6+
- **Docker Desktop**：Milvus + Prometheus + Grafana
- **MySQL**：8.0+
- **ffmpeg**（视频问答功能）：`apt install ffmpeg` / `winget install Gyan.FFmpeg`
- **智谱 API**：https://open.bigmodel.cn/ 申请（glm-4-flash 免费）
- **MiniMax API**：https://platform.minimaxi.com/ 申请

### 启动步骤

#### 1. 启动 Milvus + Prometheus + Grafana（Docker）

```bash
cd milvus
docker compose up -d
```

| 服务 | 端口 | 说明 |
|---|---|---|
| Milvus Standalone | 19530 | 向量库 |
| MinIO | 9123/9124 | Milvus 存储依赖 |
| etcd | 2379 | Milvus 元数据 |
| Prometheus | 9090 | 指标收集 |
| Grafana | 3001 | 监控大盘（admin/admin） |

健康检查：
```bash
curl http://localhost:9091/healthz   # Milvus OK
curl http://localhost:9090/-/healthy  # Prometheus OK
```

> 国内网络拉不动 quay.io 时，compose 已默认走 DaoCloud 镜像源。

#### 2. 准备 MySQL

```sql
CREATE DATABASE IF NOT EXISTS ai_code_helper DEFAULT CHARSET utf8mb4;
```

表结构由 `schema.sql` 应用启动时自动创建。

#### 3. 配置密钥

创建 `src/main/resources/application-local.yml`（已 gitignore）：

```yaml
zhipu:
  api-key: 你的智谱密钥
bigmodel:
  api-key: 你的智谱密钥
minimax:
  api-key: 你的MiniMax密钥
spring:
  datasource:
    password: 你的MySQL密码
api:
  security:
    api-key: 自定义接口调用密钥
```

#### 4. 启动后端

```bash
mvn spring-boot:run
```

首次启动自动加载 `src/main/resources/docs` 下文档 + 切分 + embedding + 入库；再次启动自动跳过。

#### 5. 启动前端

```bash
cd ai-code-helper-frontend
npm install
npm run dev
```

#### 6. 访问应用

| 入口 | 地址 |
|---|---|
| 前端 | http://localhost:3000 |
| 后端 API | http://localhost:8081/api |
| 健康检查 | http://localhost:8081/api/actuator/health |
| Prometheus 指标 | http://localhost:8081/api/actuator/prometheus |
| Grafana 大盘 | http://localhost:3001（admin/admin） |

所有 `/api/ai/**` 接口需携带请求头 `X-API-Key: 你配置的 api.security.api-key`。


## 技术架构

```
┌────────────────────────────────────────────────────────────────┐
│  Vue 3 + Naive UI 前端                                          │
│  - 多会话侧栏（今天/昨天/更早）                                 │
│  - 流式 SSE（fetch + ReadableStream）                            │
│  - 设置抽屉（API Key / 模型 / 主题 / RAG 开关）                 │
└────────────────────────────────────────────────────────────────┘
                              │ X-API-Key + SSE
                              ▼
┌────────────────────────────────────────────────────────────────┐
│  Spring Boot 3.5.3 后端                                         │
│  - ApiAuthFilter（鉴权 + 限流）                                 │
│  - AiController（SSE 流式 + 多模态 chat）                        │
│  - UploadController（文档/图片/视频分发）                        │
│  - SseConcurrencyGuard（单 IP 并发上限）                         │
└────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐    ┌──────────────────┐    ┌────────────────┐
│ LangChain4j  │    │  多模态 (GLM-4V)   │    │   可观测性       │
│ - RAG 6 阶段 │    │  - ImageCaption    │    │ - Micrometer    │
│ - 会话记忆    │    │  - VideoFrame      │    │ - Prometheus    │
│ - MCP 联网    │    │  - DocSummary      │    │ - Grafana       │
│ - 输入安全    │    │  - PdfOcr          │    │   (3001)        │
└──────┬───────┘    └──────────────────┘    └────────────────┘
       │
   ┌───┴────┬──────────┬──────────┐
   ▼        ▼          ▼          ▼
┌──────┐ ┌────────┐ ┌───────┐ ┌────────┐
│ GLM  │ │ MiniMax │ │ MySQL │ │ Milvus │
│对话  │ │ 1536维  │ │记忆    │ │ 向量   │
└──────┘ └────────┘ └───────┘ └────────┘
```


## 核心模块

### RAG 6 阶段流水线
- `RagTextSplitter`：按 Markdown 标题分节 + DocumentByParagraphSplitter
- `Bm25Index`：内存 BM25 + CJK 双字 gram + ReentrantReadWriteLock
- `QueryRewriter`：LLM 生成 N 个查询变体（glm-4-flash）
- `HybridContentRetriever`：向量 + BM25 召回 → RRF 融合 → rerank → 压缩
- `Reranker`：LLM-as-rerank 0-10 打分
- `ContextualCompressor`：每段 prompt 压缩到 ≤200 字

### 多模态
- `ImageCaptionService`：图片 → GLM-4V → ≤200 字 caption
- `VideoFrameExtractor`：外部 ffmpeg 抽 I 帧（启动期校验）
- `DocSummaryService`：上传文档后生成 ≤200 字 summary
- `RagIngestService.ocrPdfPages`：扫描版 PDF 多页 OCR

### 安全 + 可观测
- `ApiAuthFilter`：X-API-Key + IP+Key 双维限流
- `SseConcurrencyGuard`：单 IP SSE 并发上限 + gauge 暴露
- `AppMetrics`：12 个 Micrometer 指标（请求/错误/RAG 兜底/embedding/token/延迟）
- `ChatModelListener`：token 用量统计

### 前端
- `composables/storage.js`：localStorage 安全读写 + 内存 fallback
- `composables/useTheme.js`：双主题切换
- `composables/useSessions.js`：会话 CRUD + 分组
- `composables/useChat.js`：消息 / 流式 / 附件 / 重试
- `components/Sidebar.vue`（内联于 App.vue）：多会话列表
- `components/SettingsDrawer.vue`：Naive UI n-drawer 配置页


## 监控（Grafana）

启动 `docker compose up -d` 后访问 http://localhost:3001（admin/admin），dashboard 自动加载「AI Code Helper」面板：

| 面板 | 指标 | PromQL |
|---|---|---|
| QPS | `chat.requests` | `sum(rate(chat_requests_total[1m]))` |
| 错误率 | `chat.errors / chat.requests` | 5m 错误率 + 阈值告警 |
| RAG 兜底率 | `rag.fallback / chat.requests` | 知识库无命中比例 |
| 活跃 SSE | `sse.active.connections` | 当前活跃连接数 |
| Embedding 缓存命中 | `embedding.cache.hits / (hits+calls)` | 命中率 |
| LLM token 消耗 | `llm.tokens` | tokens/s |
| RAG 检索延迟 | `rag.retrieve.latency` | p50 / p95 |
| Embedding 延迟 | `embedding.latency` | p95 |


## 配置项

`application.yml` 中的关键开关：

```yaml
rag:
  vector-top-k: 20         # 向量召回 topK
  vector-min-score: 0.5    # COSINE 最低分
  bm25-top-k: 20           # BM25 召回 topK
  bm25-enabled: true       # 关键词召回开关
  query-rewrite-enabled: true
  query-rewrite-variants: 3
  rerank-enabled: true
  rerank-min-candidates: 5
  compress-enabled: true
  compress-max-chars: 200
  keyword-weight: 0.3      # 融合权重（向量 = 1 - 0.3）
  final-top-k: 5
  final-min-score: 0.35
```


## 测试

```bash
mvn test
```

- `HybridContentRetrieverTest`：召回过滤、bigram 重排、空结果兜底
- `RagScoreDiagnosticTest`：手动诊断相关/不相关问题的真实分数（需 `MINIMAX_API_KEY`）
- `ApiAuthFilterTest`：API Key 鉴权 + 限流
- `CachingEmbeddingModelTest`：embedding 缓存


## 致谢

- [LangChain4j](https://github.com/langchain4j/langchain4j) - AI 应用框架
- [智谱 GLM](https://open.bigmodel.cn/) - 国产大语言模型
- [MiniMax](https://platform.minimaxi.com/) - 向量模型
- [Milvus](https://milvus.io/) - 开源向量数据库
- [Naive UI](https://www.naiveui.com/) - Vue 3 组件库
- [Spring Boot](https://spring.io/projects/spring-boot) - Java 应用框架
- [Vue.js](https://vuejs.org/) - 渐进式前端框架
- [Grafana](https://grafana.com/) - 可观测性平台
- [ffmpeg](https://ffmpeg.org/) - 视频处理