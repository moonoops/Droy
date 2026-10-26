# Droy 伙伴

在伙伴设置中输入外观与性格描述，创建参考图后可继续生成同一角色的品牌标志和动作，再通过预览选择应用。更换外观保留伙伴姓名和已有对话。

## 配置与数据

性格使用当前 DSH 默认文本模型；图片通过官方 OpenAI Images API 生成。外观、性格描述与参考图会提交给对应服务，并按服务规则计费。图片模型及参数以 [pet-generation.mjs](pet-generation.mjs) 为准。

图片凭据通过 DSH 凭据服务或环境变量解析，优先使用 `DROY_PET_IMAGE_API_KEY`，也支持已核实的官方 OpenAI API-key 路线和 `OPENAI_API_KEY`。自定义网关凭据和 ChatGPT/OAuth 登录记录不转交给图片服务。缺少配置或服务失败时保留草稿，重试只补缺失素材。

草稿和完整候选保存在 App 私有 DSH home，完整包使用 `pet.json` 格式版本 2，包含 `atlases`、`states`、`persona`、`branding.logo` 和动作方向。已发布包不直接覆盖；修改会创建新候选。重启不自动重放收费请求。

## 显示

静态标志保持透明前景，浅深主题使用独立底板颜色。可在“通用设置 → 界面品牌与文案 → 标志底板”预览和修改，恢复自动配色会移除对应覆盖值。

浮层显示由 App 内外策略和系统悬浮窗权限决定。性格影响互动表达，不授予权限，也不改变任务结果与审批事实。

## 接口与检查

`/droy/pets` 下的列表、创建、重试、生成、状态和素材接口，以及 `/droy/branding/logo`，均要求本机会话认证。素材读取仅接受 UUID 和固定文件名，不接受任意路径或下载地址。

```sh
node --test runtime/mobile/tests/pet-generation.test.mjs
node --expose-internals --test runtime/mobile/tests/agent-bridge.test.mjs
```
