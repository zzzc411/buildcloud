/**
 * 物料域：物料、库存、申领、库存流水（spec §5.2）。
 *
 * 1. Controller URL 统一以 /buildcloud-material/ 开头，由 web starter 拼接 /admin-api 前缀；
 * 2. DataObject 表名以 bc_material_ 开头，方便在数据库中区分；
 * 3. 高并发扣减链路（Lua 预扣 / 消息幂等 / 分片流水）在 W5 落地。
 */
package com.buildcloud.material;