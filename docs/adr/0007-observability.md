# 0007 — 可观测性：SkyWalking + Prometheus + Grafana + Loki

日期：2026-09-28 · 状态：Accepted

## Context

七服务分布式系统没有链路追踪无法排查问题；压测数据（RT/QPS 曲线）需要指标体系承载；SkyWalking 在国内生态认知度最高。

## Decision

- **链路追踪**：SkyWalking（字节码增强零侵入，agent 方式接入各服务）
- **指标**：各服务暴露 Actuator + Micrometer，Prometheus 抓取，Grafana 出盘（QPS/RT/错误率/JVM）
- **日志**：Loki（单机友好，比 ELK 轻一个量级），Grafana 统一查询
- **告警**：Grafana Alerting → 钉钉/飞书 webhook
- 本地 docker-compose 与未来生产环境同构，部署差异最小化

## Consequences

- 观测栈常驻约 2-3GB 内存，开发机（32GB）无压力；未来 8G 生产服务器需精简保留
- 压测时的 RT 曲线、链路火焰图可直接归档为项目成果素材
