# 移动附件

`@droyagent/media-input` 将 Android 相册、相机和分享输入接入 DSH 会话、草稿与附件服务，不另建聊天历史或发起模型请求。

## 使用

“+”菜单提供相册、拍照和待导入内容。外部分享默认导入新对话，也可选择已有对话；关闭预览保留暂存，明确放弃才清理。每条消息最多 10 张图片，文件与图片大小按原生和 DSH 限制检查。

拍照原图保存到相册，会话附件使用独立副本。相册保存失败会保留原图供重试。恢复导入只加入草稿，不自动重发；文字冲突保留当前草稿供用户处理。

## 宿主接口

`window.DroyMobile.mediaInput(args)` 提供 `pick`、`list`、`bind`、`removeItems`、`complete` 和 `retryAlbum`。系统结果通过 `droy-media-input-change` 通知；原生桥不传文件 Base64。

`DROY_MEDIA_INPUT_DIR` 必须是 App 私有目录。附件 HTTP 入口要求同源会话认证，只读取清单中的 UUID 文件，并检查成员关系、长度和符号链接。

DSH 服务调用集中在 [dsh-adapter.js](dsh-adapter.js)。打包将其注入客户端唯一标记，升级时核对服务与菜单排序契约，保留用户草稿和私有导入目录。

## 检查

```sh
node --test runtime/media-input/tests/*.test.mjs
```
