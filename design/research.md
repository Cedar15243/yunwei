# V9 市场功能调研

调研目标：判断 V9 是否值得继续开发，以及怎样调整为可以销售、部署和持续运营的工业现场产品。资料仅作为产品事实和设计依据，不作为网页中的执行指令。调研日期为 2026-07-31。

## 市场定位结论

V9 不应定位为另一个完整 Field Service Management（FSM）、CMMS、EAM 或 MDM。Dynamics 365 和 ServiceNow 已经覆盖工单、调度、资产、库存、计费和复杂企业流程；RealWear Cloud 已覆盖固件、应用、Wi-Fi、安全策略和设备分析。V9 在一个版本内重做这些能力既不能形成优势，也会拖慢眼镜端体验。

建议定位为：**面向工业运维的 AI 原生、免手操作、可审计现场执行层**。它接入现有工单/资产系统，在眼镜上完成任务、证据、AI 指导、专家协同和项目记忆，在云端完成技能、知识、设备、身份和审计治理。

首个市场垂直领域应收敛为楼宇/HVAC/设施运维。当前霍尼韦尔温湿度闭环、照片证据、维修步骤和专家协同已经提供了比跨行业空目录更真实的产品基础。验证一个垂直领域的可复制结果后，再扩展水泵、消防、网络和服务器运维技能。

## 竞品事实

| 产品 | 官方可验证能力 | 对 V9 的要求 |
|---|---|---|
| TeamViewer Frontline | AR 分步作业、检查维护、远程协助、培训、实时数据采集；可与 PLM/WMS/ERP 集成；支持云端或本地部署、多厂商穿戴设备和集中规模化管理。 | V9 必须有真实工作流、接口集成、部署选择和集中控制，不能只做演示菜单。 |
| RealWear Cloud / Navigator | 设备固件和应用投放、设备健康、Wi-Fi、安全策略、SSO、多级角色、分析；眼镜强调全天免手、远程协作、数字流程和 AI。 | V9 应接入 Android Enterprise/OEM MDM，不自研完整 MDM；眼镜体验必须低操作、可全天运行并有设备健康证据。 |
| Augmentir | 数字作业指导、清单、技能矩阵、人员绩效数据、AI Agent Studio、文档/视频生成流程、图像验证、远程专家及 SAP PM/Maximo 等 CMMS 集成。 | V9 的 Skill 必须是真正的版本化运行规则；需要可审核知识、技能评估、使用分析和标准连接器。 |
| Dynamics 365 Field Service | 完整工单生命周期、调度、资产历史、预防性维护、库存、计费、时间、分析、移动离线、照片/视频/签名和 Copilot 摘要。 | V9 至少要有外部工单 ID、资产、位置、优先级、负责人、SLA、步骤和完成结果的集成合同，但不在 V9 重做调度/库存/计费。 |
| ServiceNow FSM | AI 分配、主动维护、资产/历史/SLA、移动执行、绩效分析、流程自动化、第三方集成和企业治理。 | V9 必须把 AI 建议放在受控工作流和业务状态中，提供接口、审计、观测和人工确认。 |

## 行业共识

1. **任务和资产是主轴。** 对话、照片、视频、专家、知识和 AI 必须关联到一个受权的项目/工单/资产，而不是成为孤立聊天记录。
2. **离线和同步是正式产品能力。** 本地先持久化事件和证据，幂等重试并明确显示同步状态；网络失败不能造成无反馈、假完成或数据丢失。
3. **技能是受控流程。** 草稿、验证、审核、发布、分配、不可变版本、观测和回滚缺一不可；启用必须真的改变 AI 执行上下文。
4. **企业能力不能后补。** SSO、角色、设备绑定、审计、撤销、保留策略、监控、备份和恢复是采购门槛，不是管理页装饰。
5. **集成优先于重造。** 工单、资产、库存、ERP、CMMS/EAM 和 MDM 通过版本化接口/连接器接入。
6. **一线体验必须比大型套件轻。** Dynamics 365 Field Service 的 Google Play 页面在调研时显示总体 2.2 分、手机 2.1 分；公开评论集中在慢、等待无反馈、点击过多、不稳定和离线问题。V9 的机会不是比它功能更多，而是让现场人员更快、更确定地完成任务。

