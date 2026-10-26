(function () {
  "use strict";

  var nav = document.getElementById("nav");
  var toggle = document.getElementById("nav-toggle");
  var navLinks = Array.prototype.slice.call(
    document.querySelectorAll('.nav-links a[href^="#"]')
  );

  /* 导航滚动态 */
  function syncNav() {
    if (nav) nav.classList.toggle("scrolled", window.scrollY > 8);
  }
  syncNav();
  window.addEventListener("scroll", syncNav, { passive: true });

  /* 移动端菜单 */
  function closeMenu() {
    document.body.classList.remove("nav-open");
    if (toggle) toggle.setAttribute("aria-expanded", "false");
  }
  if (toggle) {
    toggle.addEventListener("click", function () {
      var open = document.body.classList.toggle("nav-open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
    Array.prototype.forEach.call(
      document.querySelectorAll(".nav-links a"),
      function (a) { a.addEventListener("click", closeMenu); }
    );
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") closeMenu();
    });
    window.addEventListener("resize", function () {
      if (window.innerWidth > 860) closeMenu();
    });
  }

  /* 年份 */
  var year = document.getElementById("year");
  if (year) year.textContent = String(new Date().getFullYear());

  /* 滚动出现 */
  var reveals = Array.prototype.slice.call(document.querySelectorAll(".reveal"));
  if ("IntersectionObserver" in window) {
    var revealIO = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("in");
            revealIO.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -40px 0px" }
    );
    reveals.forEach(function (el) { revealIO.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add("in"); });
  }

  /* 当前区块高亮 */
  var sections = ["work", "notes", "about"]
    .map(function (id) { return document.getElementById(id); })
    .filter(Boolean);

  function setActive(id) {
    navLinks.forEach(function (a) {
      a.classList.toggle("active", a.getAttribute("href") === "#" + id);
    });
  }

  if ("IntersectionObserver" in window && sections.length) {
    var spy = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) setActive(entry.target.id);
        });
      },
      { rootMargin: "-45% 0px -50% 0px", threshold: 0 }
    );
    sections.forEach(function (s) { spy.observe(s); });
  }

  /* ---------------- 以下为主页增强（无对应元素时自动跳过） ---------------- */
  var reduceMotion =
    window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* boot 结束后启动首屏动画；无 boot（已过场/其它页）则立即启动 */
  function afterBoot(fn) {
    if (document.body.classList.contains("booted")) fn();
    else document.addEventListener("hulk:ready", fn, { once: true });
  }

  /* 打字机角色行 */
  var rolesEl = document.getElementById("roles");
  if (rolesEl && !reduceMotion) {
    var roles = [
      "RAG 检索工程 · Dense + BM25 + RRF",
      "Agent 编排 · SSE 流式推送",
      "SPC 统计内核 · 双 σ 口径",
      "双机部署 · Nginx 反代 · TLS"
    ];
    var ri = 0, ci = 0, deleting = false;
    function tickRoles() {
      var text = roles[ri];
      if (!deleting) {
        ci++;
        rolesEl.textContent = text.slice(0, ci);
        if (ci >= text.length) { deleting = true; setTimeout(tickRoles, 2000); return; }
        setTimeout(tickRoles, 52);
      } else {
        ci--;
        rolesEl.textContent = text.slice(0, ci);
        if (ci <= 0) { deleting = false; ri = (ri + 1) % roles.length; setTimeout(tickRoles, 420); return; }
        setTimeout(tickRoles, 20);
      }
    }
    afterBoot(function () { setTimeout(tickRoles, 500); });
  }

  /* 指标数字滚动 */
  var statNums = Array.prototype.slice.call(document.querySelectorAll(".hero-stats b[data-n]"));
  if (statNums.length) {
    function fmt(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ","); }
    function runCount() {
      statNums.forEach(function (el) {
        var target = parseInt(el.getAttribute("data-n"), 10) || 0;
        if (reduceMotion || target <= 0) { el.textContent = fmt(target); return; }
        var start = null, dur = 1100;
        function step(ts) {
          if (!start) start = ts;
          var p = Math.min((ts - start) / dur, 1);
          var eased = 1 - Math.pow(1 - p, 3);
          el.textContent = fmt(Math.round(target * eased));
          if (p < 1) requestAnimationFrame(step);
        }
        el.textContent = "0";
        requestAnimationFrame(step);
        /* 兜底：rAF 被节流（后台标签页/无头渲染）时也能落到最终值 */
        setTimeout(function () { el.textContent = fmt(target); }, dur + 600);
      });
    }
    afterBoot(function () { setTimeout(runCount, 250); });
  }

  /* 首屏鼠标辉光（仅桌面精确指针） */
  var hero = document.querySelector(".hero");
  var glow = document.querySelector(".hero-glow");
  if (hero && glow && !reduceMotion &&
      window.matchMedia && window.matchMedia("(pointer: fine)").matches) {
    var raf = null;
    hero.addEventListener("pointermove", function (e) {
      if (raf) return;
      raf = requestAnimationFrame(function () {
        var rect = hero.getBoundingClientRect();
        glow.style.setProperty("--mx", (e.clientX - rect.left) + "px");
        glow.style.setProperty("--my", (e.clientY - rect.top) + "px");
        raf = null;
      });
    });
    hero.addEventListener("pointerenter", function () { glow.classList.add("on"); });
    hero.addEventListener("pointerleave", function () { glow.classList.remove("on"); });
  }

  /* 系统卡实时日志轮播 */
  var logEl = document.getElementById("sys-log-text");
  if (logEl && !reduceMotion) {
    var logs = [
      "▸ rag recall: dense 24 + bm25 18 → rrf top-3 · 412ms",
      "▸ POST /api/v2/query 200 · stage-gate · 386ms",
      "▸ SSE stream #4821 · trace event pushed",
      "▸ health: ok · milvus 2594 vectors · redis up",
      "▸ case search: gm-demo · answer_min_score 0.31",
      "▸ embed probe: qwen flash · 1024d · 21ms"
    ];
    var li = 0;
    setInterval(function () {
      logEl.classList.add("fade");
      setTimeout(function () {
        li = (li + 1) % logs.length;
        logEl.textContent = logs[li];
        logEl.classList.remove("fade");
      }, 260);
    }, 3000);
  }

  /* 工程证据命令行：固定 3 行窗口，旧行向上滑出、新行底部进入 */
  var cliBody = document.getElementById("cli-body");
  if (cliBody && !reduceMotion) {
    var cliLines = [
      ["pytest -q", "[ok] 1,896 条离线测试 · CI 跑 Py 3.11 / 3.12 / 3.13"],
      ["hulk audit guard", "[ok] 27 权限 × 32 角色 · Agent 持 approve 启动即失败"],
      ["hulk push site --stage", "[ok] 判不出工段 → data_gap 转人工 · 推错比不推更贵"],
      ["hulk diff demo --kpi", "[ok] 演示 OTD 92.4% / 真实 seed 71.4%，两套口径分列"],
      ["hulk verify rag --thresholds", "[ok] 按 2,594 chunk 语料重标，写回配置并注明日期"],
      ["npm run sample:verify", "[ok] Cp 1.8941 / Cpk 1.4246 / Pp 1.0824 / Ppk 0.8141 复算一致"],
      ["curl -N /api/v2/events", "[ok] SSE 零缓冲逐条下发 · 跨 worker 走 Redis Stream"]
    ];
    var ci2 = 3; /* 静态 HTML 已展示前 3 行 */
    setInterval(function () {
      var first = cliBody.querySelector(".cli-line");
      if (!first) return;
      var lh = first.offsetHeight || 26;
      first.classList.add("out");
      first.style.marginTop = (-lh) + "px";
      setTimeout(function () {
        if (first.parentNode === cliBody) cliBody.removeChild(first);
        var item = cliLines[ci2 % cliLines.length];
        ci2++;
        var p = document.createElement("p");
        p.className = "cli-line enter";
        var s1 = document.createElement("span"); s1.className = "cli-p"; s1.textContent = "$";
        var s2 = document.createElement("span"); s2.className = "cli-c"; s2.textContent = item[0];
        var s3 = document.createElement("span"); s3.className = "cli-r"; s3.textContent = item[1];
        p.appendChild(s1); p.appendChild(document.createTextNode(" "));
        p.appendChild(s2); p.appendChild(document.createTextNode(" "));
        p.appendChild(s3);
        cliBody.appendChild(p);
      }, 320);
    }, 2600);
  }
})();
