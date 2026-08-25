# 🤖 AI 编程小助手 - LangChain4j 实战项目

> 基于 LangChain4j + 智谱 GLM + Milvus + MySQL 的 AI 编程学习与求职辅导机器人

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue.js](https://img.shields.io/badge/Vue.js-3.3.4-4FC08D.svg)](https://vuejs.org/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.1.0-blue.svg)](https://github.com/langchain4j/langchain4j)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)

> 本仓库在原教程基础上做了生产化改造：通义千问切换为**智谱 GLM（免费 glm-4-flash）**，RAG 存储升级为 **Milvus 向量库**，会话记忆落 **MySQL**，并补充了**密钥管理、接口鉴权限流、SSE 心跳、引用来源、Micrometer 指标、单元测试**等工程化能力。


## ✨ 项目介绍

### 定位
- 编程学习导师: 提供清晰的学习路线规划和个性化建议
- 求职面试助手: 涵盖简历优化、面试技巧、高频题目解析
- 代码答疑专家: 实时解答编程技术问题，提供代码示例

### 技术

#### AI 服务
- **LangChain4j 集成**: 采用业界领先的 AI 应用开发框架
- **智谱 GLM 对话模型**: 走 OpenAI 兼容端点，glm-4-flash 免费
- **MiniMax embo-01 向量**: 1536 维 embedding，用于知识库检索
- **流式响应**: SSE 实时打字机效果 + 心跳保活

#### RAG 检索增强
- **Milvus 向量库**: 知识切片持久化，重启不丢，首次启动自动灌库
- **混合检索**: 向量召回 + 字面 bigram 重排，兼顾语义与专有名词
- **双层缓存**: 检索结果 LRU 缓存 + embedding 向量缓存
- **引用来源**: 回答末尾标注【来源：文件名 - 标题】，知识库无命中时明确声明

#### 数据与记忆
- **MySQL 会话记忆**: chat_memory 表持久化，多轮对话重启不丢
- **窗口记忆**: 每会话保留最近 10 条，防 token 爆炸

#### 安全机制
- **输入安全防护**: 检测敏感内容，确保应用安全
- **API Key 鉴权**: X-API-Key 请求头校验，未配置密钥则全部拒绝
- **接口限流**: 每 API Key 每分钟 10 次，超限返回 429

#### 可观测性
- **Actuator**: /actuator/health、/metrics、/prometheus
- **自定义指标**: 请求量、token 用量、检索延迟、embedding 缓存命中率、RAG 兜底次数
- **embedding 重试**: 3 次指数退避，应对外部服务抖动

#### 工具集成
- **MCP 协议支持**: 模型上下文协议联网搜索



## 🚀 快速开始

### 环境要求

- **Java**: JDK 21+
- **Node.js**: 16.0+
- **Maven**: 3.6+（国内建议自装并配置阿里云镜像；mvnw 需访问 Maven 中央仓库）
- **Docker Desktop**: 运行 Milvus 向量库
- **MySQL**: 8.0+（本机服务或容器均可）
- **智谱 API**: [开放平台](https://open.bigmodel.cn/) 申请密钥（glm-4-flash 免费）
- **MiniMax API**: [开放平台](https://platform.minimaxi.com/) 申请密钥（向量用）

### 启动步骤

#### 1. 启动 Milvus（Docker）

```bash
cd milvus
docker compose up -d
# 等待健康检查通过
curl http://localhost:9091/healthz   # 返回 OK 即就绪
```

> 国内网络拉不动 quay.io 时，compose 已默认走 DaoCloud 镜像源；Docker Desktop 建议配置代理或镜像加速。

#### 2. 准备 MySQL

```sql
CREATE DATABASE IF NOT EXISTS ai_code_helper DEFAULT CHARSET utf8mb4;
```

表结构由 `schema.sql` 在应用启动时自动创建，无需手动建表。

#### 3. 配置密钥

创建 `src/main/resources/application-local.yml`（已被 .gitignore 排除，不会提交）：

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

生产环境改用环境变量注入：`ZHIPU_API_KEY` / `MINIMAX_API_KEY` / `MYSQL_PASSWORD` / `APP_API_KEY`。

#### 4. 启动后端

```bash
mvn spring-boot:run
```

首次启动自动加载 `src/main/resources/docs` 下的知识文档、切片、向量化并写入 Milvus；再次启动检测到已有数据会跳过。

#### 5. 启动前端

```bash
cd ai-code-helper-frontend
npm install
npm run dev
```

#### 6. 访问应用
- 前端地址: `http://localhost:5173`
- 后端API: `http://localhost:8081/api`
- 健康检查: `http://localhost:8081/api/actuator/health`
- 指标: `http://localhost:8081/api/actuator/metrics`

> 所有 `/api/ai/**` 接口需携带请求头 `X-API-Key: 你配置的 api.security.api-key`。



## 技术架构

```
┌─────────────────┐    ┌──────────────────────┐
│   Vue.js 前端    │───▶│    Spring Boot 后端    │
│   - 聊天界面     │SSE │  - ApiAuthFilter 鉴权  │
│   - 实时流式     │    │  - 限流 + SSE 心跳     │
└─────────────────┘    └──────────┬───────────┘
                                  │
                       ┌──────────▼───────────┐
                       │      LangChain4j      │
                       │  - AiServices 编排     │
                       │  - RAG 混合检索+缓存   │
                       │  - 会话记忆(窗口10条)   │
                       │  - 工具调用 / MCP      │
                       └───┬──────┬──────┬────┘
                           │      │      │
                 ┌─────────▼┐ ┌───▼────┐ ┌▼─────────────┐
                 │ 智谱 GLM  │ │ MiniMax │ │ MySQL        │
                 │ 对话/流式 │ │ 向量    │ │ chat_memory  │
                 └──────────┘ └───┬────┘ └──────────────┘
                                 │
                        ┌────────▼────────┐
                        │ Milvus 向量库    │
                        │ (etcd + MiniO)  │
                        └─────────────────┘
```



## 核心模块

- `AiCodeHelperService`: 核心对话服务（声明式 AI 接口）
- `AiCodeHelperServiceFactory`: AiServices 编排（模型/记忆/RAG/工具装配）
- `ZhipuModelConfig` / `MinimaxModelConfig`: 模型配置管理
- `RagConfig`: 知识库灌库与 Milvus 接入（空库探测自动构建）
- `HybridContentRetriever`: 向量召回 + bigram 重排混合检索
- `CachingContentRetriever` / `CachingEmbeddingModel`: 双层缓存
- `MysqlChatMemoryStore`: 会话记忆 MySQL 持久化
- `McpConfig`: 模型上下文协议联网搜索
- `SafeInputGuardrail`: 输入安全防护
- `ApiAuthFilter`: API Key 鉴权 + 固定窗口限流
- `AppMetrics`: Micrometer 指标集中定义
- `ChatModelListener`: 对话监听与 token 用量统计



## 测试

```bash
mvn test
```

覆盖混合检索重排、缓存淘汰、鉴权限流、embedding 缓存等核心逻辑（25+ 个单测）；`AiCodeHelperServiceTest` 为真实调用 LLM 的集成验证，默认跳过，可在 IDEA 中手动执行。


## 致谢

- [LangChain4j](https://github.com/langchain4j/langchain4j) - 强大的 AI 应用开发框架
- [智谱 GLM](https://open.bigmodel.cn/) - 免费的国产大语言模型
- [MiniMax](https://platform.minimaxi.com/) - 向量模型
- [Milvus](https://milvus.io/) - 开源向量数据库
- [Spring Boot](https://spring.io/projects/spring-boot) - 简化的 Java 开发框架
- [Vue.js](https://vuejs.org/) - 渐进式 JavaScript 框架
