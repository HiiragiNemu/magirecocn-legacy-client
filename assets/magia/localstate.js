/*
 * 本地状态覆盖层 —— 让无状态服务端下的编队活过一次进程。
 *
 * 由 CNDeckState 注入（两条路：CNWebProxy 的 onPageStarted，以及 CNDeckState 自己
 * 那条轮询的探针补注）。注入时机早于 jquery/requirejs，所以这里只依赖
 * XMLHttpRequest 与 JSON，不碰任何前端模块。
 *
 * 开头那个 __MAGIACN_LOCAL_STATE__ 标记既是重入保护，也是 Java 侧「这个文档注过
 * 没有」的探针目标——它必须在最前面置上，且必须早于「取不到桥就返回」那一支，
 * 否则没有桥的文档会被反复重注。
 *
 * ── 为什么是「覆盖响应」而不是「重放请求」 ──────────────────────────
 *
 * Totentanz 服务端无状态：userDeck/save 发出去，罐头响应回来,服务端一个字节
 * 都不记。所以把 payload 存下来下次再 POST 一遍是没有意义的——回来的还是罐头。
 *
 * 真正管用的是反过来：把玩家存的编队记在客户端，然后在**每一个带 userDeckList
 * 的响应到达前端之前**把它换成我们记住的那份。前端的 responseSetStorage 从响应
 * 读进 storage，它读到什么就信什么——这一点在 backboneCommon.js 里是无条件的。
 *
 * ── 三种介入方式，由一张路由表统一登记 ──────────────────────────────
 *
 *   request(url, body)     看请求体（唯一能看到 POST body 的地方）
 *   response(json, url)    改响应，返回是否动过
 *   answer(url, body)      **整份本地应答**，返回字符串即不发网络请求
 *
 * 请求体只有在 JS 层拿得到——Java 侧的 shouldInterceptRequest 看不见 POST body
 * （WebResourceRequest 没有 getBody），这是本脚本必须存在的直接原因。
 *
 * 为什么要是一张表而不是几个 if：2026-08-27 的真机日志把整条战斗链路摊开了，
 * 全部走 WebView，全部是这一层看得见的 XHR：
 *
 *     MainQuest → MainQuestBranch → QuestBattleSelect → SupportSelect
 *       → DeckFormation → quest/start（敌人配置）→ …战斗在 native 跑…
 *       → QuestResult（结果回传）
 *
 * 也就是说「服务端要做的事」在这一层是**可枚举**的。要往那个方向走，就不能每
 * 加一条端点就在 send/onreadystatechange 里多插一段 if——那会长成没人敢动的
 * 一坨。表的形状逼着每条端点自报「我拦哪个 path、我是改响应还是整份自己答」。
 *
 * ⚠ 目前**没有任何一条路由用 answer**：本地应答要先有那个端点的真实响应样本
 * （归档里的 magica/api/<路径>/NNN.json），照着形状答才有意义；照猜的形状答
 * 只会把前端弄崩，而且崩在离原因很远的地方。机制先立好，第一条何时登记是另一
 * 件事，见 tools/localstate-js-test.js 里对 answer 的判据。
 *
 * 落盘走 CNLocalState（addJavascriptInterface 挂进来的 Java 桥），不是
 * localStorage——理由见 CNWebStateBridge 的类注释。
 */
