/*
 * assets/magia/localstate.js 的判据测试（node，无依赖）。
 *
 * 为什么值得单独测：这个脚本是整条链路里唯一跑在游戏页面里的一段，也是唯一
 * 「Java 测试覆盖不到」的一段。它错了的表现是玩家的编队悄悄错乱——不抛异常、
 * 不进日志，最难查的那种。
 *
 * 测试台把 XMLHttpRequest 与 CNLocalState 都换成假的，于是可以在 JVM/浏览器
 * 之外，逐条验：
 *   1. savePrm（请求体形状）→ userDeckList 行（响应形状）的还原对不对
 *   2. 覆盖是否真的发生（服务端无状态，回来的是罐头）
 *   3. 存档能不能活过一次「进程重启」
 *   4. 账号里没有的魔法少女会不会被就地摘掉，且**不回写**存档
 *
 * 跑法：node tools/localstate-js-test.js
 */
"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const SCRIPT = path.join(__dirname, "..", "assets", "magia", "localstate.js");
const src = fs.readFileSync(SCRIPT, "utf8");

let pass = 0, fail = 0;
function check(name, ok) {
  if (ok) { pass++; console.log("  ✓ " + name); }
  else { fail++; console.log("  ✗ " + name); }
}
function eq(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  check(name + (ok ? "" : "  期望=" + JSON.stringify(expected) + " 实际=" + JSON.stringify(actual)), ok);
}

/* 假的 Java 桥。store 在多次 boot 之间共享，用来模拟「落盘活过重启」。 */
function makeBridge(store) {
  return {
    get: (ns) => (Object.prototype.hasOwnProperty.call(store, ns) ? store[ns] : null),
    set: (ns, json) => { store[ns] = json; return true; },
    remove: (ns) => { delete store[ns]; return true; },
    list: () => JSON.stringify(Object.keys(store)),
  };
}

/*
 * 起一个「页面」：新的 JS 全局环境 + 一份假 XHR，脚本在里面跑一遍。
 * store 从外面传进来，所以两次 boot 共享同一份「磁盘」。
 */
function boot(store, opts) {
  opts = opts || {};
  const sandbox = {};
  sandbox.window = sandbox;
  sandbox.console = { log() {}, warn() {}, error() {} };
  if (!opts.noBridge) sandbox.CNLocalState = makeBridge(store);

  // __sent 记「真的发出去了没有」：answer 路由的全部意义就是让它保持 false。
  function FakeXHR() { this.__l = []; this.__fired = []; this.responseType = ""; this.__sent = false; }
  FakeXHR.prototype.open = function (m, u) { this.__method = m; this.__url = u; };
  FakeXHR.prototype.send = function (b) { this.__body = b; this.__sent = true; };
  FakeXHR.prototype.addEventListener = function (ev, fn) {
    if (ev === "readystatechange") this.__l.push(fn);
  };
  FakeXHR.prototype.dispatchEvent = function (ev) {
    this.__fired.push(ev && ev.type);
    return true;
  };
  sandbox.XMLHttpRequest = FakeXHR;
  // 脚本里 serveLocal 走 setTimeout(fn, 0)（真实 XHR 一定是 send 先返回、回调后到）。
  // 测试台里同步跑掉即可：这里要验的是「派发了什么」，不是「什么时候派发」。
  sandbox.setTimeout = function (fn) { fn(); return 0; };
  sandbox.JSON = JSON;
  sandbox.Object = Object;
  sandbox.Array = Array;
  sandbox.RegExp = RegExp;
  sandbox.String = String;

  vm.createContext(sandbox);
  vm.runInContext(src, sandbox, { filename: "localstate.js" });

  return {
    sandbox,
    /* 模拟一次请求：发出去（可能被捕获），再喂一份响应（可能被覆盖）。 */
    roundtrip(method, url, body, responseObj) {
      const x = new sandbox.XMLHttpRequest();
      x.open(method, url);
      x.send(body === undefined ? null : body);
      x.readyState = 4;
      x.responseText = JSON.stringify(responseObj);
      x.__l.forEach((fn) => fn.call(x));
      return JSON.parse(x.responseText);
    },
  };
}

// ── 素材：与抓包归档里的样本同形 ────────────────────────────────
// magica/api/userDeck/save/001.json 的请求体（ID 已换成短的假值）。
const SAVE_PRM = {
  deckType: 11,
  formationSheetId: 111,
  name: "队伍1",
  questPositionHelper: 3,
  episodeUserCardId: "cardB",
  rentalPieceSetIdList: [null, null, null, null, null, null, null, null, null, null],
  userCardIds: ["cardA", "cardB"],
  questPositionIds: [1, 5],
  userPieceIdLists: [["pieceA1", "pieceA2"], []],
  switchNpcEventId: null,
  switchNpcFlagList: [null, null, null, null, null, null, null, null, null, null],
};

