# Companion sticker packs

Open a companion's profile and choose **Sticker packs**. The library is shared
on this device; each companion has its own enabled packs. New companions start
with Droy's original built-in stickers. Turning off every pack disables sticker
sending for that companion without deleting earlier messages or their images.

Choose **Import ZIP** to add a pack. Importing immediately adds it to the shared
library and enables it for the current companion. Other companions keep their
existing selections. Save any pending pack selection changes before importing.

Supported archives are [dsh-meme](https://github.com/yyh-001/dsh-meme) exported
packs (`index.db`, optional `manifest.json`, and `memes/`) and ZIPs containing
static images. For plain image archives, use descriptive file names and category
folders so companions can find appropriate stickers. PNG, JPEG and static WebP
are supported. Animated GIF, APNG and WebP are not supported in this version.

Limits: 20 MiB per ZIP, 64 MiB of expanded content, 8 MiB per image and 500
images per pack. Invalid or unsupported packs leave the current library intact.
Import only images you have permission to use. The code license of a pack tool
does not determine the license of its pictures.

Companions decide when a sticker fits the conversation, using their existing
personality and reply logic. Droy searches enabled packs and gives the model a
small candidate list. It does not require a sticker on every turn or split normal
text replies into multiple bubbles. Chat images are not automatically added to
the sticker library. Import and search run locally; selected sticker descriptions
can be included in requests to the companion's configured model.

Imported images are stored in the App's private shared sticker library. Disabling
a pack does not erase it, and deleting one companion does not erase shared packs
used by other companions or historical messages. This version does not provide
pack removal, automatic image recognition, a marketplace or animated playback.

## 表情包图包

打开伙伴资料，进入“表情包”。素材库由本机伙伴共用，每个伙伴分别选择可用图包。
新伙伴默认使用 Droy 原创内置表情；关闭全部图包会停止该伙伴的表情发送，不会删除
已有消息或历史图片。

点击“导入 ZIP”添加图包。导入会立即加入共享素材库，并为当前伙伴启用；其他伙伴
的选择保持不变。若有尚未保存的图包选择，请先保存再导入。

支持 dsh-meme 导出的图包（`index.db`、可选 `manifest.json` 和 `memes/`），以及
包含静态图片的 ZIP。纯图片图包请使用含义清晰的文件名和分类目录，方便伙伴找图。
支持 PNG、JPEG 和静态 WebP；本版暂不支持 GIF、APNG 或动态 WebP。

每个 ZIP 最大 20 MiB，解压内容合计最大 64 MiB，单图最大 8 MiB，每包最多 500 张。
无效或不支持的图包不会覆盖已有图库。请使用有权使用的素材；图库工具的代码许可
不代表其中图片的使用许可。

伙伴继续根据原有的人格和回复逻辑决定何时发表情，Droy 从启用图包中检索少量候选
供模型选择，不要求每轮发图，也不拆分普通文字回复。聊天图片不会自动入库。
导入和检索在本机进行；所选表情的描述可能随对话提交给伙伴已配置的模型。

导入图片保存在 App 私有共享素材库。禁用图包不会删除素材；删除一个伙伴也不会
删除其他伙伴或历史消息使用的共享图包。本版暂不提供删除图包、自动识图、图库市场
或动态表情播放。
