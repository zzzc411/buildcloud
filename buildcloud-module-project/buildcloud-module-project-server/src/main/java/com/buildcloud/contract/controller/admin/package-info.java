/**
 * Controller 层：对外提供 RESTful 接口。
 *
 * 注意：Controller 需放在 controller.admin / controller.app 子包下，
 * web starter 才会按包路径自动拼接 /admin-api、app-api 前缀（基座 YudaoWebAutoConfiguration 约定）。
 *
 * 合同子域的 Controller 全部位于本包的 contract 子包内，URL 首段统一为
 * /buildcloud-project/contract（AGENTS R-9：首段=服务标识，模块名不进 URL）。
 */
package com.buildcloud.contract.controller.admin;