/* 无状态服务端回来的罐头：编队是默认的空队。 */
function cannedPage(extra) {
  const page = {
    resultCode: "success",
    userDeckList: [{ userId: "u1", deckType: 11, name: "队伍1" }],
    userFormationSheetList: [
      { formationSheetId: 111, formationSheet: { id: 111, name: "布雷夫阵形", sheetType: 6 } },
    ],
  };
  return Object.assign(page, extra || {});
}

const DECK_SAVE_URL = "https://dorothy.magi-reco.com/magica/api/userDeck/save";
const PAGE_URL = "https://dorothy.magi-reco.com/magica/api/page/DeckFormation";

// ── 1. 捕获与还原 ─────────────────────────────────────────────────
console.log("== 捕获请求体并还原成响应行 ==");
{
  const store = {};
  const page = boot(store);

  // 玩家点保存：请求体是 savePrm，服务端回一份罐头（不记）。
  page.roundtrip("POST", DECK_SAVE_URL, JSON.stringify(SAVE_PRM), { resultCode: "success" });
  check("请求体被存进 deck 命名空间", !!store.deck);

  const saved = JSON.parse(store.deck || "{}");
  eq("按 deckType 存档", Object.keys(saved), ["11"]);

  // 之后任意一个带 userDeckList 的响应都应被覆盖。
  const out = page.roundtrip("POST", PAGE_URL, null, cannedPage());
  const row = out.userDeckList.find((r) => r.deckType === 11);

  eq("userCardIds → userCardIdN", [row.userCardId1, row.userCardId2], ["cardA", "cardB"]);
  eq("questPositionIds → questPositionIdN", [row.questPositionId1, row.questPositionId2], [1, 5]);
  eq("episodeUserCardId → questEpisodeUserCardId", row.questEpisodeUserCardId, "cardB");
  // savePrmCreate 里的 ("000"+(f+1)+(r+1)).slice(-3)：第 1 个位置的第 1/2 张记忆
  eq("userPieceIdLists → userPieceId011/012",
     [row.userPieceId011, row.userPieceId012], ["pieceA1", "pieceA2"]);
  eq("name 跟着走", row.name, "队伍1");
  eq("formationSheetId 跟着走", row.formationSheetId, 111);
  eq("questPositionHelper 跟着走", row.questPositionHelper, 3);
  eq("formationSheet 从响应里的阵形表补齐", row.formationSheet && row.formationSheet.id, 111);
  eq("旧行里我们不产生的字段被保留（userId）", row.userId, "u1");
}

// ── 2. 活过一次重启 ───────────────────────────────────────────────
console.log("== 活过一次进程重启 ==");
{
  const store = {};
  boot(store).roundtrip("POST", DECK_SAVE_URL, JSON.stringify(SAVE_PRM), { resultCode: "success" });

  // 新的全局环境 = 新进程，只有「磁盘」是同一份。
  const next = boot(store);
  const out = next.roundtrip("POST", PAGE_URL, null, cannedPage());
  const row = out.userDeckList.find((r) => r.deckType === 11);
  eq("重启后仍覆盖出玩家存的编队", [row.userCardId1, row.userCardId2], ["cardA", "cardB"]);
}

// ── 3. 响应里没有这个 deckType 就补进去 ───────────────────────────
console.log("== 响应缺这一队时补进去 ==");
{
  const store = {};
  const page = boot(store);
  page.roundtrip("POST", DECK_SAVE_URL, JSON.stringify(SAVE_PRM), { resultCode: "success" });
  const out = page.roundtrip("POST", PAGE_URL, null,
      { resultCode: "success", userDeckList: [{ userId: "u1", deckType: 24 }] });
  eq("原有的别的队不被动", out.userDeckList[0].deckType, 24);
  check("缺的那一队被补进来", out.userDeckList.some((r) => r.deckType === 11));
}

