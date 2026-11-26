/**
 * 项目域（D17：项目管理与合同资金两个子域在同一服务内以包隔离）。
 *
 * 1. 本包 = 项目域：项目、里程碑、项目成员、项目数据权限；
 * 2. 合同资金域在 com.buildcloud.contract 包内隔离，两域之间禁止互相引用 DAL；
 * 3. Controller URL 统一以 /buildcloud-project/ 开头（合同域为 /buildcloud-project/contract/），
 *    由 web starter 统一拼接 /admin-api 前缀，保证两个子域共用同一条网关路由；
 * 4. DataObject 表名以 bc_project_ 开头，方便在数据库中区分。
 */
package com.buildcloud.project;