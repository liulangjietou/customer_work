// ==UserScript==
// @name         内网工作台统一自动登录
// @namespace    https://customer-admin/workbench
// @version      2.0.0
// @description  进内网系统登录页后，凭个人令牌向工作台换取账号密码并自动填表登录（替代逐站点硬编码脚本）
// @author       customer-admin workbench
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @connect      __CONNECT_HOST__
__MATCH_BLOCK__
// ==/UserScript==

/*
 * 本脚本由后台「内网工作台」动态生成，内嵌一次性个人令牌，请勿外传。
 * 站点账号密码与选择器配置全部存在工作台，脚本本身不含任何凭据明文。
 * 新增站点后请回后台重新生成并覆盖安装（@match 列表会随启用站点更新）。
 */
(function () {
  'use strict';

  var TOKEN = '__TOKEN__';
  var API_BASE = '__API_BASE__';

  // 时序/等待参数（毫秒）
  var PROBE_INTERVAL = 300;   // 探测登录框轮询间隔
  var PROBE_TIMEOUT = 8000;   // 探测登录框最长等待（非登录页会自然超时静默退出）
  var ELEMENT_TIMEOUT = 15000; // 定位用户名/密码/按钮的最长等待

  if (window.__workbenchAutoLoginStarted) {
    return;
  }
  window.__workbenchAutoLoginStarted = true;

  var LOG_PREFIX = '[工作台自动登录]';

  // 用原生 setter 写值，绕过 React/Ember 等框架对输入的内部追踪（是所有站点的兼容超集）
  function setNativeValue(input, value) {
    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(input, value);
  }

  function dispatchInputEvents(input) {
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    input.dispatchEvent(new Event('blur', { bubbles: true }));
  }

  // 一次性填充：原生 setter + 事件序列
  function fillAuto(input, value) {
    input.focus();
    setNativeValue(input, value);
    dispatchInputEvents(input);
  }

  // 逐字打字：给最顽固的受控组件（如 Kibana/EUI），每字符补 InputEvent
  function fillTyping(input, value) {
    input.focus();
    setNativeValue(input, '');
    for (var i = 0; i < value.length; i++) {
      setNativeValue(input, input.value + value[i]);
      input.dispatchEvent(new InputEvent('input', {
        data: value[i], inputType: 'insertText', bubbles: true, cancelable: true
      }));
    }
    input.dispatchEvent(new Event('change', { bubbles: true }));
    input.blur();
  }

  // 轮询等待某个 getter 返回非空元素
  function waitFor(getter, timeout) {
    return new Promise(function (resolve, reject) {
      var found = getter();
      if (found) { resolve(found); return; }
      var start = Date.now();
      var timer = setInterval(function () {
        var el = getter();
        if (el) {
          clearInterval(timer);
          resolve(el);
        } else if (Date.now() - start > timeout) {
          clearInterval(timer);
          reject(new Error('timeout'));
        }
      }, PROBE_INTERVAL);
    });
  }

  // 启发式定位用户名框：密码框之前 DOM 顺序最近的可见文本输入框
  function heuristicUsername(passwordEl) {
    var inputs = Array.prototype.slice.call(document.querySelectorAll('input'));
    var pwdIndex = inputs.indexOf(passwordEl);
    for (var i = pwdIndex - 1; i >= 0; i--) {
      var t = (inputs[i].getAttribute('type') || 'text').toLowerCase();
      if (t !== 'password' && t !== 'hidden' && t !== 'checkbox' && t !== 'radio' && t !== 'submit') {
        return inputs[i];
      }
    }
    return null;
  }

  // 启发式定位登录按钮：优先密码框所在表单内的提交按钮，其次含"登录/log in"文本的按钮
  function heuristicSubmit(passwordEl) {
    var form = passwordEl.closest('form');
    var scope = form || document;
    var btn = scope.querySelector('button[type="submit"], input[type="submit"]');
    if (btn) { return btn; }
    var candidates = scope.querySelectorAll('button, input[type="button"], a');
    for (var i = 0; i < candidates.length; i++) {
      var text = (candidates[i].textContent || candidates[i].value || '').toLowerCase();
      if (text.indexOf('登录') >= 0 || text.indexOf('登陆') >= 0
        || text.indexOf('log in') >= 0 || text.indexOf('login') >= 0 || text.indexOf('sign in') >= 0) {
        return candidates[i];
      }
    }
    return null;
  }

  function requestCredential() {
    return new Promise(function (resolve, reject) {
      GM_xmlhttpRequest({
        method: 'GET',
        url: API_BASE + '/api/workbench/agent/site?host=' + encodeURIComponent(location.host),
        headers: { 'X-Workbench-Token': TOKEN },
        onload: function (resp) {
          if (resp.status !== 200) { reject(new Error('http ' + resp.status)); return; }
          try {
            var body = JSON.parse(resp.responseText);
            if (body.code !== 0 || !body.data) { reject(new Error(body.message || 'no data')); return; }
            resolve(body.data);
          } catch (e) {
            reject(e);
          }
        },
        onerror: function () { reject(new Error('network error')); }
      });
    });
  }

  async function run() {
    // 1) 先探测密码框；非登录页探测不到会超时，静默退出，不向后台发任何请求
    var passwordProbe;
    try {
      passwordProbe = await waitFor(function () { return document.querySelector('input[type="password"]'); }, PROBE_TIMEOUT);
    } catch (e) {
      return; // 当前不是登录页
    }

    // 2) 凭令牌换取该站点凭证与配置
    var cfg;
    try {
      cfg = await requestCredential();
    } catch (e) {
      console.info(LOG_PREFIX, '未取到本站配置，跳过：', e.message);
      return;
    }

    // 3) 按 initDelay 等待后定位三要素（选择器留空走启发式）
    await new Promise(function (r) { setTimeout(r, cfg.initDelayMs || 500); });

    var passwordEl = await waitFor(function () {
      return cfg.passwordSelector ? document.querySelector(cfg.passwordSelector) : passwordProbe;
    }, ELEMENT_TIMEOUT).catch(function () { return null; });
    if (!passwordEl) { console.error(LOG_PREFIX, '未找到密码框'); return; }

    var usernameEl = cfg.usernameSelector
      ? document.querySelector(cfg.usernameSelector)
      : heuristicUsername(passwordEl);
    if (!usernameEl) { console.error(LOG_PREFIX, '未找到用户名框'); return; }

    var submitEl = cfg.submitSelector
      ? document.querySelector(cfg.submitSelector)
      : heuristicSubmit(passwordEl);

    // 4) 填充
    var fill = cfg.fillMode === 'typing' ? fillTyping : fillAuto;
    fill(usernameEl, cfg.account || '');
    fill(passwordEl, cfg.password || '');

    // 5) 提交
    setTimeout(function () {
      try {
        if (cfg.submitMode === 'formSubmit') {
          var form = passwordEl.closest('form');
          if (form) { form.submit(); return; }
        }
        if (submitEl) {
          submitEl.click();
          submitEl.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
        } else {
          console.error(LOG_PREFIX, '未找到登录按钮');
        }
      } catch (e) {
        console.error(LOG_PREFIX, '提交失败：', e.message);
      }
    }, cfg.submitDelayMs || 300);

    console.info(LOG_PREFIX, '已自动填充并提交：', location.host);
  }

  run();
})();