// ── 4. bulkSave（歼灭战 / 镜之魔女多队）────────────────────────────
console.log("== bulkSave 多队 ==");
{
  const store = {};
  const page = boot(store);
  const bulk = {
    userDeckList: [
      Object.assign({}, SAVE_PRM, { deckType: 101, name: "队伍1", userCardIds: ["cardC"], questPositionIds: [1], userPieceIdLists: [[]] }),
      Object.assign({}, SAVE_PRM, { deckType: 102, name: "队伍2", userCardIds: ["cardD"], questPositionIds: [2], userPieceIdLists: [[]] }),
    ],
  };
  page.roundtrip("POST",
      "https://dorothy.magi-reco.com/magica/api/userDeck/bulkSave",
      JSON.stringify(bulk), { resultCode: "success" });
  const saved = JSON.parse(store.deck || "{}");
  eq("两队都被存下", Object.keys(saved).sort(), ["101", "102"]);

  const out = page.roundtrip("POST", PAGE_URL, null,
      { resultCode: "success", userDeckList: [] });
  const t101 = out.userDeckList.find((r) => r.deckType === 101);
  const t102 = out.userDeckList.find((r) => r.deckType === 102);
  eq("101 覆盖正确", t101.userCardId1, "cardC");
  eq("102 覆盖正确", t102.userCardId1, "cardD");
}

// 歼灭战那条路径的 URL 形状不同，单独验一次——正则漏了它就是「歼灭战存不住」。
console.log("== 歼灭战 bulkSave 的 URL 形状 ==");
{
  const store = {};
  const page = boot(store);
  page.roundtrip("POST",
      "https://dorothy.magi-reco.com/magica/api/userDeck/extermination/bulkSave",
      JSON.stringify({ userDeckList: [Object.assign({}, SAVE_PRM, { deckType: 71 })] }),
      { resultCode: "success" });
  check("extermination/bulkSave 也被捕获", !!store.deck && !!JSON.parse(store.deck)["71"]);
}

// ── 5. 账号里没有的卡就地摘掉，且不回写存档 ───────────────────────
console.log("== 账号里没有的魔法少女 ==");
{
  const store = {};
  const page = boot(store);
  page.roundtrip("POST", DECK_SAVE_URL, JSON.stringify(SAVE_PRM), { resultCode: "success" });
  const before = store.deck;

  // userCardList 只报了 cardA —— cardB 这次不在。
  const out = page.roundtrip("POST", PAGE_URL, null,
      cannedPage({ userCardList: [{ id: "cardA" }] }));
  const row = out.userDeckList.find((r) => r.deckType === 11);

  eq("在册的位置留着", row.userCardId1, "cardA");
  eq("不在册的位置被摘掉", row.userCardId2, undefined);
  eq("被摘掉的位置连槽位一起清", row.questPositionId2, undefined);
  eq("指向被摘掉那张卡的剧情位也清掉", row.questEpisodeUserCardId, undefined);
  // 这一条是本组的重点：服务端无状态，一次响应不全不代表玩家真的失去了卡。
  eq("存档不因此被改小（下次卡回来还在）", store.deck, before);
}

// ── 5b. switchNpc 系字段 ──────────────────────────────────────────
// 归档里确认响应行用 switchNpcEventId + switchNpcFlagN（见过 switchNpcFlag3）。
// 漏还原它们不会报错，只会让切换 NPC 的状态每次覆盖后被静默抹平。
console.log("== switchNpc 系字段 ==");
{
  const store = {};
  const page = boot(store);
  const prm = Object.assign({}, SAVE_PRM, {
    switchNpcEventId: 4321,
    switchNpcFlagList: [null, null, 1, null, null, null, null, null, null, null],
  });
  page.roundtrip("POST", DECK_SAVE_URL, JSON.stringify(prm), { resultCode: "success" });
  const out = page.roundtrip("POST", PAGE_URL, null, cannedPage());
  const row = out.userDeckList.find((r) => r.deckType === 11);
  eq("switchNpcEventId 被还原", row.switchNpcEventId, 4321);
  eq("switchNpcFlagList[2] → switchNpcFlag3", row.switchNpcFlag3, 1);
  eq("为空的槽位不产生字段", row.switchNpcFlag1, undefined);

  // 再存一次不带这些字段的，旧值必须被清掉——我们负责的字段是「全部重写」，
  // 只覆盖非空的那些会让玩家撤下来的设置阴魂不散。
  page.roundtrip("POST", DECK_SAVE_URL,
      JSON.stringify(Object.assign({}, SAVE_PRM, { switchNpcEventId: null, switchNpcFlagList: null })),
      { resultCode: "success" });
  const out2 = page.roundtrip("POST", PAGE_URL, null, cannedPage());
  const row2 = out2.userDeckList.find((r) => r.deckType === 11);
  eq("撤下之后 switchNpcEventId 被清掉", row2.switchNpcEventId, undefined);
  eq("撤下之后 switchNpcFlag3 被清掉", row2.switchNpcFlag3, undefined);
}

