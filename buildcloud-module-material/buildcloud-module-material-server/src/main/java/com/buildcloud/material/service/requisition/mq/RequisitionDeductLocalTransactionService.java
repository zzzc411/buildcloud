package com.buildcloud.material.service.requisition.mq;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * RocketMQ **事务消息的本地事务侧**（spec §6.1 步骤④）
 *
 * <pre>
 *   ④ 发 RocketMQ 事务消息（half 消息）
 *        └ 本地事务：创建申领单(已提交) + 写 pending 扣减流水
 *        └ commit → 消费者：DB 真实库存扣减（乐观锁 version）
 *                    + stock_log 落库（分片表）+ 状态→已扣减
 *        └ 本地事务失败 → rollback，MQ 丢弃 half 消息
 * </pre>
 *
 * ========== RocketMQ 事务消息的三段时序（先对齐概念，再读代码） ==========
 *
 * <pre>
 *  1) producer 发 half 消息 → Broker 落盘但**对消费者不可见**
 *  2) Broker 回调 {@link RequisitionDeductTransactionListener#executeLocalTransaction} → 本类
 *  3) 返回 COMMIT：消息对消费者可见（消费者随后扣 DB）；返回 ROLLBACK：half 消息被丢弃
 *  4) 若第 3 步结果丢了（producer 宕机/网络超时），Broker 主动回查
 *     {@link RequisitionDeductTransactionListener#checkLocalTransaction} → 查库决定 COMMIT/ROLLBACK
 * </pre>
 *
 * ========== 「pending 扣减流水」本项目的落地方式（任务书要求说明） ==========
 *
 * 任务书给了两个选项：复用 `bc_stock_log` 加 pending 标记，或新增 pending 标记/表。本项目**选前者**，
 * 即：本地事务阶段**不写** pending 流水，只把「申领单推进到已提交(1)」当作 pending 凭证，
 * 保持 `bc_stock_log` **只记已生效的库存事实**。理由（三条，按重要性排序）：
 *
 * 1. **流水表的单一语义**：`bc_stock_log` 是 W6 分库分表的主表，也是对账 Job 的唯一数据源。
 *    它现在的语义是「**每一行 = 一次已生效的库存变动**，`SUM(quantity) == available_qty`」
 *    （`StockServiceImpl#validateStockBalance` 与 W4 的 30+ 条用例都钉着这条不变量）。
 *    往里塞 `pending=1` 的行，要么让 `SUM` 把未生效的量算进去（对账全错），
 *    要么让**每一条**对账/汇总 SQL 都补 `AND pending = 0`——后者是一颗随时会被漏掉的雷
 *    （lessons L3-12 的"两层防线必须成对"就是这类债的典型）。
 * 2. **ADR-004 的口径**：`bc_stock_log` 上已有 `idx_project_biz(project_id, biz_no)` 索引，
 *    若按「`biz_no` + `biz_type` + `pending`」建唯一索引防重，会踩上「逻辑删除 + 唯一索引 = 500」
 *    的老坑；不建唯一索引则 pending 行本身没有幂等保护，等于把问题挪走而不是解决。
 *    （W5-P2 已把「行级幂等判据」做成 `next-key`：`biz_no + biz_type` 计数查询，不改表结构。）
 * 3. **状态机本身就是 persisted pending 标记**：spec §5.2 的「已提交(1)」在 V2 里语义就是
 *    「**Redis 已预扣、DB 尚未扣减**」，它持久在 `bc_requisition.status` 上、由 `deductCas` 独占推进。
 *    单据状态 + Redis 预扣明细（`pm:prededuct:{pid}:{mid}` 里的 reqNo→qty）两者合起来，
 *    已经完整表达了"在途预扣"，再加一个 pending 列是**同一事实的第三份拷贝**，
 *    三份拷贝迟早不同步（对账时无法判断该信谁）。
 *
 * 代价与补偿：对账口径变成
 * `Redis.available = DB.available_qty - Σ(状态=1 的申领单明细)`（P4 的对账 Job 按此实现），
 * 而不是"扫 pending 流水"。这个公式比"pending 流水"更强——它天然要求单据状态与 Redis 明细都能查到。
 *
 * ========== 本地事务里到底做了什么 ==========
 *
 * | 动作 | 为什么必须在这里（而不是消费者） |
 * | --- | --- |
 * | 单据 草稿(0) → 已提交(1)（CAS） | 用户的"提交"动作必须同步生效：接口返回后前端就该看到已提交。放消费者会出现"接口返回成功但列表还是草稿" |
 * | 写 `timeout_time`（+30 分钟） | 延迟关单（P3）据此扫描，必须在提交那一刻落下 |
 * | 校验：单据存在 / 状态可流转 / 明细非空 | 业务前置校验失败属于**用户错误**，必须立刻以错误码告诉用户；若放到消费者，用户收到的是"提交成功但永远没扣" |
 *
 * **不做**的事（也在本类注释里钉死，防止下一个人"顺手加"）：不扣 DB 库存、不写 `bc_stock_log`、
 * 不动 Redis（Redis 预扣已在 {@code RequisitionV2SubmitHandler} 里、消息发送**之前**完成）。
 *
 * ========== 为什么要暴露 {@link #markSubmitted} 而不是把逻辑内联进监听器 ==========
 *
 * 监听器的 {@code executeLocalTransaction} 运行在 RocketMQ 的生产者线程里，**抛异常语义与普通方法不同**
 * （抛异常后 RocketMQ Spring 会把它当成 UNKNOWN 去回查）。为了让「失败 → ROLLBACK」这一段可控，
 * 本类的方法统一**吞掉异常并返回 ROLLBACK**，把"为什么失败"完整写进日志；
 * 同时它也是 {@code @Transactional} 的边界——必须由 Spring 代理调用，否则事务不生效。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class RequisitionDeductLocalTransactionService {

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * 本地事务提交后的动作说明（用于日志/单测断言，避免魔数）
     */
    public static final String PENDING_FLOW_MARK = "已提交(1)：Redis 已预扣、DB 待扣减";

    /**
     * 执行本地事务：把申领单推进到「已提交(1)」并写超时时间
     *
     * **必须返回 {@link RocketMQLocalTransactionState} 而不是抛业务异常**：
     * 监听器层需要把「本地事务是否成功」翻译成 MQ 协议里的 COMMIT/ROLLBACK，
     * 用异常表达会被 RocketMQ Spring 包装成 UNKNOWN（进而触发 60s 一次的频繁回查）。
     *
     * @param reqNo 申领单号（幂等键）
     * @return COMMIT = 本地事务已提交（消息将投递）；ROLLBACK = 本地事务失败（half 消息丢弃）
     */
    @Transactional(rollbackFor = Exception.class)
    public RocketMQLocalTransactionState markSubmitted(String reqNo) {
        if (reqNo == null || reqNo.isBlank()) {
            log.error("[markSubmitted][reqNo 为空，拒绝提交本地事务]");
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        RequisitionDO requisition = requisitionMapper.selectByReqNo(reqNo);
        if (requisition == null) {
            // 走到这里说明消息与库对不上（生产者发了别人的单号 / 单据被物理删除）。
            // 回滚 half 消息是唯一正确选择：继续 COMMIT 会让消费者处理一张不存在的单据。
            log.error("[markSubmitted][本地事务失败：申领单不存在 reqNo={}]", reqNo);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        // 幂等：已经是已提交(1) 或已往后的状态（例如回查重入、生产者重发），视为"本地事务已成"
        Integer status = requisition.getStatus();
        if (isSubmittedOrLater(status)) {
            log.info("[markSubmitted][单据已处于 {}（已提交及之后），本地事务视为已完成 reqNo={}]",
                    status, reqNo);
            return RocketMQLocalTransactionState.COMMIT;
        }
        if (!RequisitionStatusEnum.canTransfer(status, RequisitionStatusEnum.SUBMITTED.getStatus())) {
            log.error("[markSubmitted][本地事务失败：状态不允许流转 reqNo={} from={}]", reqNo, status);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        // CAS 推进：WHERE status = 读到的值，防并发二次提交（与 V1 的 submitRequisition 同一套思路）
        int rows = requisitionMapper.updateStatusCas(requisition.getId(), status,
                RequisitionStatusEnum.SUBMITTED.getStatus(),
                requisition.getApprovalStatus(),
                requisition.getTimeoutTime());
        if (rows != 1) {
            log.error("[markSubmitted][本地事务失败：状态 CAS 未命中 reqNo={} 期望 status={}]", reqNo, status);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        log.info("[markSubmitted][本地事务已提交 reqNo={} status={}→{}（{}）]",
                reqNo, status, RequisitionStatusEnum.SUBMITTED.getStatus(), PENDING_FLOW_MARK);
        return RocketMQLocalTransactionState.COMMIT;
    }

    /**
     * 回查：Broker 问「这条 half 消息的本地事务到底成没成」
     *
     * 判定只看**单据是否处于已提交(1) 及之后**：
     * - 是 → COMMIT（本地事务确实提交过，消费者该去扣 DB 了）；
     * - 否（单据不存在 / 仍是草稿 / 已取消）→ ROLLBACK（本地事务没成，half 消息必须丢弃）。
     *
     * 为什么用「及之后」而不是「== 1」：回查可能在消息已被消费之后才来——
     * 那时单据已是已扣减(2)，本地事务显然成功过，回答 ROLLBACK 会让一条**已消费**的消息被标记丢弃，
     * 破坏 MQ 的事务语义（虽然业务上无副作用，但会给排障制造矛盾证据）。
     *
     * @param reqNo 申领单号
     * @return COMMIT / ROLLBACK
     */
    public RocketMQLocalTransactionState checkSubmitted(String reqNo) {
        if (reqNo == null || reqNo.isBlank()) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        RequisitionDO requisition = requisitionMapper.selectByReqNo(reqNo);
        if (requisition == null) {
            log.warn("[checkSubmitted][回查：申领单不存在，判定 ROLLBACK reqNo={}]", reqNo);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        if (isSubmittedOrLater(requisition.getStatus())) {
            log.info("[checkSubmitted][回查：单据状态={}，判定 COMMIT reqNo={}]", requisition.getStatus(), reqNo);
            return RocketMQLocalTransactionState.COMMIT;
        }
        log.warn("[checkSubmitted][回查：单据状态={}（未提交），判定 ROLLBACK reqNo={}]",
                requisition.getStatus(), reqNo);
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    /**
     * 本地事务补偿：把已提交(1) 撤回草稿(0)（用于「预扣成功但发消息失败」的回退）
     *
     * ========== 为什么补偿要单独一个方法、而且只允许 1 → 0 ==========
     *
     * 生产者发 half 消息失败（Namesrv 不可达 / Broker 满）时，本地事务还没执行，单据仍是草稿；
     * 但若失败发生在「本地事务已 COMMIT、返回结果丢失」的窗口，消息可能会被 Broker 回查后投递，
     * 所以**不能**简单地把单据改回草稿了事。本方法的语义是「**确认消息没发出去**」后的回退，
     * 调用方（{@code RequisitionV2SubmitHandler}）只在 `syncSend` 抛异常时调用它，
     * 并且在此之前已经回滚了 Redis 预扣。
     *
     * 只允许 1 → 0：已扣减(2) 的单据说明消息已经投递并被消费，改回草稿就是**账目倒退**
     * （DB 已扣、单据说没提交），会立刻破坏 `期初 - 已扣 == available` 的断言。
     *
     * @param reqNo 申领单号
     * @return true 回退成功（含"已经是草稿"的幂等情形）；false 状态不适合回退
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean revertToDraft(String reqNo) {
        RequisitionDO requisition = requisitionMapper.selectByReqNo(reqNo);
        if (requisition == null) {
            return false;
        }
        if (RequisitionStatusEnum.DRAFT.getStatus().equals(requisition.getStatus())) {
            return true;
        }
        if (!RequisitionStatusEnum.SUBMITTED.getStatus().equals(requisition.getStatus())) {
            log.error("[revertToDraft][状态={} 不允许回退到草稿（消息可能已被消费，禁止账目倒退）reqNo={}]",
                    requisition.getStatus(), reqNo);
            return false;
        }
        int rows = requisitionMapper.updateStatusCas(requisition.getId(),
                RequisitionStatusEnum.SUBMITTED.getStatus(), RequisitionStatusEnum.DRAFT.getStatus(),
                requisition.getApprovalStatus(), requisition.getTimeoutTime());
        if (rows != 1) {
            log.error("[revertToDraft][状态 CAS 未命中 reqNo={}]", reqNo);
            return false;
        }
        log.warn("[revertToDraft][发消息失败，单据已回退草稿 reqNo={}]", reqNo);
        return true;
    }

    /**
     * 判断状态是否「已提交(1) 及之后」（不含草稿与终态里的取消/关闭）
     *
     * 为什么取消(9)/关闭(8) 不算"本地事务成功"：这两者的语义是「预扣已回滚」，
     * 让 half 消息 COMMIT 会让消费者去扣一个已经没预留的库存 → 直接触发 DB 兜底防线失败进死信。
     *
     * @param status 状态
     * @return 是否已提交或更靠后
     */
    private boolean isSubmittedOrLater(Integer status) {
        if (status == null) {
            return false;
        }
        return RequisitionStatusEnum.SUBMITTED.getStatus().equals(status)
                || RequisitionStatusEnum.DEDUCTED.getStatus().equals(status)
                || RequisitionStatusEnum.OUTBOUND.getStatus().equals(status)
                || RequisitionStatusEnum.COMPLETED.getStatus().equals(status)
                || RequisitionStatusEnum.PARTIAL_RETURNED.getStatus().equals(status);
    }

    /**
     * 计算明细合计数量（本地事务与响应体共用，避免两处各算一遍导致口径漂移）
     *
     * @param quantities 各行数量
     * @return 合计（空集合返回 0，不返回 null）
     */
    public static BigDecimal sumQuantity(List<BigDecimal> quantities) {
        if (quantities == null || quantities.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return quantities.stream().filter(q -> q != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 按 materialId 升序排序（消费者写流水时用，保证多行明细的加锁顺序一致，避免 AB-BA 死锁）
     *
     * 与 V1 的 {@code RequisitionV1SubmitHandler} 完全同一口径——这条约定是跨版本的，
     * 放在这里而不是各写一遍 {@code Comparator.comparing(...)}，是为了它只有一处。
     *
     * @param items 明细
     * @return 排序后的新列表（不修改入参）
     */
    public static List<RequisitionDeductMessage.Item> sortByMaterialId(List<RequisitionDeductMessage.Item> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .sorted(Comparator.comparing(RequisitionDeductMessage.Item::getMaterialId))
                .toList();
    }

    /**
     * 构造一条申领扣减流水（**尚未落库**）
     *
     * 抽成静态工厂而不是在消费者里 builder 一遍：V1 handler / V2 消费者 / 将来的退料冲正
     * 都应产生**同样形状**的流水（biz_type=2、quantity 为负、biz_no=reqNo），
     * 字段口径散在三处迟早分叉（比如某处忘了 negate）。
     *
     * @param reqNo        申领单号
     * @param projectId    项目编号
     * @param materialId   物料编号
     * @param quantity     申领数量（正数）
     * @param balanceAfter 扣减后余额
     * @return 流水（未落库）
     */
    public static StockLogDO buildDeductLog(String reqNo, Long projectId, Long materialId,
                                            BigDecimal quantity, BigDecimal balanceAfter) {
        return StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.REQUISITION_OUT.getBizType())
                .bizNo(reqNo)
                .quantity(quantity.negate())
                .balanceAfter(balanceAfter)
                .remark("申领扣减（V2 异步）：" + reqNo)
                .build();
    }

    /**
     * 查询本单本物料已落库的申领扣减流水条数（消费幂等的行级判据）
     *
     * @param reqNo      申领单号
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 条数
     */
    public Long countDeductLog(String reqNo, Long projectId, Long materialId) {
        return stockLogMapper.selectCountByBizNoAndType(projectId, materialId, reqNo,
                StockBizTypeEnum.REQUISITION_OUT.getBizType());
    }

}