(function () {
  "use strict";

  if (window.__MAGIACN_LOCAL_STATE__) return;   // 页面重载时不重复挂
  window.__MAGIACN_LOCAL_STATE__ = true;

  var NS_DECK = "deck";     // 编队：deckType -> savePrm
  var NS_SHEET = "sheet";   // 阵形：formationSheetId -> formationSheet 对象

  var bridge = null;
  try { bridge = window.CNLocalState || null; } catch (e) { bridge = null; }
  if (!bridge) return;      // 桥没挂上（开关关了）——什么都不做，行为与没有本脚本一致

  // ── 存取 ────────────────────────────────────────────────────────

  function load(ns) {
    try {
      var raw = bridge.get(ns);
      if (!raw) return {};
      var v = JSON.parse(raw);
      return (v && typeof v === "object" && !(v instanceof Array)) ? v : {};
    } catch (e) { return {}; }
  }

  function save(ns, obj) {
    try { return !!bridge.set(ns, JSON.stringify(obj)); } catch (e) { return false; }
  }

  // 内存镜像：每个响应都要读，不缓存就是每个响应打一次 JNI。
  var decks = load(NS_DECK);
  var sheets = load(NS_SHEET);

  // ── savePrm ←→ userDeckList 行 ──────────────────────────────────

  /* 与 DeckFormationUtil.savePrmCreate 里的 ("000"+(f+1)+(r+1)).slice(-3) 同源。 */
  function pieceKey(slot, idx) { return "userPieceId" + ("000" + slot + idx).slice(-3); }

  /*
   * 把 savePrm（请求体形状）还原成 userDeckList 的一行（响应形状）。
   *
   * 两种形状不是一回事：请求体用数组（userCardIds / questPositionIds /
   * userPieceIdLists），响应用编号字段（userCardId1..9 / questPositionId1..9 /
   * userPieceId011..）。savePrmCreate 是前者的构造方，这里是它的逆。
   */
  function toRow(prm, prev) {
    var row = {};
    var i, j;

    // 先铺上一份旧行：userId、getTime 之类我们不产生的字段要留住，
    // 否则前端某些页面按它们取值会拿到 undefined。
    if (prev) for (var k in prev) if (Object.prototype.hasOwnProperty.call(prev, k)) row[k] = prev[k];

    // 我们负责的字段全部重写（包括置空），不能只覆盖非空的那些——
    // 玩家把一个位置的魔法少女撤下来，就是要那个 userCardIdN 消失。
    for (i = 1; i <= 10; i++) {
      delete row["userCardId" + i];
      delete row["questPositionId" + i];
      delete row["rentalPieceSetId" + i];
      delete row["switchNpcFlag" + i];
      for (j = 1; j <= 4; j++) delete row[pieceKey(i, j)];
    }
    delete row.switchNpcEventId;

    row.deckType = prm.deckType;
    if (prm.name !== undefined) row.name = prm.name;
    if (prm.formationSheetId !== undefined) row.formationSheetId = prm.formationSheetId;
    if (prm.questPositionHelper !== undefined) row.questPositionHelper = prm.questPositionHelper;
    // 请求体叫 episodeUserCardId，响应行叫 questEpisodeUserCardId——不是笔误，
    // 是原版两侧本来就不同名（见归档样本 magica/api/userDeck/save/001.json）。
    if (prm.episodeUserCardId !== undefined) row.questEpisodeUserCardId = prm.episodeUserCardId;

    var cards = prm.userCardIds || [];
    var poses = prm.questPositionIds || [];
    var pieces = prm.userPieceIdLists || [];
    for (i = 0; i < cards.length && i < 10; i++) {
      if (!cards[i]) continue;
      row["userCardId" + (i + 1)] = cards[i];
      if (poses[i] !== undefined && poses[i] !== null) row["questPositionId" + (i + 1)] = poses[i];
      var list = pieces[i] || [];
      for (j = 0; j < list.length && j < 4; j++) {
        if (list[j]) row[pieceKey(i + 1, j + 1)] = list[j];
      }
    }

    var rentals = prm.rentalPieceSetIdList;
    if (rentals) {
      for (i = 0; i < rentals.length && i < 10; i++) {
        if (rentals[i] !== null && rentals[i] !== undefined) row["rentalPieceSetId" + (i + 1)] = rentals[i];
      }
    }

    // switchNpc 系：请求体是 switchNpcEventId + switchNpcFlagList[]，响应行是
    // switchNpcEventId + switchNpcFlagN（归档里见过 switchNpcFlag3）。
    // 漏掉它们的后果不是报错，是 DeckFormation.js 里那段
    // `-1!==b.indexOf("switchNpcFlag")` 每次都判不到，切换 NPC 的状态被静默抹平。
    if (prm.switchNpcEventId !== undefined && prm.switchNpcEventId !== null) {
      row.switchNpcEventId = prm.switchNpcEventId;
    }
    var npcFlags = prm.switchNpcFlagList;
    if (npcFlags) {
      for (i = 0; i < npcFlags.length && i < 10; i++) {
        if (npcFlags[i] !== null && npcFlags[i] !== undefined) {
          row["switchNpcFlag" + (i + 1)] = npcFlags[i];
        }
      }
    }

    // formationSheet 是个对象，deckDataCreate 会遍历它取 placeSkill* 拼 posArr，
    // 缺了它编成界面会拼不出格子。
    //
    // ⚠ 它必须跟着 formationSheetId 一起换：上面从 prev 拷了一整行，其中就带着
    // **旧的** formationSheet。玩家换过阵形之后 formationSheetId 是新的、
    // formationSheet 还是旧的——两者对不上，格子会按旧阵形排。所以先删掉再按
    // 新 id 找，只有 id 没变时才允许回退到 prev 那一份。
    var prevSheet = (prev && prev.formationSheet) ? prev.formationSheet : null;
    var prevSheetId = prev ? prev.formationSheetId : undefined;
    delete row.formationSheet;
    if (row.formationSheetId !== undefined) {
      var sheet = sheets[String(row.formationSheetId)];
      if (!sheet && prevSheet && prevSheetId === row.formationSheetId) sheet = prevSheet;
      if (sheet) row.formationSheet = sheet;
    }
    return row;
  }

  // ── 攒阵形对象 ──────────────────────────────────────────────────

  function harvestSheets(json) {
    var changed = false;
    var lists = [json.userFormationSheetList, json.formationSheetList];
    for (var n = 0; n < lists.length; n++) {
      var arr = lists[n];
      if (!(arr instanceof Array)) continue;
      for (var i = 0; i < arr.length; i++) {
        var e = arr[i];
        if (!e) continue;
        var sheet = e.formationSheet || (e.sheetType !== undefined ? e : null);
        var id = e.formationSheetId !== undefined ? e.formationSheetId
               : (sheet && sheet.id !== undefined ? sheet.id : undefined);
        if (id === undefined || !sheet) continue;
        var key = String(id);
        if (!sheets[key]) { sheets[key] = sheet; changed = true; }
      }
    }
    if (changed) save(NS_SHEET, sheets);
  }

  // ── 覆盖 ────────────────────────────────────────────────────────

  /*
   * 用存下来的编队覆盖响应里的 userDeckList。
   *
   * 三件事：已有的行按 deckType 换掉、没有的行补进去、引用了账号里不存在的
   * 魔法少女的位置就地摘掉。
   *
   * 最后那条只影响**这一次**的覆盖结果，不回写存档：服务端无状态，
   * userCardList 某次回来不全并不代表玩家真的失去了那张卡，据此把存档改小
   * 就是拿一次响应的抖动去销毁玩家的编队。
   */
  function overlay(json) {
    if (!(json.userDeckList instanceof Array)) return false;

    var owned = null;
    if (json.userCardList instanceof Array) {
      owned = {};
      for (var c = 0; c < json.userCardList.length; c++) {
        var card = json.userCardList[c];
        if (card && card.id !== undefined) owned[String(card.id)] = true;
      }
    }

    var byType = {};
    for (var i = 0; i < json.userDeckList.length; i++) {
      var r = json.userDeckList[i];
      if (r && r.deckType !== undefined) byType[String(r.deckType)] = i;
    }

    var touched = false;
    for (var key in decks) {
      if (!Object.prototype.hasOwnProperty.call(decks, key)) continue;
      var prm = decks[key];
      if (!prm || prm.deckType === undefined) continue;
      var at = byType[key];
      var row = toRow(prm, at !== undefined ? json.userDeckList[at] : null);
      if (owned) dropUnowned(row, owned);
      if (at !== undefined) json.userDeckList[at] = row;
      else json.userDeckList.push(row);
      touched = true;
    }
    return touched;
  }

  /* 摘掉引用了账号里不存在的 userCardId 的位置，连同它那格的记忆一起。 */
  function dropUnowned(row, owned) {
    for (var i = 1; i <= 10; i++) {
      var id = row["userCardId" + i];
      if (id === undefined || owned[String(id)]) continue;
      delete row["userCardId" + i];
      delete row["questPositionId" + i];
      for (var j = 1; j <= 4; j++) delete row[pieceKey(i, j)];
      if (row.questEpisodeUserCardId === id) delete row.questEpisodeUserCardId;
    }
  }

  // ── 路由表 ──────────────────────────────────────────────────────
  //
  // 一条路由 = { name, test(url), request?, response?, answer? }，三个钩子都可选：
  //
  //   request(url, body)      纯观察，看得到 POST 体。不返回值。
  //   response(json, url)     就地改 json，返回 true 表示动过（动过才重新序列化）。
  //   answer(url, body)       返回字符串 = 这一条**不出网**，就用这份当响应；
  //                           返回 null/undefined = 照常发出去。
  //
  // test 返回 false 的路由三个钩子都不跑。test 恒真的路由（如攒阵形）也允许，
  // 但要在 name 上写清楚，否则一眼看不出它对全站生效。

  var ROUTES = [];
  var STATS = {};

  function route(def) {
    ROUTES.push(def);
    STATS[def.name] = { req: 0, res: 0, ans: 0 };
  }

  function bump(name, kind) {
    var s = STATS[name];
    if (s) s[kind]++;
  }

  /* 钩子里抛出去会连累前端的请求本身，所以每条路由都各自兜住。 */
  function safe(fn, a, b) {
    try { return fn(a, b); } catch (e) { return undefined; }
  }

  function matches(r, url) {
    try { return !!r.test(url); } catch (e) { return false; }
  }

  // ── 捕获 ────────────────────────────────────────────────────────

  /* 这三个端点的请求体就是玩家的编队意图，是本脚本唯一的信息来源。 */
  function isDeckSave(url) {
    return /\/magica\/api\/userDeck\/(save|bulkSave)(?:\?|$)/.test(url)
        || /\/magica\/api\/userDeck\/extermination\/bulkSave(?:\?|$)/.test(url);
  }

  function capture(url, body) {
    if (!body) return;
    var payload;
    try { payload = JSON.parse(body); } catch (e) { return; }
    if (!payload) return;

    var list = (payload.userDeckList instanceof Array) ? payload.userDeckList : [payload];
    var changed = false;
    for (var i = 0; i < list.length; i++) {
      var prm = list[i];
      if (!prm || prm.deckType === undefined || prm.deckType === null) continue;
      decks[String(prm.deckType)] = prm;
      changed = true;
    }
    if (changed) save(NS_DECK, decks);
  }

  // ── 登记 ────────────────────────────────────────────────────────
  //
  // 现有行为原样搬过来，一条不多一条不少：编队捕获 + 编队覆盖 + 攒阵形。
  // 顺序有意义：攒阵形排在覆盖之前，因为 toRow 会去 sheets 里找 formationSheet
  // ——同一份响应里既带着新阵形又带着 userDeckList 时，先攒后覆盖才拼得出格子。

  route({
    name: "deck:capture",
    test: isDeckSave,
    request: function (url, body) {
      if (typeof body === "string") capture(url, body);
    }
  });

  route({
    name: "sheet:harvest(全站)",
    test: function () { return true; },
    response: function (json) { harvestSheets(json); return false; }
  });

  route({
    name: "deck:overlay(全站)",
    test: function () { return true; },
    response: function (json) { return overlay(json); }
  });

  // ── XHR 挂钩 ────────────────────────────────────────────────────
  //
  // 直接挂 XMLHttpRequest.prototype 而不是 jQuery.ajaxPrefilter：本脚本注入
  // 时机早于 jquery，那会儿 window.jQuery 还不存在。XHR 原型任何时候都在。

  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    try {
      this.__cnStateUrl = String(url || "");
      var self = this;
      this.addEventListener("readystatechange", function () {
        if (self.readyState !== 4) return;
        try { applyToResponse(self); } catch (e) {}
      });
    } catch (e) {}
    return origOpen.apply(this, arguments);
  };

  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (body) {
    var url = this.__cnStateUrl || "";
    var i, r;
    try {
      for (i = 0; i < ROUTES.length; i++) {
        r = ROUTES[i];
        if (!r.request || !matches(r, url)) continue;
        safe(r.request, url, body);
        bump(r.name, "req");
      }
      // answer 排在 request 之后：先让所有观察者看过这次请求体，再决定出不出网。
      // 否则一条路由自己答掉，另一条就永远看不到玩家干了什么。
      for (i = 0; i < ROUTES.length; i++) {
        r = ROUTES[i];
        if (!r.answer || !matches(r, url)) continue;
        var out = safe(r.answer, url, body);
        if (typeof out !== "string") continue;
        bump(r.name, "ans");
        if (serveLocal(this, out)) return;    // 不出网
        break;                                // 伪造失败就照常发，别再问下一条
      }
    } catch (e) {}
    return origSend.apply(this, arguments);
  };

  /*
   * 本地整份应答：不发网络请求，直接把 text 当成这次 XHR 的响应交给前端。
   *
   * ⚠ 这是整个脚本里唯一「无中生有」的地方，做不到就必须**如实失败**——
   * 返回 false 让调用方照常发出去，而不是留下一个半死的 XHR。前端拿着一个
   * readyState=4 但 status=0 的对象，报出来的错会离原因十万八千里。
   *
   * 伪造的范围有意划得很窄：readyState / status / statusText / responseText /
   * response，外加把 readystatechange 与 load 派发一遍。**没有响应头**——
   * getAllResponseHeaders 仍是原样，谁要是依赖它就会看到空的。第一条真的
   * answer 路由登记之前，这一点得先确认前端不在乎。
   *
   * 异步派发（setTimeout 0）而不是同步：真实 XHR 的 send() 一定是先返回、
   * 回调后到。同步派发会让前端在自己还没写完 onreadystatechange 的时候就被回调，
   * 那种 bug 只在真机上偶发。
   */
  function serveLocal(xhr, text) {
    var body;
    var wantJson = xhr.responseType === "json";

    // ⚠ 先把 response 路由跑完，再把结果暴露出去——顺序错了会静默走偏。
    //
    // 正常网络路径上我们是安全的：本脚本在 open() 里 addEventListener，注册得比
    // jquery 早，所以覆盖先发生、前端后拿到。而这里是**伪造**的派发，下面那圈
    // 先叫 onreadystatechange 再 dispatchEvent；jquery 用的正是 onreadystatechange
    // ——于是本地应答这条路上，前端会先拿到**没跑过覆盖**的那份。
    //
    // 与其去复刻真实 XHR 的监听器顺序（那东西依赖 onX 属性何时被赋值，脆得很），
    // 不如让 body 在被任何人看见之前就已经是最终形态。跑完打上 __cnStateServed，
    // applyToResponse 见到就跳过，避免同一份 json 被覆盖两遍。
    // 本地应答**必须是合法 JSON 对象**，否则如实退回出网。
    //
    // 第一版写的是「只有 responseType==='json' 时才校验」——那条承诺只在一半
    // 情况下成立：默认的 responseType 是空串，于是一份写坏的应答会被原样塞给
    // 前端，表现是前端在离这里很远的地方解析炸掉。而整个游戏 API 都是 JSON
    // （applyToResponse 里那句「不是 { 开头的直接放过」是同一个前提），所以
    // 把判据拉齐成「一律要求 JSON」，两种 responseType 下行为一致。
    var obj = null;
    try { obj = JSON.parse(text); } catch (e) { obj = null; }
    if (!obj || typeof obj !== "object") return false;
    try {
      runResponseRoutes(obj, xhr.__cnStateUrl || "");
      text = JSON.stringify(obj);
    } catch (e) {}
    body = wantJson ? obj : text;

    try {
      define(xhr, "readyState", 4);
      define(xhr, "status", 200);
      define(xhr, "statusText", "OK");
      define(xhr, "responseText", text);
      define(xhr, "response", body);
    } catch (e) {
      return false;
    }
    // ⚠ 这一句必须排在上面那圈 define **全部成功之后**。
    // 放在前面的话，只要中间任何一个 defineProperty 抛了，我们就带着已经置上的
    // __cnStateServed 走 return false —— 请求照常发出去，而 applyToResponse 见到
    // 这个标记会直接跳过，于是这条真实响应**一次覆盖都不会跑**。
    // 「伪造失败就当无事发生」是这个函数的全部承诺，标记早置一行就毁了它。
    define(xhr, "__cnStateServed", true);
    var fire = function () {
      try { if (typeof xhr.onreadystatechange === "function") xhr.onreadystatechange(); } catch (e) {}
      try { if (typeof xhr.dispatchEvent === "function") xhr.dispatchEvent(mkEvent("readystatechange")); } catch (e) {}
      try { if (typeof xhr.onload === "function") xhr.onload(); } catch (e) {}
      try { if (typeof xhr.dispatchEvent === "function") xhr.dispatchEvent(mkEvent("load")); } catch (e) {}
    };
    if (typeof setTimeout === "function") setTimeout(fire, 0); else fire();
    return true;
  }

  function define(obj, name, value) {
    Object.defineProperty(obj, name, { value: value, configurable: true, writable: true });
  }

  function mkEvent(type) {
    try {
      if (typeof Event === "function") return new Event(type);
    } catch (e) {}
    return { type: type };
  }

  /*
   * 把覆盖结果写回这个 XHR 的响应。
   *
   * responseType 分两种走法，与 jquery 里那个 i18n 注入器同源：
   *   'json' —— response 已经是对象，就地改，前端拿到的就是改过的；
   *   ''/'text' —— 只能重新序列化再 defineProperty 盖掉 responseText。
   *
   * 两个注入器都会盖 responseText。i18n 那份在 jquery.min.js 里，加载晚于本
   * 脚本，于是它的 open 包装更外层、监听器先跑——翻译先发生，我们后覆盖。
   * 顺序无所谓：编队字段全是 ID，翻译不碰它们。
   */
  function applyToResponse(xhr) {
    // serveLocal 已经在暴露之前跑过一轮路由了，再跑一遍等于对同一份 json 覆盖两次。
    if (xhr.__cnStateServed) return;
    var type = xhr.responseType;
    var url = xhr.__cnStateUrl || "";

    if (type === "json") {
      var obj = xhr.response;
      if (!obj || typeof obj !== "object") return;
      runResponseRoutes(obj, url);   // 就地改，前端拿到的就是改过的，不必回写
      return;
    }

    if (type !== "" && type !== "text") return;

    var text = xhr.responseText;
    if (!text) return;
    var first = text.charAt(0);
    // 整个游戏的 API 都是 JSON。不是 { 开头的直接放过，省掉一次无谓的解析。
    if (first !== "{") return;

    var json;
    try { json = JSON.parse(text); } catch (e) { return; }
    if (!json || typeof json !== "object") return;

    if (!runResponseRoutes(json, url)) return;   // 没动过就别重新序列化，白费一次大字符串

    var out = JSON.stringify(json);
    try {
      Object.defineProperty(xhr, "responseText", { value: out, configurable: true });
      if (xhr.response !== undefined) {
        Object.defineProperty(xhr, "response", { value: out, configurable: true });
      }
    } catch (e) {}
  }

  /*
   * 跑一遍 response 钩子，返回「有没有人动过 json」。
   *
   * ⚠ 不能在第一个返回 true 的地方短路：路由之间是叠加关系不是择一关系
   * （攒阵形不改 json 但必须跑，编队覆盖要跑在它之后）。「动过」是或运算。
   */
  function runResponseRoutes(json, url) {
    var touched = false;
    for (var i = 0; i < ROUTES.length; i++) {
      var r = ROUTES[i];
      if (!r.response || !matches(r, url)) continue;
      if (safe(r.response, json, url) === true) {
        touched = true;
        bump(r.name, "res");
      }
    }
    return touched;
  }

  // 给前端/调试用的小口子：看当前存了什么、路由各命中多少次、或者整份清掉。
  //
  // stats 不只是好看：要判断「某条端点该不该本地答」，第一步就是知道它一局里
  // 到底被叫了几次。chrome://inspect 里敲 __MAGIACN_STATE__.stats() 就有。
  window.__MAGIACN_STATE__ = {
    decks: function () { return decks; },
    sheets: function () { return sheets; },
    routes: function () {
      var out = [];
      for (var i = 0; i < ROUTES.length; i++) out.push(ROUTES[i].name);
      return out;
    },
    stats: function () { return STATS; },
    /* 让别处（将来的前端包）也能登记路由，不必改这个文件。 */
    route: route,
    reset: function () {
      decks = {};
      sheets = {};
      try { bridge.remove(NS_DECK); bridge.remove(NS_SHEET); } catch (e) {}
    }
  };
})();
