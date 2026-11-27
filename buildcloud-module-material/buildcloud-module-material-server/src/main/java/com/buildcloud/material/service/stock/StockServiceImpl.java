package com.buildcloud.material.service.stock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.material.controller.admin.stock.vo.StockInboundReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockLogPageReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_INBOUND_QUANTITY_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_UPDATE_CONFLICT;

/**
 * 项目库存 Service 实现类
 *
 * ========== 入库的事务与并发设计（本类核心，改动前必读） ==========
 *
 * 1. **事务边界**：{@link #inbound} 整个方法一个本地事务。库存行更新与流水写入必须同成同败
 *    （spec §6.1 V1 事务边界；只写一半会让余额与流水永久不一致，且没有补偿路径）。
 *
 * 2. **乐观锁而非行锁**：入库走 `UPDATE ... SET available_qty = available_qty + ?,
 *    version = version + 1 WHERE id = ? AND version = ?` 的 CAS。为什么不用 SELECT ... FOR UPDATE：
 *    - FOR UPDATE 是 V1 申领扣减（W4-P4，spec §6.1 明确标注为「行锁串行化、压测对照组」）的做法；
 *      入库是低频管理动作，用 CAS 足够，且不引入长事务持锁；
 *    - 两者的对照（行锁 vs 乐观锁）正是 W5 压测要讲的故事，提前把入库写成行锁会污染对照组。
 *
 * 3. **冲突重试**：CAS 影响行数 0 = 读到的 version 已被别人推进。策略是「重新读 → 再 CAS」，
 *    最多 {@link #MAX_UPDATE_RETRY_TIMES} 次，每次失败后短暂退避（指数退避、上限见
 *    {@link #RETRY_BACKOFF_BASE_MILLIS}）。为什么重试而不是直接报错：入库冲突是**正常的并发现象**
 *    而非错误，直接报错会让并发入库的用户白白失败；为什么有限次：极端热点下无限重试会拖住请求线程
 *    并可能耗尽连接池，快速失败 + 让调用方重试更安全（错误码 STOCK_UPDATE_CONFLICT 带上次数）。
 *
 * 4. **balance_after 的取值**：用「CAS 成功的那个快照 + 本次数量」算出，**不回查**。
 *    因为 CAS 语义保证了「我们读到的 version 到写入成功之间没人改过这一行」，所以
 *    新余额 = 读到的 available_qty + quantity 必然等于库里的真实值；而且本事务已经持有该行的
 *    排他锁直到提交，别的写入进不来。少一次查询，也避免「回查→再被改」的错觉。
 *
 * 5. **首次入库的并发**：两个线程同时发现「没有库存行」时会各插一行，靠 uk(tenant_id, project_id,
 *    material_id) 兜底，后者抛 DuplicateKeyException；本类捕获它、转入「更新已有行」的正常路径
 *    （DDL 里那个唯一索引存在的意义就在这里）。MySQL/H2 下唯一键冲突不会让当前事务失效，
 *    故可以在同一事务内捕获后继续。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class StockServiceImpl implements StockService {

    /**
     * 乐观锁冲突的最大重试次数（含首次尝试）
     *
     * 取 3：并发入库同一项目同一物料的场景极少超过「读-写」窗口两次被插队；
     * 再多次只会拖长请求而不提升成功率，反而把热点问题掩盖成慢请求。
     */
    static final int MAX_UPDATE_RETRY_TIMES = 3;

    /**
     * 重试退避的基础毫秒数（第 n 次重试等待 n * 该值）
     *
     * 冲突时对方事务通常已在毫秒级内提交，短退避即可显著提高第二次 CAS 的成功率；
     * 逐次递增是为了避免「多个线程同步重试 → 再次同时撞车」的活锁。
     */
    static final long RETRY_BACKOFF_BASE_MILLIS = 20L;

    /**
     * 入库单号前缀
     */
    private static final String BIZ_NO_PREFIX = "IN";

    private static final DateTimeFormatter BIZ_NO_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    // ========== 入库 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockLogDO inbound(StockInboundReqVO reqVO) {
        // 1. 业务校验再查一次（VO 已校验，这里防内部直接调用 Service 绕过 VO）
        validateInboundQuantity(reqVO.getQuantity());

        // 2. 乐观锁 upsert：拿到「更新后的库存快照」
        MaterialStockDO stock = upsertStockForInbound(reqVO.getProjectId(), reqVO.getMaterialId(),
                reqVO.getQuantity(), reqVO.getRemark());

        // 3. 写流水：biz_type=1 入库，quantity 记正数，balance_after=更新后的 available_qty
        StockLogDO stockLog = StockLogDO.builder()
                .projectId(reqVO.getProjectId())
                .materialId(reqVO.getMaterialId())
                .bizType(StockBizTypeEnum.INBOUND.getBizType())
                .bizNo(generateInboundBizNo())
                .quantity(reqVO.getQuantity())
                .balanceAfter(stock.getAvailableQty())
                // 备注分开记：库存行记最后一次入库备注，流水记本次备注（流水才是审计口径）
                .remark(reqVO.getRemark())
                .build();
        stockLogMapper.insert(stockLog);

        log.info("[inbound] 入库成功 projectId={} materialId={} qty={} balanceAfter={} version={} bizNo={}",
                reqVO.getProjectId(), reqVO.getMaterialId(), reqVO.getQuantity(),
                stock.getAvailableQty(), stock.getVersion(), stockLog.getBizNo());
        return stockLog;
    }

    /**
     * 入库的乐观锁 upsert：不存在则插入，存在则 CAS 累加（含冲突重试）
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param quantity   入库数量（> 0）
     * @param remark     备注（写入库存行，记录「最近一次入库备注」）
     * @return CAS 成功后的库存快照（availableQty / version 均为更新后的值）
     */
    private MaterialStockDO upsertStockForInbound(Long projectId, Long materialId,
                                                  BigDecimal quantity, String remark) {
        // 首次尝试前先查一次：绝大多数入库是「已有库存行」的累加，这一次查询也用于决定插入还是更新
        for (int attempt = 1; attempt <= MAX_UPDATE_RETRY_TIMES; attempt++) {
            MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
            // 情况一：没有库存行 → 新建（首笔入库）
            if (stock == null) {
                MaterialStockDO inserted = tryInsertStock(projectId, materialId, quantity, remark);
                if (inserted != null) {
                    return inserted;
                }
                // 插入时撞唯一键：说明别的线程刚刚建了这一行，直接进入下一轮的「更新」路径
                log.debug("[upsertStockForInbound] 并发首插撞唯一键，转入更新路径 projectId={} materialId={}",
                        projectId, materialId);
                continue;
            }
            // 情况二：已有库存行 → 乐观锁条件更新
            int updatedRows = materialStockMapper.updateAvailableQtyIncrWithVersion(
                    stock.getId(), quantity, stock.getVersion());
            if (updatedRows == 1) {
                // CAS 成功：新余额 = 读到的余额 + 本次数量（见类注释第 4 点，无需回查）
                stock.setAvailableQty(stock.getAvailableQty().add(quantity));
                stock.setVersion(stock.getVersion() + 1);
                return stock;
            }
            // 情况三：影响行数 0 → 版本冲突（或行被逻辑删除，极端情况；下一轮查询会返回 null 转入插入）
            log.debug("[upsertStockForInbound] 乐观锁冲突，第 {} 次尝试失败 id={} 期望 version={}",
                    attempt, stock.getId(), stock.getVersion());
            backoff(attempt);
        }
        // 有限次重试仍冲突 → 快速失败（不无限重试，避免拖死线程池；见类注释第 3 点）
        throw exception(STOCK_UPDATE_CONFLICT, MAX_UPDATE_RETRY_TIMES);
    }

    /**
     * 尝试插入首笔库存行
     *
     * 为什么捕获 {@link DuplicateKeyException} 而不是先加锁/先查重：
     * 「先查不存在再插入」天然有竞态，唯一索引是唯一可靠的判定；把异常当控制流在这里是合理的，
     * 因为调用方马上会转入「更新」路径，语义正确且无需额外事务。
     *
     * @return 插入成功返回库存快照；撞唯一键返回 null（调用方转入更新路径）
     */
    private MaterialStockDO tryInsertStock(Long projectId, Long materialId,
                                           BigDecimal quantity, String remark) {
        MaterialStockDO stock = MaterialStockDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .availableQty(quantity)
                .lockedQty(BigDecimal.ZERO)
                .version(0)
                .remark(remark)
                .build();
        try {
            materialStockMapper.insert(stock);
            return stock;
        } catch (DuplicateKeyException e) {
            // 并发首插：另一线程抢先建行，交给上层重试的「更新」路径
            return null;
        }
    }

    /**
     * 冲突退避（第 n 次失败后等待 n * 基础值）
     *
     * @param attempt 已失败的尝试次数（从 1 开始）
     */
    private void backoff(int attempt) {
        // 最后一次失败后不再等待（马上要抛异常），避免无意义的尾随延迟
        if (attempt >= MAX_UPDATE_RETRY_TIMES) {
            return;
        }
        try {
            Thread.sleep(RETRY_BACKOFF_BASE_MILLIS * attempt);
        } catch (InterruptedException e) {
            // 恢复中断标记后继续重试：中断不应导致「金额/数量类」写入静默失败
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 生成入库单号：IN-yyyyMMdd-XXXXXXXX
     *
     * 为什么不用数据库自增/序列：bc_stock_log 是 W6 分库分表主表（按 project_id 哈希 4 库 8 表），
     * 自增序列在分片下不唯一；UUID 片段 + 日期既全局唯一又人工可读（对接单/对账时肉眼可辨日期）。
     * 注意 biz_no **不是唯一索引**（一次业务可产生多条流水），这里只求「几乎不重复」。
     *
     * @return 入库单号
     */
    private String generateInboundBizNo() {
        return BIZ_NO_PREFIX + "-" + LocalDate.now().format(BIZ_NO_DATE_FORMATTER) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    // ========== 查询 ==========

    @Override
    public MaterialStockDO getStock(Long id) {
        return materialStockMapper.selectById(id);
    }

    @Override
    public PageResult<MaterialStockDO> getStockPage(StockPageReqVO pageReqVO) {
        return materialStockMapper.selectPage(pageReqVO);
    }

    @Override
    public PageResult<StockLogDO> getStockLogPage(StockLogPageReqVO pageReqVO) {
        return stockLogMapper.selectPage(pageReqVO);
    }

    // ========== 一致性校验 ==========

    @Override
    public MaterialStockDO validateStockBalance(Long projectId, Long materialId) {
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        if (stock == null) {
            throw exception(STOCK_NOT_EXISTS);
        }
        BigDecimal logSum = stockLogMapper.selectSumQuantity(projectId, materialId);
        // SUM 为 null 时（理论上不会，SQL 里已 COALESCE）按 0 处理，避免 NPE 掩盖真实差异
        if (logSum == null) {
            logSum = BigDecimal.ZERO;
        }
        if (stock.getAvailableQty().compareTo(logSum) != 0) {
            // 用 IllegalStateException 而非业务错误码：这是「数据自相矛盾」的完整性事故，
            // 不该被前端当成普通业务提示吞掉，要打日志/告警（对账 Job 的入口）。
            throw new IllegalStateException(String.format(
                    "库存与流水不一致：projectId=%s materialId=%s availableQty=%s SUM(quantity)=%s",
                    projectId, materialId, stock.getAvailableQty(), logSum));
        }
        return stock;
    }

    // ========== 校验方法 ==========

    /**
     * 校验入库数量必须 > 0
     *
     * 入库只走正向增量；减少库存必须走申领扣减/退回/冲正各自的链路，
     * 否则流水的 biz_type 失去意义、无法按类型分组对账（见 StockBizTypeEnum 注释）。
     *
     * @param quantity 入库数量
     */
    private void validateInboundQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw exception(STOCK_INBOUND_QUANTITY_INVALID, quantity);
        }
    }

}