// ── 5c. 相对 URL ──────────────────────────────────────────────────
// linkList 里的地址是相对路径（/magica/api/…），归档里没有 linkList 样本，
// 所以两种形状都得能认出来——只认绝对的话就是「保存了但没存下」。
console.log("== 相对 URL 与带查询串 ==");
{
  for (const url of ["/magica/api/userDeck/save",
                     "/magica/api/userDeck/save?_=1700000000"]) {
    const store = {};
    const page = boot(store);
    page.roundtrip("POST", url, JSON.stringify(SAVE_PRM), { resultCode: "success" });
    check("认得 " + url, !!store.deck && !!JSON.parse(store.deck)["11"]);
  }
  // 不该误伤的形状
  const store = {};
  const page = boot(store);
  page.roundtrip("POST", "/magica/api/userDeck/saveSomethingElse",
      JSON.stringify(SAVE_PRM), { resultCode: "success" });
  check("不误伤 userDeck/saveSomethingElse", !store.deck);
}

// ── 5d. 换阵形之后 formationSheet 不能还是旧的 ────────────────────
console.log("== 换阵形 ==");
{
  const store = {};
  const page = boot(store);
  // 先让缓存里同时有 111 和 112 两张阵形
  page.roundtrip("POST", PAGE_URL, null, cannedPage({
    userFormationSheetList: [
      { formationSheetId: 111, formationSheet: { id: 111, name: "布雷夫阵形" } },
      { formationSheetId: 112, formationSheet: { id: 112, name: "另一个阵形" } },
    ],
  }));
  // 玩家换到 112
  page.roundtrip("POST", DECK_SAVE_URL,
      JSON.stringify(Object.assign({}, SAVE_PRM, { formationSheetId: 112 })),
      { resultCode: "success" });
  // 旧行里带着 111 的 formationSheet —— 这正是会出错的输入
  const out = page.roundtrip("POST", PAGE_URL, null, {
    resultCode: "success",
    userDeckList: [{ userId: "u1", deckType: 11, formationSheetId: 111,
                     formationSheet: { id: 111, name: "布雷夫阵形" } }],
  });
  const row = out.userDeckList.find((r) => r.deckType === 11);
  eq("formationSheetId 换成新的", row.formationSheetId, 112);
  eq("formationSheet 跟着换，不是旧那张", row.formationSheet && row.formationSheet.id, 112);
}

// ── 6. 没有桥时必须完全无副作用 ───────────────────────────────────
console.log("== 桥没挂上时退化成空操作 ==");
{
  const store = {};
  const page = boot(store, { noBridge: true });
  const canned = cannedPage();
  const out = page.roundtrip("POST", PAGE_URL, null, canned);
  eq("响应原样放行", out, canned);
  eq("没有产生任何落盘", Object.keys(store), []);
  // 重入标记必须在「取不到桥」之前就置上：Java 侧那条 500ms 的探针就是查它，
  // 没置上的话没有桥的文档会被反复重注（9KB 脚本，每 500ms 一次）。
  eq("无桥时重入标记仍然置上", page.sandbox.__MAGIACN_LOCAL_STATE__, true);
}

// ── 7. 非游戏 JSON / 非 JSON 响应不受影响 ─────────────────────────
console.log("== 不相干的响应不受影响 ==");
{
  const store = {};
  const page = boot(store);
  page.roundtrip("POST", DECK_SAVE_URL, JSON.stringify(SAVE_PRM), { resultCode: "success" });
  const other = { resultCode: "success", userItemList: [{ itemId: 1 }] };
  eq("不带 userDeckList 的响应原样放行",
     page.roundtrip("POST", "https://dorothy.magi-reco.com/magica/api/page/MyPage", null, other),
     other);
}

// ── 8. 路由表 ─────────────────────────────────────────────────────
//
// 这一节测的是机制本身，不是编队。机制错了的后果比编队错乱更大：answer 一旦
// 半死（readyState=4 但 status=0），前端报出来的错会离原因十万八千里。
console.log("== 路由表：登记与统计 ==");
{
  const page = boot({});
  const st = page.sandbox.__MAGIACN_STATE__;
  eq("内置三条路由都登记了", st.routes(),
     ["deck:capture", "sheet:harvest(全站)", "deck:overlay(全站)"]);

  page.roundtrip("POST", DECK_SAVE_URL, JSON.stringify(SAVE_PRM), { resultCode: "success" });
  check("捕获路由记了一次 request", st.stats()["deck:capture"].req === 1);

  page.roundtrip("POST", PAGE_URL, null, cannedPage());
  check("覆盖路由记了一次 response", st.stats()["deck:overlay(全站)"].res >= 1);
  // 攒阵形不改 json（response 返回 false），所以它的 res 恒为 0——这不是没跑，
  // 是「跑了但没动过」。两者在 stats 里必须分得开，否则 stats 会骗人。
  check("攒阵形路由不计入 res（它不改 json）", st.stats()["sheet:harvest(全站)"].res === 0);
}

