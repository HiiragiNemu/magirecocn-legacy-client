# 原生战斗同名技能的类型绑定

`ファスト・マナアップ` 在已有国服资料中有两个正式名称。保留差异，不将它加入无上下文的全局译表。

| 原生技能 ID | type | displayType | 显示名称 |
|---|---|---|---|
| 115201 | ABILITY | MEMORIA | 快速魔法提升 |
| 1144110 | ABILITY | EMOTION | 魔力骤升 |

来源：已保存的 `stat.magireco.moe/data/battle/10200556.json` 同时包含这两个记录；精神强化记录另见 10200551、10200553、10200557、1033055、1033056。既有 `pieceSkillMap` / `emotionSkillMap` 中的译名不变。国服原始 MyPage 的技能 115201 使用“快速魔法提升”；CharaCollection 的精神强化技能 1018113 使用“魔力骤升”，作为同名系列的正式用词依据。

原生路径是 `QbJsonUtilityArt::parseArtUnit` → `QbArtUnit::setParam` → `QbEffectAnimeSkillName::setTitleToBone`。`setParam` 参数同时保留类别、技能 ID、完整名称和显示类型。发布基线 3.1.9 的 arm64 / armv7 导出签名一致；引擎枚举表均确认：技能类别 MEMORIA=3、ABILITY=1、显示 MEMORIA=1、EMOTION=3。

新增入口只匹配上述复合身份与完整日文原名，只替换名称参数。原函数照常处理描述、等级、图标、消耗、语音编号和其他数值；未知 ID、类型、不同原文及已翻译名称原样传递。既有 `noI18nLabel` 调试开关关闭此翻译。

验证：`python3 tools/test-battle-skill-name-context.py` 执行真实包装函数，检查两个正例及类型/ID/原文不匹配、幂等和调试开关。宿主参数回放、双 ABI 编译与发布包检查不替代真机显示验收。它是已有名称的原生显示接入，不增加技能翻译条目或水银贡献字段数。
