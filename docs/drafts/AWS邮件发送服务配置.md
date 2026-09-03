# AWS SES（邮件发送服务）

## AWS SES 申请与配置完整指南

### 第一步：申请 AWS SES 账号

#### 1.1 注册 AWS 账号

1. 访问 [AWS 官网](https://aws.amazon.com/)
2. 点击「创建 AWS 账户」
3. 填写邮箱、密码、账户名称
4. 提供信用卡信息（用于身份验证，不会立即扣费）
5. 选择支持计划（可选择免费的基本计划）

#### 1.2 激活 SES 服务

1. 登录 AWS 控制台
2. 在服务搜索框输入「SES」
3. 选择「Simple Email Service」
4. 选择区域（推荐：`us-east-1` 或离你最近的区域）

***

### 第二步：验证发件人邮箱/域名

#### 2.1 验证单个邮箱（适合测试）

```
AWS SES 控制台 → Verified identities → Create identity
```

1. 选择「Email address」
2. 输入你的邮箱地址（例如：`noreply@yourdomain.com`）
3. 点击「Create identity」
4. **检查邮箱收件箱**，AWS 会发送验证邮件
5. 点击邮件中的验证链接
6. 等待状态变为「Verified」✅

#### 2.2 验证整个域名（推荐生产环境）

```
AWS SES 控制台 → Verified identities → Create identity
```

1. 选择「Domain」
2. 输入你的域名（例如：`yourdomain.com`）
3. 勾选「Generate DKIM settings」（推荐）
4. 点击「Create identity」
5. AWS 会提供 3 条 DNS 记录：
    - **DKIM 记录**（3条 CNAME，用于防伪）
    - **验证记录**（1条 TXT，用于验证所有权）

**添加 DNS 记录示例**（以 Cloudflare 为例）：

```
类型: CNAME
名称: abc123._domainkey.yourdomain.com
值: abc123.dkim.amazonses.com
TTL: 自动

类型: TXT
名称: _amazonses.yourdomain.com
值: xyz789...（AWS 提供的验证码）
TTL: 自动
```

6. 等待 DNS 生效（通常 5-30 分钟）
7. 返回 SES 控制台，状态变为「Verified」✅

***

### 第三步：申请移出沙箱模式

⚠️ **重要**：新账号默认处于「沙箱模式」，只能：

- 发送给已验证的邮箱
- 每天最多 200 封邮件
- 每秒最多 1 封邮件


#### 3.1 提交生产访问请求

```
AWS SES 控制台 → Account dashboard → Request production access
```

**填写申请表**：


| 字段 | 建议填写内容 |
| :-- | :-- |
| **Use case** | 「Transactional emails」（事务性邮件） |
| **Website URL** | 你的网站地址 |
| **Use case description** | 英文描述，示例见下方 ⬇️ |
| **Expected sending rate** | 预计每天发送量（如：500 emails/day） |
| **Bounce/complaint rate** | 承诺保持低退信率（< 5%） |

**描述示例**（英文）：

```
We are building a web application that sends transactional emails 
to our registered users, including:
- Account registration confirmation
- Password reset notifications
- Order confirmations
- System notifications

We have implemented:
- Double opt-in for email subscriptions
- Automatic bounce and complaint handling
- Unsubscribe mechanism in all emails

Expected volume: approximately 500-1000 emails per day.
We commit to maintaining bounce rate below 5% and complaint rate below 0.1%.
```


#### 3.2 等待审批

- 通常 24-48 小时内回复
- 如被拒绝，根据反馈修改后重新申请

***

### 第四步：创建 SMTP 凭证

#### 4.1 生成 SMTP 用户名和密码

```
AWS SES 控制台 → SMTP settings → Create SMTP credentials
```

1. 点击「Create SMTP credentials」
2. 输入 IAM 用户名（如：`ses-smtp-user`）
3. 点击「Create」
4. **⚠️ 立即下载凭证**（只显示一次！）

**下载的凭证包含**：

```
SMTP Username: AKIAIOSFODNN7EXAMPLE
SMTP Password: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```


#### 4.2 记录 SMTP 配置信息

根据你选择的区域，SMTP 服务器地址不同：


| 区域 | SMTP 服务器地址 | 端口 |
| :-- | :-- | :-- |
| **美国东部** (us-east-1) | `email-smtp.us-east-1.amazonaws.com` | 587 (TLS) / 465 (SSL) |
| **美国西部** (us-west-2) | `email-smtp.us-west-2.amazonaws.com` | 587 / 465 |
| **欧洲** (eu-west-1) | `email-smtp.eu-west-1.amazonaws.com` | 587 / 465 |
| **亚太** (ap-southeast-1) | `email-smtp.ap-southeast-1.amazonaws.com` | 587 / 465 |


***

### 第五步：Spring Boot 配置

#### 5.1 application-prod.yml

```yaml
spring:
  mail:
    host: email-smtp.us-east-1.amazonaws.com  # 根据你的区域修改
    port: 587
    username: ${AWS_SMTP_USERNAME}  # SMTP 用户名（从环境变量读取）
    password: ${AWS_SMTP_PASSWORD}  # SMTP 密码（从环境变量读取）
    protocol: smtp
    default-encoding: UTF-8
    
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true
          connectiontimeout: 10000
          timeout: 10000
          writetimeout: 10000

app:
  mail:
    from-email: noreply@yourdomain.com  # 必须是已验证的邮箱或域名
    from-name: My Application
    enabled: true
    
    rate-limit:
      enabled: true
      max-per-minute: 14  # SES 限制：生产模式每秒14封（14 * 60 = 840/分钟）
    
    recovery:
      scan-interval-minutes: 10
```


#### 5.2 环境变量设置

**Linux/Mac**：

```bash
export AWS_SMTP_USERNAME="AKIAIOSFODNN7EXAMPLE"
export AWS_SMTP_PASSWORD="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
```

**Windows**：

```cmd
set AWS_SMTP_USERNAME=AKIAIOSFODNN7EXAMPLE
set AWS_SMTP_PASSWORD=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

**Docker**：

```yaml
version: '3.8'
services:
  app:
    image: email-system:latest
    environment:
      - AWS_SMTP_USERNAME=AKIAIOSFODNN7EXAMPLE
      - AWS_SMTP_PASSWORD=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
      - SPRING_PROFILES_ACTIVE=prod
```


***

### 第六步：测试发送

#### 6.1 启动应用

```bash
# 设置环境变量
export AWS_SMTP_USERNAME="你的SMTP用户名"
export AWS_SMTP_PASSWORD="你的SMTP密码"

# 启动应用
java -jar email-system-1.0.0.jar --spring.profiles.active=prod
```


#### 6.2 发送测试邮件

```bash
curl -X POST "http://localhost:8080/api/email/test/simple?to=test@example.com"
```


#### 6.3 检查日志

```
✅ 邮件发送成功 [EVENT][prod][ID=1]: test@example.com - 测试邮件 (耗时: 234ms)
```


***

### 第七步：监控与管理

#### 7.1 查看发送统计

```
AWS SES 控制台 → Reputation metrics
```

**关键指标**：

- ✅ **Bounce rate**（退信率）：应 < 5%
- ✅ **Complaint rate**（投诉率）：应 < 0.1%
- ⚠️ 超标会被暂停服务


#### 7.2 配置退信/投诉处理

```
AWS SES 控制台 → Configuration sets → Create set
```

**推荐配置**：

1. 创建 SNS 主题接收退信/投诉通知
2. 自动将退信邮箱加入黑名单
3. 定期清理无效邮箱

#### 7.3 设置发送限制

```
AWS SES 控制台 → Sending limits
```

**沙箱模式**：

- 每天 200 封
- 每秒 1 封

**生产模式**：

- 初始：每天 50,000 封
- 初始：每秒 14 封
- 可申请提升额度

***

### 常见问题

#### Q1: 如何提升发送配额？

```
AWS SES 控制台 → Account dashboard → Request a sending limit increase
```

需要提供：

- 历史发送数据
- 退信/投诉率记录
- 业务增长预期


#### Q2: 为什么邮件进入垃圾箱？

**解决方案**：

1. ✅ 配置 SPF 记录

```
类型: TXT
名称: yourdomain.com
值: v=spf1 include:amazonses.com ~all
```

2. ✅ 配置 DKIM（验证域名时自动配置）
3. ✅ 配置 DMARC 记录

```
类型: TXT
名称: _dmarc.yourdomain.com
值: v=DMARC1; p=quarantine; rua=mailto:dmarc@yourdomain.com
```


#### Q3: 发送失败如何排查？

**检查清单**：

1. ✅ SMTP 凭证是否正确
2. ✅ 发件人邮箱/域名是否已验证
3. ✅ 是否还在沙箱模式（只能发给已验证邮箱）
4. ✅ 是否达到发送限制
5. ✅ 查看 AWS CloudWatch 日志

***

### 成本估算

**AWS SES 价格**（2026年）：


| 项目 | 价格 |
| :-- | :-- |
| **前 62,000 封/月** | 免费（EC2发送） |
| **超出部分** | \$0.10 / 1000封 |
| **附件存储** | \$0.10 / GB |
| **接收邮件** | \$0.10 / 1000封 |

**示例**：

- 每天发送 1000 封邮件
- 每月 30,000 封
- **完全免费** ✅

***

### 最佳实践

1. ✅ **使用域名而非个人邮箱**（更专业，不易进垃圾箱）
2. ✅ **启用 DKIM 签名**（防止伪造）
3. ✅ **监控退信/投诉率**（保持账号健康）
4. ✅ **实现自动退订机制**（法律要求）
5. ✅ **从环境变量读取凭证**（不要硬编码）
6. ✅ **使用 Configuration Sets 跟踪邮件**

***

### 总结

**申请流程**：

```
注册AWS账号 → 激活SES → 验证邮箱/域名 → 申请生产访问 → 创建SMTP凭证 → 配置应用
```

**配置要点**：

- 服务器：`email-smtp.[region].amazonaws.com`
- 端口：587 (TLS)
- 发件人：必须已验证
- 限速：生产模式 14封/秒

**下一步**：

1. 验证你的域名
2. 申请移出沙箱
3. 创建 SMTP 凭证
4. 更新 Spring Boot 配置
5. 测试发送

需要我帮你生成完整的 AWS SES 配置文件模板吗？🚀
<span style="display:none">[^1][^2][^3]</span>

<div align="center">⁂</div>

[^1]: wo-yao-kai-fa-yi-ge-ai-mei-ron-rnVcNINTQ1qFUw9SkPQKgQ.md
[^2]: email-event-driven.md
[^3]: spring-boot-email-complete.md


---

## 成本比较

结论先说：  
**如果只看“发出去的邮件数量 / 花的钱”，AWS SES 的价格优势非常明显，比 SendGrid 便宜 3–5 倍左右，量越大越明显。**  
SendGrid 主要是把 UI、营销自动化、报表等功能打包进月费里，适合愿意用钱换“省心 + 可视化工具”的团队。

### 1. 纯发送成本对比（只考虑 API 发信）

常见量级下的大致价格对比（不含附加服务）：

| 每月邮件量 | AWS SES 费用（约） | SendGrid 费用（约） |
|-----------|--------------------|---------------------|
| 50,000    | 约 5 美元          | 约 15–20 美元（Essentials 50K） |
| 200,000   | 约 20 美元         | 约 80 美元（Essentials 200K）   |
| 1,000,000 | 约 100 美元        | 约 400 美元（Pro 1M）          |

- **AWS SES**：标准价长期稳定在 **0.10 美元 / 1,000 封**（也就是 1 美元 / 10,000 封），高量用户甚至可以做到 0.02 美元 / 1,000 封。 [blog.campaignhq](https://blog.campaignhq.co/aws-email-service-pricing/)
- **SendGrid**：走 SaaS 套餐价：
  - Essentials：大约 **19.95 美元/月 起，包含 50K–100K 邮件** [campaignrefinery](https://campaignrefinery.com/sendgrid-pricing/)
  - 更高量要升到 Pro / Premier，100K、200K、1M 都是阶梯价，实测对比表中 50K、200K、1M 分别约为 **$14.95 / $79.95 / $399.95**。 [blog.campaignhq](https://blog.campaignhq.co/amazon-ses-pricing/)

所以从“**每 1,000 封邮件的边际成本**”看，大致是：
- SES：**$0.10 / 1,000 封**
- SendGrid：按上面表折算，大约 **$0.80–$1.00 / 1,000 封**

量一大，差价就非常直接。

***

### 2. 免费额度与起步成本

**AWS SES：**

- 现在主打 **按量计费 + 新用户信用额度**：
  - 标准价是 $0.10 / 1,000 封，用多少付多少。
  - 部分地区和时间段，还有 **新用户赠送信用额度（例如 $200 额度）**，用完再按量收费。 [costgoat](https://costgoat.com/pricing/amazon-ses)
- 以前的老规则是 EC2 上发信有 62,000 封/月免费，现在新用户更多是走“通用信用额度 + 按量计费”模式。 [blog.campaignhq](https://blog.campaignhq.co/aws-email-service-pricing/)

**SendGrid：**

- 仍然提供一个非常低量的免费档：每天大约 **100 封以内免费**，适合开发测试。 [tekpon](https://tekpon.com/software/sendgrid/pricing/)
- 真正上生产一般要上 **Essentials 付费档，起步约 $19.95/月（50K 封上下）**。 [cloudeagle](https://www.cloudeagle.ai/blogs/what-is-sendgrid-pricing-guide)

如果你是**后端开发 + Spring Boot 服务**，做的是正常业务通知 / 验证邮件，量一上来（比如几万封/月起），SES 的起步门槛更“线性”：  
> 没有“必须先交固定 20 美元/月”的门槛，而是从几美分、几美元慢慢涨。

***

### 3. 隐性成本和“为什么有人愿意多花钱用 SendGrid”

虽然 SES 单价很便宜，但它是偏 **基础设施级别** 的服务：

**AWS SES 的“隐形成本”主要在：** [saasgenius](https://www.saasgenius.com/blog/amazon-ses-vs-sendgrid/)

- 需要自己或通过第三方做：
  - 报表、统计、A/B 测试
  - 营销自动化（欢迎系列邮件、唤醒流失用户等）
  - 可视化模板编辑器
- 监控、报警往往要接 CloudWatch 或第三方
- 如果要独立 IP、内容扫描、归档等会有额外费用（每个专用 IP 大约 24.95 美元/月起） [aws.amazon](https://aws.amazon.com/ses/pricing/)

**SendGrid 的月费，实际上打包了：** [campaignrefinery](https://campaignrefinery.com/sendgrid-pricing/)

- 在线控制台 + 详细统计仪表盘
- 模板编辑器（拖拽式），非技术同学也能搞
- 营销自动化、联系人列表、细分、A/B 测试等
- 更“傻瓜式”的开箱体验，文档和 UI 更偏产品化

所以很多团队愿意多花钱是因为：

> “我要的是一整套 Email SaaS，而不仅是一个 SMTP 出口。”

***

### 4. 结合你的场景怎么选？

结合你现在的项目（Spring Boot、以事务型邮件为主，如注册、通知、重置密码）：

- **如果你是技术主导、已有监控 / 日志体系，只需要一个**稳定、便宜、可水平扩展的发信通道：
  - **优先选 AWS SES**，和你现在的架构（事件驱动 + 队列）非常契合。
  - 成本极低，即便做到 **每月 10 万封左右也就 10 美元量级**。
- **如果未来你打算做营销邮件、运营团队要自己拖拽模板、做活动自动化：**
  - 可以考虑：
    - 继续用 SES 做底层发信 + 上一层第三方营销平台，或者
    - 直接用 SendGrid 这类“全家桶”，用钱换省心。

***

### 5. 总结一句话

- **纯粹论“1 封邮件多少钱”：AWS SES 完胜 SendGrid，量越大优势越离谱（常见对比 100,000 封：SES 大约 $10，SendGrid 大约 $35–$40）。** [mailtrap](https://mailtrap.io/blog/amazon-ses-vs-sendgrid/)
- **SendGrid 的价值更多在于“工具和体验”：可视化、营销自动化、报表一条龙，适合有运营 / 市场团队的场景。**

如果你现在主要是后端自己控代码 + 发事务型邮件，成本敏感，**用 SES 是性价比最高的选择**。如果你愿意，我可以按你现在的配置直接帮你改出一份「SendGrid 版本的 `application-prod.yml`」做对比试用。