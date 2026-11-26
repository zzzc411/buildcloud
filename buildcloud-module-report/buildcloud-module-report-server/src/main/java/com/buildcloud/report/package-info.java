/**
 * 报表域：异步导出任务、汇总看板（spec §5.4）。
 *
 * 1. Controller URL 统一以 /buildcloud-report/ 开头，由 web starter 拼接 /admin-api 前缀；
 * 2. DataObject 表名以 bc_report_ 开头，方便在数据库中区分；
 * 3. 大数据量导出走 XXL-Job 分片广播（W4/W6 落地）。
 */
package com.buildcloud.report;