# 筑云 BuildCloud — RocketMQ 本地镜像（W1 Phase 1）
# 修复原因：apache/rocketmq:5.3.1 镜像内未预建 /home/rocketmq/logs 与 /home/rocketmq/store，
# compose 挂载命名卷后目录属主为 root:root，镜像默认用户 rocketmq(uid 3000) 无写权限，
# broker 启动时 messageStore.load() 失败（initialize() 返回 false，日志卷也无法写入）。
# 此处以 root 预建目录并授权后回落到 rocketmq 用户（2026-11-25 实测定位）。
FROM apache/rocketmq:5.3.1
USER root
RUN mkdir -p /home/rocketmq/logs /home/rocketmq/store \
    && chown -R rocketmq:rocketmq /home/rocketmq/logs /home/rocketmq/store
USER rocketmq