console.log("== 路由表：answer 本地应答 ==");
{
  const page = boot({});
  const st = page.sandbox.__MAGIACN_STATE__;
  const LOCAL = JSON.stringify({ resultCode: "success", localAnswer: true });
  st.route({
    name: "t:answer",
    test: (u) => /\/magica\/api\/test\/local(?:\?|$)/.test(u),
    answer: () => LOCAL
  });

  const x = new page.sandbox.XMLHttpRequest();
  x.open("POST", "https://dorothy.magi-reco.com/magica/api/test/local");
  x.send("{}");
  check("没有真的发出去", x.__sent === false);
  eq("responseText 就是本地那份", x.responseText, LOCAL);
  eq("readyState 伪造成 4", x.readyState, 4);
  eq("status 伪造成 200", x.status, 200);
  check("派发了 readystatechange 与 load",
        x.__fired.indexOf("readystatechange") >= 0 && x.__fired.indexOf("load") >= 0);
  check("answer 计数加了一次", st.stats()["t:answer"].ans === 1);

  // 同一个页面里，没登记 answer 的 path 必须照常出网。
  const y = new page.sandbox.XMLHttpRequest();
  y.open("POST", PAGE_URL);
  y.send(null);
  check("没被登记的 path 照常发出去", y.__sent === true);
}

console.log("== 路由表：answer 失败要如实退回出网 ==");
{
  const page = boot({});
  const st = page.sandbox.__MAGIACN_STATE__;
  // responseType='json' 时 serveLocal 会先 JSON.parse，解析不了就必须返回 false，
  // 让请求照常发出去——而不是留下一个 readyState=4、status=0 的半死 XHR。
  st.route({
    name: "t:bad",
    test: (u) => u.indexOf("/api/test/bad") >= 0,
    answer: () => "这不是 JSON"
  });
  const x = new page.sandbox.XMLHttpRequest();
  x.responseType = "json";
  x.open("POST", "https://dorothy.magi-reco.com/magica/api/test/bad");
  x.send("{}");
  check("伪造失败后照常发出去", x.__sent === true);
  check("没有留下半死的 readyState", x.readyState !== 4);
}

console.log("== 路由表：一条路由抛异常不连累请求 ==");
{
  const page = boot({});
  const st = page.sandbox.__MAGIACN_STATE__;
  st.route({
    name: "t:boom",
    test: () => { throw new Error("test 炸了"); },
    request: () => { throw new Error("不该跑到"); }
  });
  st.route({
    name: "t:boom2",
    test: () => true,
    response: () => { throw new Error("response 炸了"); }
  });
  const canned = cannedPage();
  let out;
  let threw = false;
  try { out = page.roundtrip("POST", PAGE_URL, null, canned); } catch (e) { threw = true; }
  check("请求本身没被带崩", threw === false);
  check("其余路由照常工作", out && out.userDeckList instanceof Array);
}

// ── 9. 「还没有人用 answer」是一句会过期的话，钉住它 ──────────────
//
// README 和脚本头部都写着「目前没有任何一条路由用 answer」。这种话最会悄悄
// 变成谎话：谁顺手加一条，文档不会自己更新，而下一个人会照着那句话去信任
// 「本地应答还没启用」。这条判据让「加第一条 answer」变成一个必须同时改文档
// 的动作——不是禁止，是逼着表态。
//
// 判据读源码而不是问运行时：运行时只看得到 __MAGIACN_STATE__.route 登记的那些，
// 而这里要管的恰恰是文件里内置的那几条。
console.log("== 内置路由尚未启用 answer ==");
{
  const builtin = src.match(/\broute\(\{[\s\S]*?\n  \}\);/g) || [];
  check("内置路由抓得到（正则没失效）", builtin.length === 3);
  const withAnswer = builtin.filter((b) => /(^|[^.\w])answer\s*:/.test(b));
  check("没有任何内置路由登记 answer" +
        (withAnswer.length ? "  —— 有了就要同步改 README 与脚本头部那句话" : ""),
        withAnswer.length === 0);
}

console.log("");
console.log("localstate-js-test: " + pass + " 通过, " + fail + " 失败");
process.exit(fail > 0 ? 1 : 0);
