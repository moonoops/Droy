# Companion sticker packs

Open a companion's profile and choose **Sticker packs**. The library is shared
on this device; each companion has its own enabled packs. New companions start
with Droy's original built-in stickers. Turning off every pack disables sticker
sending for that companion without deleting earlier messages or their images.

Choose **Import ZIP** to add a pack. Importing immediately adds it to the shared
library and enables it for the current companion. Other companions keep their
existing selections. Save any pending pack selection changes before importing.

## Online search and marketplace

Companions can search the public [dsh-meme-packs image index](https://github.com/yyh-001/dsh-meme-packs)
by picture description, caption and emotion. When local stickers do not fit,
they can download a matching image and send it using the existing sticker
message flow. Individual downloads enter the shared **Online stickers** collection,
which is enabled for the downloading companion. Descriptions, sources and licenses
are retained. Only the public indexes and selected media are fetched; searches
do not upload the conversation or search keywords to a remote search service.
This searches supported public sticker sources, rather than the whole web.

Open **Sticker packs → Sticker marketplace** to search and download whole packs.
Companions can also find a market pack with `companion_sticker_search(kind="packs")`
and install a returned ID with `companion_sticker_download(packId=...)`.
For one image, use `kind="stickers"` (`format="gif"` to find a GIF), download the returned `stickerId`, then
send the returned local `sticker.id` through `companion_emoji(stickerId=...)`.
The companion normally downloads one needed image, with at most three download
attempts per chat turn. It installs a whole pack when requested or when several
images of the same style are needed.

Marketplace packs may use a release ZIP or a remote image manifest. ZIP downloads
are checked against the market's SHA-256. Marketplace installation supports animated
GIFs and skips unsupported APNG and animated WebP images. It reports the actual
number of images installed; a pack with no
supported images cannot be installed. Downloading a pack enables it only for the
current companion. Files are downloaded into private runtime data, not bundled
into the APK. Exiting the marketplace cancels an active download. Network or
verification failures keep the existing library intact and can be retried.

Supported archives are [dsh-meme](https://github.com/yyh-001/dsh-meme) exported
packs (`index.db`, optional `manifest.json`, and `memes/`) and ZIPs containing
images. For plain image archives, use descriptive file names and category
folders so companions can find appropriate stickers. PNG, JPEG, static WebP and
animated GIF are supported. GIFs retain their animation in the picker, messages
and image preview. APNG and animated WebP are not supported in this version.

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
pack removal or automatic image recognition.

## 表情包图包

打开伙伴资料，进入“表情包”。素材库由本机伙伴共用，每个伙伴分别选择可用图包。
新伙伴默认使用 Droy 原创内置表情；关闭全部图包会停止该伙伴的表情发送，不会删除
已有消息或历史图片。

点击“导入 ZIP”添加图包。导入会立即加入共享素材库，并为当前伙伴启用；其他伙伴
的选择保持不变。若有尚未保存的图包选择，请先保存再导入。

### 联网找图与图库市场

伙伴可以按画面描述、台词和情绪检索公开的
[dsh-meme-packs 逐图索引](https://github.com/yyh-001/dsh-meme-packs)。本地表情不合适时，
可以自主下载一张匹配的图片，再通过原有表情消息发送。单图进入共享“联网表情”集合，
只为下载的伙伴启用，并保留描述、来源和许可。联网只获取公开索引和选中的素材，
不会向远程搜索服务上传聊天记录或检索词；搜索范围是已接入的公开表情来源。

打开“表情包 → 图库市场”可以搜索和下载整个图包。伙伴也可以调用
`companion_sticker_search(kind="packs")` 找图包，再用实际返回的 `packId`
调用 `companion_sticker_download`。单图使用 `kind="stickers"`，需要 GIF 时增加 `format="gif"`，下载返回的
`stickerId` 后，以本地 `sticker.id` 调用 `companion_emoji(stickerId=...)` 发送。
伙伴通常只下载当前需要的一张，每轮聊天最多尝试下载三次；用户指定图包或确需同风格
多张表情时才安装整包。

市场支持 Release ZIP 和远程逐图清单，ZIP 按市场 SHA-256 校验。市场安装支持 GIF 动图，
仅跳过暂不支持的 APNG 和动态 WebP，显示实际安装的图片数量；没有受支持图片的图包
无法安装。下载只为当前伙伴启用，
文件保存在 App 私有运行数据中，不随 APK 打包。退出市场会取消正在进行的下载。
联网或校验失败不会覆盖已有图库，可以重试。

支持 dsh-meme 导出的图包（`index.db`、可选 `manifest.json` 和 `memes/`），以及
包含图片的 ZIP。纯图片图包请使用含义清晰的文件名和分类目录，方便伙伴找图。
支持 PNG、JPEG、静态 WebP 和 GIF 动图；GIF 在选择面板、聊天消息和放大预览中保留动画。
本版暂不支持 APNG 或动态 WebP。

每个 ZIP 最大 20 MiB，解压内容合计最大 64 MiB，单图最大 8 MiB，每包最多 500 张。
无效或不支持的图包不会覆盖已有图库。请使用有权使用的素材；图库工具的代码许可
不代表其中图片的使用许可。

伙伴继续根据原有的人格和回复逻辑决定何时发表情，Droy 从启用图包中检索少量候选
供模型选择，不要求每轮发图，也不拆分普通文字回复。聊天图片不会自动入库。
导入和检索在本机进行；所选表情的描述可能随对话提交给伙伴已配置的模型。

导入图片保存在 App 私有共享素材库。禁用图包不会删除素材；删除一个伙伴也不会
删除其他伙伴或历史消息使用的共享图包。本版暂不提供删除图包或自动识图。
