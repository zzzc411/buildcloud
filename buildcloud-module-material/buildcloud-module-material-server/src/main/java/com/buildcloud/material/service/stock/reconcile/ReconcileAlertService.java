package com.buildcloud.material.service.stock.reconcile;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import com.buildcloud.material.framework.job.config.ReconcileProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对账差异告警（spec v1.2 §6.1 步骤⑦「差异告警（站内信）+ 修复」）
 *
 * ========== 降级方案（任务书要求说明，这里是完整口径） ==========
 *
 * | 情形 | 行为 | 为什么 |
 * | --- | --- | --- |
 * | 未配置接收人（`alertUserIds` 为空） | **不打站内信**，只打 error 日志 | 默认配置里没有任何真实 userId 可写；凭空写死一个 id 会在某天变成"发给不存在的人"，而 error 日志至少留在日志盘上。**明确降级 > 发错人** |
 * | RPC 返回 `code != 0` | 记 error 日志（含 userId 与 code），继续发给下一个人 | 单人失败不能中断整条告警链 |
 * | RPC 抛异常（system-server 未启动/网络不通/Feign 超时） | **catch 住**，记 error 日志，不影响对账结论 | 告警是**旁路**：告警通道挂了不代表账是平的，也不代表账是不平的。让告警异常冒到 Job 上会把「一轮对账已完成并落库」变成「Job 失败」，进而丢掉本轮快照——那是拿账务审计换告警 |
 * | 差异数未超阈值 | 完全不调用告警 | 阈值默认 0（有任何差异就告警），见 {@link ReconcileProperties#getAlertThreshold()} |
 *
 * **为什么日志用 error 而不是 warn**：差异意味着库存与缓存已经不平，按基座口径这属于
 * 「需要人介入的异常」，走到 error 才会被日志告警规则（files 级别 ≥ ERROR）抓走。
 * 用 warn 会让这类事件落在"看起来只是提示"的层级。
 *
 * **与「修复」的关系**：告警**不进事务**、也**不影响**修复结果。先落库快照 → 再告警，
 * 这样即便告警通道全挂，`bc_reconcile_log` 里仍有本轮结论可查（审计不丢）。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class ReconcileAlertService {

    @Resource
    private ReconcileProperties reconcileProperties;

    /**
     * 站内信客户端（system-server 的 RPC）。
     *
     * 用 `@Resource` 注入的前提是 {@code RpcConfiguration} 里显式
     * {@code @EnableFeignClients(NotifyMessageSendApi.class)}（L2-15：加了依赖还不够）。
     * 若将来有人删掉那条声明，**material 服务会启动失败**——这是刻意的：
     * 告警通道是 spec §6.1 步骤⑦ 的验收项，静默降级成"永远不发"会让对账看起来在工作、
     * 实际从不告警。真正的降级只发生在**运行期**（未配置接收人 / RPC 抛异常），
     * 见 {@link #alert(String, String, int)} 与类注释「降级方案」表。
     */
    @Resource
    private NotifyMessageSendApi notifyMessageSendApi;

    /**
     * 按阈值判定是否告警
     *
     * @param diffCount 本轮检出差异条数
     * @return 是否应告警（`diffCount > alertThreshold`）
     */
    public boolean shouldAlert(int diffCount) {
        return diffCount > reconcileProperties.getAlertThreshold();
    }

    /**
     * 发送一轮对账的差异告警（站内信 + error 日志）
     *
     * @param batchNo  对账批次号
     * @param summary  人类可读摘要（如 "D1=2（已修 2） D3=1（已修 0）"）
     * @param diffCount 本轮检出差异总数
     */
    public void alert(String batchNo, String summary, int diffCount) {
        // ① 无论如何先打 error 日志：它是**唯一不依赖外部服务**的告警通道，
        //    也是站内信降级后的兜底（任务书：如不可用则降级为 error 日志 + 说明）
        log.error("[alert][库存对账发现差异 batchNo={} diffCount={} threshold={} detail={}]",
                batchNo, diffCount, reconcileProperties.getAlertThreshold(), summary);

        // ② 站内信（按接收人列表逐个发，单人失败不影响其他人）
        List<Long> userIds = parseUserIds(reconcileProperties.getAlertUserIds());
        if (userIds.isEmpty()) {
            log.warn("[alert][未配置告警接收人（buildcloud.material.reconcile.alert-user-ids），"
                    + "本次仅记录 error 日志，未发送站内信 batchNo={}]", batchNo);
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("batchNo", batchNo);
        params.put("diffCount", diffCount);
        params.put("summary", summary);
        sendInAppNotifications(batchNo, userIds, params);
    }

    /**
     * 发送站内信（**吞掉一切异常**，理由见类注释「降级方案」表）
     *
     * @param batchNo 批次号（日志用）
     * @param userIds 接收人
     * @param params  模板参数
     * @return 发送成功的人数（便于单测/调用方判断，但不影响对账结论）
     */
    private int sendInAppNotifications(String batchNo, List<Long> userIds, Map<String, Object> params) {
        int sent = 0;
        for (Long userId : userIds) {
            try {
                NotifySendSingleToUserReqDTO reqDTO = new NotifySendSingleToUserReqDTO();
                reqDTO.setUserId(userId);
                reqDTO.setTemplateCode(reconcileProperties.getNotifyTemplateCode());
                reqDTO.setTemplateParams(params);
                CommonResult<Long> result = notifyMessageSendApi.sendSingleMessageToAdmin(reqDTO);
                if (result == null || !CommonResult.isSuccess(result.getCode())) {
                    // 返回码非 0（如模板不存在）：这条告警没送出去，但账务结论已经落库，
                    // 记 error 让运维从日志补看，不抛出
                    log.error("[sendInAppNotifications][站内信发送失败 batchNo={} userId={} code={} msg={}]",
                            batchNo, userId, result == null ? null : result.getCode(),
                            result == null ? null : result.getMsg());
                    continue;
                }
                sent++;
            } catch (Exception e) {
                // system-server 未启动 / 网络不通 / 超时：**绝不外抛**（外抛会让整轮对账变成失败）
                log.error("[sendInAppNotifications][站内信发送异常（已降级为日志）batchNo={} userId={}]",
                        batchNo, userId, e);
            }
        }
        return sent;
    }

    /**
     * 解析接收人列表（逗号分隔；忽略空段与非数字）
     *
     * 为什么不抛异常而是跳过：配置写错（多一个逗号 / 混入注释）不该让对账 Job 挂掉——
     * 与"未配置接收人"同属降级情形，只记 warn。
     *
     * @param raw 原始配置值
     * @return 解析成功的 userId 列表（可能为空）
     */
    static List<Long> parseUserIds(String raw) {
        List<Long> userIds = new ArrayList<>();
        if (StrUtil.isBlank(raw)) {
            return userIds;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                userIds.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                log.warn("[parseUserIds][告警接收人配置项不是数字，已忽略 value={}]", trimmed);
            }
        }
        return userIds;
    }

}
