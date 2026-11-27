package com.buildcloud.material.controller.admin.stock.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 库存流水分页 Request VO
 *
 * 检索维度对齐 bc_stock_log 的两个索引（DDL 第 7 节）：
 * - projectId + createTime 区间 → idx_project_create(project_id, create_time)：分片内查询主路径，
 *   「某项目某时间段的流水」是最热查询；
 * - projectId + bizNo → idx_project_biz：按业务单号追溯（含冲正）。
 * 两者都以 projectId 打头，W6 分片后不会跨片（spec §6.3）。
 *
 * materialId / bizType 不在索引里，但在「已按 projectId 圈定分片」之后的数据量很小，
 * 作为内存/回表过滤是可接受的；W6 若成为瓶颈再评估索引。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 库存流水分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StockLogPageReqVO extends PageParam {

    @Schema(description = "所属项目编号（**强烈建议传**：既是主查询维度也是 W6 分片键）", example = "1")
    private Long projectId;

    @Schema(description = "物料编号", example = "1")
    private Long materialId;

    @Schema(description = "业务类型：1-入库 2-申领扣减 3-退回 4-冲正", example = "1")
    private Integer bizType;

    @Schema(description = "业务单号（入库单号/申领单号）", example = "IN-20261127-0001")
    private String bizNo;

    @Schema(description = "流水时间区间（对应 create_time）")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
