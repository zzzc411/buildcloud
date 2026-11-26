/**
 * 合同资金域（D17：contract 服务并入 project 服务，以包内分包隔离）。
 *
 * 1. 本包覆盖合同、收付款计划、合同变更；
 * 2. Controller URL 统一以 /buildcloud-project/contract/ 开头，复用 project 服务的网关路由；
 * 3. DataObject 表名以 bc_contract_ 开头；
 * 4. 与项目域（com.buildcloud.project）跨域协作只走 Service 接口，禁止互相引用 DAL。
 */
package com.buildcloud.contract;