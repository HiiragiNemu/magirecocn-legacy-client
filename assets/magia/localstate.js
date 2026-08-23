/*
 * 本地状态覆盖层 —— 让无状态服务端下的编队活过一次进程。
 *
 * 由 CNDeckState 在 onPageStarted 时 evaluateJavascript 注入，注入时机早于
 * jquery/requirejs，所以这里只依赖 XMLHttpRequest 与 JSON，不碰任何前端模块。
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
 * ── 两条路 ──────────────────────────────────────────────────────────
 *
 *   写：XHR.send 拦 userDeck/save 与两个 bulkSave 的请求体（savePrm 形状），存起来
 *   读：XHR 响应里出现 userDeckList 就用存下来的覆盖/补齐
 *
 * 请求体只有在 JS 层拿得到——Java 侧的 shouldInterceptRequest 看不见 POST body
 * （WebResourceRequest 没有 getBody），这是本脚本必须存在的直接原因。
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
      for (j = 1; j <= 4; j++) delete row[pieceKey(i, j)];
    }

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

    // formationSheet 是个对象，deckDataCreate 会遍历它取 placeSkill* 拼 posArr。
    // 缺了它编成界面会拼不出格子，所以从缓存补——缓存是从任何带
    // formationSheetList/userFormationSheetList 的响应里顺手攒的。
    if (!row.formationSheet && row.formationSheetId !== undefined) {
      var sheet = sheets[String(row.formationSheetId)];
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
    try {
      if (isDeckSave(this.__cnStateUrl || "") && typeof body === "string") capture(this.__cnStateUrl, body);
    } catch (e) {}
    return origSend.apply(this, arguments);
  };

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
    var type = xhr.responseType;

    if (type === "json") {
      var obj = xhr.response;
      if (!obj || typeof obj !== "object") return;
      harvestSheets(obj);
      overlay(obj);
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

    harvestSheets(json);
    if (!overlay(json)) return;      // 没动过就别重新序列化，白费一次大字符串

    var out = JSON.stringify(json);
    try {
      Object.defineProperty(xhr, "responseText", { value: out, configurable: true });
      if (xhr.response !== undefined) {
        Object.defineProperty(xhr, "response", { value: out, configurable: true });
      }
    } catch (e) {}
  }

  // 给前端/调试用的小口子：看当前存了什么、或者整份清掉。
  window.__MAGIACN_STATE__ = {
    decks: function () { return decks; },
    sheets: function () { return sheets; },
    reset: function () {
      decks = {};
      sheets = {};
      try { bridge.remove(NS_DECK); bridge.remove(NS_SHEET); } catch (e) {}
    }
  };
})();