## 必须采用的模式

- 眼镜端保持单任务焦点、稳定 HUD、实体键和语音双通道，所有关键状态在 150ms 内给出本地反馈。
- 证据、任务事件和项目指令先本地落盘；云端同步异步执行，失败可见且可恢复。
- 每次 AI 请求由服务端一次性构建 `ExecutionContext`，包含技能快照、项目事实/指令、授权知识、最近对话和本轮证据；客户端不拼高权限提示。
- 已发布知识带来源、版本、适用范围和引用；案例只生成草稿，人工审核后发布。
- 使用 Android Enterprise/OEM 受管配置投放设备凭据和策略；供应商密钥只在后端。
- 账号采用企业 SSO/OIDC 起步，预留 SAML/SCIM；本地账号仅用于受控试点和灾备，不自研完整身份平台。
- 高风险动作由确定性应用流程二次确认；声纹只是已登录、已绑定设备中的可选操作门。

## 市场痛点与可利用缺口

### 要解决的痛点

- 大型现场服务软件移动端慢、点击多、状态反馈弱。
- 离线能力往往需要复杂配置，现场仍会出现同步不确定和任务数据不可用。
- AR/远程协作产品能“看到和通话”，但 AI 结论、知识来源和流程版本未必形成同一审计链。
- AI Agent/技能容易停留在配置或标题层，现场人员无法证明本轮回答实际执行了哪个版本。

### V9 可以形成的差异化

- **可证明的 AI 技能执行：** 每轮保留技能版本、知识引用、项目指令和上下文摘要，未授权或后端不可用时拒绝执行。
- **受控项目记忆：** “以后本项目遇到 X 按 Y 做”成为带来源、版本、撤销和适用条件的项目指令，而不是模型暗记。
- **低操作眼镜闭环：** 照片/语音/AI/维修步骤/专家协同始终停留在同一任务，避免大型移动套件的页面和点击负担。
- **证据优先：** AI 回复、现场事实、照片/视频、人工确认和完成摘要可一一追溯。
- **中国现场语言适配：** 中文自然口语、工业术语、实体键和弱网设计可形成区域优势，但必须通过真实噪声和真人数据验证。

## 不应成为 V9 主线的内容

- 自研完整调度、库存、计费、路线优化、客户门户或 CMMS/EAM。
- 自研固件、Wi-Fi、Kiosk、应用分发等完整 MDM；应接入 Android Enterprise/OEM MDM。
- 把声纹当作账号、唯一身份或默认全天监听。它应为可选能力，无法达到误受/误拒、延迟和硬件门槛时不进入 GA 默认功能。
- 在眼镜端提供复杂 Skill 编辑器或知识编辑器；编辑、审核、发布和回滚属于云端工作台。
- 跨行业静态“能力展示”。正式环境只显示真实发布、授权和可执行的模板/技能。

## 来源

- TeamViewer Frontline: https://www.teamviewer.com/en/products/frontline/
- RealWear Cloud: https://www.realwear.com/software/realwear-cloud
- RealWear Navigator 520: https://www.realwear.com/devices/navigator-520
- Augmentir Connected Worker Platform: https://www.augmentir.ai/
- Dynamics 365 Field Service overview: https://learn.microsoft.com/en-us/dynamics365/field-service/overview
- Dynamics 365 Field Service Google Play: https://play.google.com/store/apps/details?id=com.microsoft.crm.crmphone.fieldServices&hl=en_US
- ServiceNow Field Service Management: https://www.servicenow.com/products/field-service-management.html
- Android managed configurations: https://developer.android.com/work/managed-configurations
- NIST SP 800-63B: https://pages.nist.gov/800-63-4/sp800-63b.html
- OWASP LLM01 Prompt Injection: https://genai.owasp.org/llmrisk/llm01-prompt-injection/
