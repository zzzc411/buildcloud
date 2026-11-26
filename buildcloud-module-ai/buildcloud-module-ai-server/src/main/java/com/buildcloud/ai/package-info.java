/**
 * AI 域：RAG 知识库、智能问答、会话（spec §5.5）。
 *
 * 1. Controller URL 统一以 /buildcloud-ai/ 开头，由 web starter 拼接 /admin-api 前缀；
 * 2. DataObject 表名以 bc_ai_ 开头，方便在数据库中区分；
 * 3. 向量存储用 PostgreSQL + pgvector（W7 落地），LLM 供应商/模型/Key 走 Nacos 动态配置（D19）。
 */
package com.buildcloud.ai;