package com.buildcloud.material.service.requisition;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionPageReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionSaveReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionItemMapper;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.enums.requisition.RequisitionApprovalStatusEnum;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.service.requisition.strategy.RequisitionSubmitHandler;
import com.buildcloud.material.service.requisition.strategy.RequisitionSubmitHandlerFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_CANCEL;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_ITEM_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_ITEM_MATERIAL_DUPLICATE;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_STATUS_INVALID;

/**
 * 材料申领 Service 实现类（**V1 同步链路的核心，改动前必读类注释**）
 *
 * ========== 一、事务边界（spec §6.1 V1 原文，一个字都不能少） ==========
 *
 * 「提交申领 → **同一事务内** `SELECT ... FOR UPDATE` 锁库存行 → 校验并扣减 → 写申领单与流水」
 *
 * {@link #submitRequisition} 整个方法一个本地事务，包含 5 个写入动作：
 * ① 单据状态 0→1（CAS）、② 逐行锁库存扣减、③ 每行一条流水、④ 明细落库、⑤ 单据状态 1→2（CAS）。
 * 任一失败整体回滚——**不留脏数据**是硬验收项：库存不减少、流水不产生、单据仍是草稿。
 * 若把扣减与写单拆成两个事务，就会出现「库存少了但没有单据」这种没有补偿路径的黑洞。
 *
 * ========== 二、为什么状态推进要 CAS（`WHERE status = 期望值`） ==========
 *
 * 库存行锁只能保证「每次扣减原子」，拦不住「同一张单据被提交两次」：
 * 双击/网关重试/压测脚本重投都会让第二个请求也走到扣减逻辑，库存被扣两遍。
 * 因此单据状态推进用 {@code RequisitionMapper#updateStatusCas} 的条件更新：
 * 第二个请求影响行数 0 → 立刻抛状态非法 → 整个事务回滚（连它已扣的库存一起退掉）。
 *
 * ========== 三、加锁顺序（防死锁的唯一约定） ==========
 *
 * 所有路径统一为：**先锁单据行（CAS UPDATE）→ 再按 materialId 升序锁库存行**。
 * - submit：0→1 CAS（锁单据）→ 升序锁库存 → 1→2 CAS（已在同一行上，重入不阻塞）；
 * - cancel：2→9 CAS（锁单据）→ 升序锁库存回补。
 * 两个动作对「单据行」和「库存行」的申请顺序一致，因此不会 AB-BA 死锁。
 *
 * ========== 四、V1/V2 的边界（W5 要做什么，写在最前面避免下一个 worker 走错） ==========
 *
 * 本类的 {@link #submitRequisition} 到「1→2 CAS」为止就是 V1 的全部；
 * 库存怎么扣（行锁同步 vs Redis 预扣）、流水什么时候写（同事务 vs 消费者线程）**全部委托给**
 * {@link RequisitionSubmitHandler} 的实现。W5 做 V2 时：
 * - 改的是 `RequisitionV2SubmitHandler`（Lua 预扣 + MQ），**不需要动本类**；
 * - 本类的 ①④⑤（单据与明细的落库）在 V2 里仍然要做，只是 ⑤ 从「同步推进到 2」变成
 *   「保持在 1，等消费者推进」。那个差异点已经抽成 `handler.submit` 的契约（见接口注释），
 *   届时把 ⑤ 改成「由 handler 决定」即可，改动面仍是本类的几行。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class RequisitionServiceImpl implements RequisitionService {

    /**
     * 申领单号前缀：REQ-yyyyMMdd-XXXXXX
     *
     * 为什么不用数据库序列：bc_requisition 的 id 是自增主键，但 req_no 要进流水 biz_no、
     * 要给人看、要被 W5 的 MQ 当幂等键。带日期前缀 + 随机后缀的单号，
     * 既能人工肉眼辨认日期（对账时按单号就能定位批次），又不依赖任何全局序列（分库后也安全）。
     * 随机段取 6 位大写十六进制：同一毫秒内 16^6 = 1677 万组合，测试/压测并发下碰撞概率可忽略；
     * 真碰撞由真库 uk_req_no 兜底（ADR-004 里 bc_requisition 归 A 类，Service 侧也会先查）。
     */
    private static final String REQ_NO_PREFIX = "REQ";

    private static final DateTimeFormatter REQ_NO_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 单号随机段长度（见 {@link #REQ_NO_PREFIX} 注释的碰撞概率估算）
     */
    private static final int REQ_NO_RANDOM_LENGTH = 6;

    /**
     * 提交后的超时关闭时间偏移：30 分钟（spec §6.1 第 ⑥ 步「延迟消息（30 分钟）」）
     *
     * V1 写这个值不是给自己用（V1 立即扣减，没有"待审批超时"的概念），
     * 而是让 W5 的延迟消息能直接接管：到期时核对 `status==1 && timeout_time<=now()` 再关单。
     * 常量放这里而不是硬编码在方法里，是为了 W5 调整时长时只改一处。
     */
    static final int TIMEOUT_MINUTES = 30;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private RequisitionItemMapper requisitionItemMapper;

    @Resource
    private RequisitionSubmitHandlerFactory submitHandlerFactory;

    // ========== 建单（草稿） ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRequisition(RequisitionSaveReqVO createReqVO) {
        // 1. 校验明细（空 / 重复物料 / 数量非正）
        List<RequisitionItemDO> items = validateAndBuildItems(createReqVO);

        // 2. 申请人：VO 显式传入优先（管理端代录/脚本），否则取登录态
        Long applicantUserId = resolveApplicantUserId(createReqVO.getApplicantUserId());

        // 3. 建单：固定为草稿态，不动库存
        RequisitionDO requisition = RequisitionDO.builder()
                .projectId(createReqVO.getProjectId())
                .applicantUserId(applicantUserId)
                .status(RequisitionStatusEnum.DRAFT.getStatus())
                .approvalStatus(RequisitionApprovalStatusEnum.NOT_START.getStatus())
                .remark(createReqVO.getRemark())
                // req_no 在建单时就生成：草稿态也有单号，便于现场口头/纸面沟通时唯一指代
                // （"把 REQ-20261127-A3F2C1 那张单改一下"比 id=17 可读得多）
                .reqNo(generateReqNo())
                .build();
        requisitionMapper.insert(requisition);

        // 4. 落明细
        items.forEach(item -> {
            item.setRequisitionId(requisition.getId());
            requisitionItemMapper.insert(item);
        });

        log.info("[createRequisition][建单成功 id={} reqNo={} projectId={} itemCount={}]",
                requisition.getId(), requisition.getReqNo(), requisition.getProjectId(), items.size());
        return requisition.getId();
    }

    // ========== 提交（V1 同步扣减的核心） ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RequisitionDO submitRequisition(Long id) {
        // 1. 校验单据存在 + 状态必须是草稿
        RequisitionDO requisition = validateRequisitionExists(id);
        if (!RequisitionStatusEnum.DRAFT.getStatus().equals(requisition.getStatus())) {
            throw exception(REQUISITION_STATUS_INVALID, statusName(requisition.getStatus()),
                    RequisitionStatusEnum.SUBMITTED.getName());
        }
        // 状态机单点校验（canTransfer 是唯一实现，这里再走一次，保证「先校验再推进」的顺序可读）
        if (!RequisitionStatusEnum.canTransfer(requisition.getStatus(),
                RequisitionStatusEnum.SUBMITTED.getStatus())) {
            throw exception(REQUISITION_STATUS_INVALID, statusName(requisition.getStatus()),
                    RequisitionStatusEnum.SUBMITTED.getName());
        }

        // 2. 明细必须非空（建单已校验，但库里可能被人为删空，这里以库为准）
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(id);
        if (items == null || items.isEmpty()) {
            throw exception(REQUISITION_ITEM_EMPTY);
        }

        // 3. 状态 草稿(0) → 已提交(1)，同时写 timeout_time（+30 分钟，W5 延迟消息用）
        //    CAS：WHERE status = 0。若并发第二个 submit，这里影响行数 0 → 立刻失败并整体回滚，
        //    防止「同一单据扣两次库存」（见类注释第二节）
        //    注意 timeoutTime 只算**一次**并复用给内存态与 SQL：若两处各调 LocalDateTime.now()，
        //    响应体里的值会与库里差几毫秒，压测断言"返回超时时间 == 库值"时会偶发失败。
        LocalDateTime timeoutTime = LocalDateTime.now().plusMinutes(TIMEOUT_MINUTES);
        Integer fromStatus = requisition.getStatus();
        Integer toStatus = RequisitionStatusEnum.SUBMITTED.getStatus();
        int casRows = requisitionMapper.updateStatusCas(id, fromStatus, toStatus,
                RequisitionApprovalStatusEnum.NOT_START.getStatus(), timeoutTime);
        if (casRows != 1) {
            log.warn("[submitRequisition][单据状态已被并发修改 id={} 期望 status={}]", id, fromStatus);
            throw exception(REQUISITION_STATUS_INVALID, statusName(fromStatus), statusName(toStatus));
        }
        requisition.setStatus(toStatus);
        requisition.setTimeoutTime(timeoutTime);

        // 4. 委托给模式对应的策略：V1 = 行锁同步扣减 + 写流水（V2 = W5 的 Lua 预扣，当前抛未实现）
        //    getHandler() 每次调用都读配置，因此切换模式不需要重启（见工厂类注释）
        RequisitionSubmitHandler handler = submitHandlerFactory.getHandler();
        handler.submit(requisition, items);

        // 5. V1 专用：扣减成功后把单据推进到 已扣减(2)。
        //    为什么这一步放在 Service 而不是 handler 里：状态推进属于「单据生命周期」，
        //    与「库存怎么扣」是两件事。V2 时这一步由 MQ 消费者执行（handler 内部），
        //    届时这里改成「若 handler 已代为推进则跳过」即可，改动面只有这几行。
        if (RequisitionStatusEnum.SUBMITTED.getStatus().equals(requisition.getStatus())) {
            int deductRows = requisitionMapper.updateStatusCas(id,
                    RequisitionStatusEnum.SUBMITTED.getStatus(),
                    RequisitionStatusEnum.DEDUCTED.getStatus(),
                    RequisitionApprovalStatusEnum.NOT_START.getStatus(),
                    null);
            if (deductRows != 1) {
                // 走到这里说明：本事务刚把单据改成 1，紧接着改 2 却失败——只可能是单据被
                // 另一个事务抢先改走（不该发生，因为第一步 CAS 已在单据行上持有排他锁到提交）。
                // 保守处理：抛错回滚，绝不"部分成功"。
                log.error("[submitRequisition][已提交→已扣减推进失败 id={}]", id);
                throw exception(REQUISITION_STATUS_INVALID,
                        RequisitionStatusEnum.SUBMITTED.getName(),
                        RequisitionStatusEnum.DEDUCTED.getName());
            }
            requisition.setStatus(RequisitionStatusEnum.DEDUCTED.getStatus());
        }

        log.info("[submitRequisition][提交完成 id={} reqNo={} status={} mode={}]",
                id, requisition.getReqNo(), requisition.getStatus(),
                submitHandlerFactory.getActiveMode());
        return requisition;
    }

    // ========== 取消（回补库存 + 冲正流水） ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RequisitionDO cancelRequisition(Long id) {
        // 1. 校验单据存在
        RequisitionDO requisition = validateRequisitionExists(id);

        // 2. 业务前置：只有已提交(1)/已扣减(2) 可取消（任务书口径）
        if (!RequisitionStatusEnum.canCancel(requisition.getStatus())) {
            throw exception(REQUISITION_CANNOT_CANCEL, statusName(requisition.getStatus()));
        }
        // 3. 状态机单点校验（与上一条互补：canCancel 是业务口径，canTransfer 是状态图口径）
        if (!RequisitionStatusEnum.canTransfer(requisition.getStatus(),
                RequisitionStatusEnum.CANCELED.getStatus())) {
            throw exception(REQUISITION_STATUS_INVALID, statusName(requisition.getStatus()),
                    RequisitionStatusEnum.CANCELED.getName());
        }

        // 4. CAS 推进到已取消(9)：同样是「判断 + 写入」一条语句，防重复取消导致重复回补
        int casRows = requisitionMapper.updateStatusCas(id, requisition.getStatus(),
                RequisitionStatusEnum.CANCELED.getStatus(),
                RequisitionApprovalStatusEnum.CANCEL.getStatus(), null);
        if (casRows != 1) {
            log.warn("[cancelRequisition][单据状态已被并发修改 id={} 期望 status={}]",
                    id, requisition.getStatus());
            throw exception(REQUISITION_STATUS_INVALID, statusName(requisition.getStatus()),
                    RequisitionStatusEnum.CANCELED.getName());
        }

        // 5. 回补库存 + 写冲正流水（biz_type=4，quantity 正数）
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(id);
        if (items == null || items.isEmpty()) {
            // 明细被删空的单据理论上不存在（建单就落库），但若发生：已经没有"该退多少"的依据，
            // 只能报错回滚，交由人工核对。静默跳过 = 账永久不平。
            throw exception(REQUISITION_ITEM_EMPTY);
        }
        submitHandlerFactory.getHandler().cancel(requisition, items);

        // 6. 同步内存态再返回 —— CAS 只在库里改了 status/approval_status，
        //    内存里的 DO 仍是旧值（**联调实证踩到**：cancel 接口返回 approvalStatus=-1，
        //    而库里已是 4，前端拿返回值判断会显示"审批未开始"）。
        //    这类"库里对、响应体错"的缺陷单测很难发现（单测断言的是重新 selectById 的库值），
        //    只在真实 HTTP 响应体上暴露，故这里必须逐字段回填。
        requisition.setStatus(RequisitionStatusEnum.CANCELED.getStatus());
        requisition.setApprovalStatus(RequisitionApprovalStatusEnum.CANCEL.getStatus());
        log.info("[cancelRequisition][取消完成 id={} reqNo={} itemCount={}]",
                id, requisition.getReqNo(), items.size());
        return requisition;
    }

    // ========== 查询 ==========

    @Override
    public RequisitionDO getRequisition(Long id) {
        return requisitionMapper.selectById(id);
    }

    @Override
    public List<RequisitionItemDO> getRequisitionItemList(Long requisitionId) {
        return requisitionItemMapper.selectListByRequisitionId(requisitionId);
    }

    @Override
    public PageResult<RequisitionDO> getRequisitionPage(RequisitionPageReqVO pageReqVO) {
        return requisitionMapper.selectPage(pageReqVO);
    }

    // ========== 校验与辅助方法 ==========

    /**
     * 校验单据存在
     *
     * @param id 申领单编号
     * @return 单据
     */
    private RequisitionDO validateRequisitionExists(Long id) {
        if (id == null) {
            throw exception(REQUISITION_NOT_EXISTS);
        }
        RequisitionDO requisition = requisitionMapper.selectById(id);
        if (requisition == null) {
            throw exception(REQUISITION_NOT_EXISTS);
        }
        return requisition;
    }

    /**
     * 校验建单明细并转成 DO 列表
     *
     * 三层校验（VO 注解已挡了「空列表 / 非正数」，这里再查一遍是因为 Service 可能被内部代码直接调用）：
     * 1. 明细非空；
     * 2. 每行 quantity > 0；
     * 3. **同一物料不得重复**——V1 逐行加锁扣减时，同物料两行没有意义且语义不清
     *    （MySQL 下同事务内二次 FOR UPDATE 是重入的，不报错，但两次校验各自看到的可用量不同），
     *    合并成一行才是唯一清晰的语义（错误码见 REQUISITION_ITEM_MATERIAL_DUPLICATE）。
     *
     * @param reqVO 建单请求
     * @return 明细 DO 列表（未设置 requisitionId，由调用方填）
     */
    private List<RequisitionItemDO> validateAndBuildItems(RequisitionSaveReqVO reqVO) {
        if (reqVO.getItems() == null || reqVO.getItems().isEmpty()) {
            throw exception(REQUISITION_ITEM_EMPTY);
        }
        Set<Long> materialIds = new HashSet<>();
        List<RequisitionItemDO> items = reqVO.getItems().stream()
                .map(itemVO -> {
                    if (itemVO.getQuantity() == null
                            || itemVO.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                        throw invalidParamException("申领数量必须大于 0");
                    }
                    if (!materialIds.add(itemVO.getMaterialId())) {
                        throw exception(REQUISITION_ITEM_MATERIAL_DUPLICATE, itemVO.getMaterialId());
                    }
                    return RequisitionItemDO.builder()
                            .materialId(itemVO.getMaterialId())
                            .quantity(itemVO.getQuantity())
                            .remark(itemVO.getRemark())
                            .build();
                })
                .toList();
        return items;
    }

    /**
     * 解析申请人：VO 优先，其次登录态
     *
     * 两者都为空 → 参数异常。不允许落「无申请人」的单据：
     * 申领是**追责性业务**（谁领的料、用在哪个项目），缺了申请人这张单在审计上不成立。
     *
     * @param applicantUserId VO 传入的申请人（可为 null）
     * @return 申请人编号
     */
    private Long resolveApplicantUserId(Long applicantUserId) {
        if (applicantUserId != null) {
            return applicantUserId;
        }
        Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
        if (loginUserId == null) {
            throw invalidParamException("申请人不能为空（未登录且未显式指定 applicantUserId）");
        }
        return loginUserId;
    }

    /**
     * 生成申领单号：REQ-yyyyMMdd-XXXXXX
     *
     * 与入库单号（{@code StockServiceImpl#generateInboundBizNo}）同一风格，便于全链路单号可读。
     * 唯一性保障：真库 uk_req_no（ADR-004 A 类，Service 侧可再查）+ 6 位随机段。
     * 这里**不做**「查库确认不存在」的重试循环——那会让建单多一次往返，且并发下也挡不住竞态；
     * 唯一索引 + 6 位随机段的碰撞概率已足够低，真撞了会抛 DuplicateKeyException 由上层返回可读错误。
     *
     * @return 申领单号
     */
    private String generateReqNo() {
        return REQ_NO_PREFIX + "-" + LocalDate.now().format(REQ_NO_DATE_FORMATTER) + "-"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, REQ_NO_RANDOM_LENGTH).toUpperCase();
    }

    /**
     * 状态值转可读名字（错误码文案用；未知值原样回显，便于排障时看到脏数据）
     *
     * @param status 状态值
     * @return 状态名
     */
    private String statusName(Integer status) {
        RequisitionStatusEnum statusEnum = RequisitionStatusEnum.valueOf(status);
        return statusEnum != null ? statusEnum.getName() : Objects.toString(status, "未知");
    }

}
