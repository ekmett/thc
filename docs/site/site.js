/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
(() => {
  "use strict";
  const frame = document.getElementById("thc-content");
  const menu = document.querySelector(".thc-menu");
  const rail = document.getElementById("thc-rail");
  const pages = new Set(JSON.parse(document.getElementById("thc-pages").textContent));
  const base = new URL("./", location.href);
  let current = "home.html";

  // Only paths copied into this exact revision's site can be selected. URL
  // normalization handles ../, encoded segments and origins. Generator search
  // may use a query, but only on a page in the generated inventory.
  function route(value) {
    if (typeof value !== "string") return null;
    try {
      const url = new URL(value, base);
      if (url.origin !== base.origin || !url.pathname.startsWith(base.pathname)) return null;
      const page = decodeURIComponent(url.pathname.slice(base.pathname.length));
      return pages.has(page) ? page + url.search + url.hash : null;
    } catch (_) { return null; }
  }

  function shellUrl(page) {
    const url = new URL(base);
    if (page !== "home.html") url.searchParams.set("page", page);
    return url.pathname + url.search;
  }

  function active(page) {
    const path = page.split(/[?#]/, 1)[0];
    const section = path.startsWith("api/haskell/") ? "api/haskell/index.html" :
      path.startsWith("api/jvm/") ? "api/jvm/index.html" : path;
    document.querySelectorAll(".thc-nav-link[data-page]").forEach(link => {
      if (link.dataset.page === section) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    });
  }

  function select(page, push) {
    const valid = route(page) || "home.html";
    if (push && valid !== current) history.pushState(null, "", shellUrl(valid));
    current = valid;
    active(valid);
    const loaded = frameRoute();
    if (loaded !== valid) {
      // A shell click already made one history entry. Replacing the child URL
      // avoids adding a second entry to the browser's joint frame history.
      const absolute = new URL(valid, base).href;
      if (loaded) frame.contentWindow.location.replace(absolute);
      else frame.setAttribute("src", absolute);
    }
    menu.setAttribute("aria-expanded", "false");
    rail.classList.remove("thc-open");
  }

  document.querySelectorAll("[data-page]").forEach(link => link.addEventListener("click", event => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    select(link.dataset.page, true);
  }));
  menu.addEventListener("click", () => {
    const open = menu.getAttribute("aria-expanded") !== "true";
    menu.setAttribute("aria-expanded", String(open));
    rail.classList.toggle("thc-open", open);
  });
  document.getElementById("thc-appearance").addEventListener("change", event => {
    window.thcTheme.set(event.target.value);
  });

  function frameRoute() {
    try { return route(frame.contentWindow.location.href); }
    catch (_) { return null; }
  }
  function syncFrame() {
    const page = frameRoute();
    if (!page) {
      frame.setAttribute("src", current);
      return;
    }
    current = page;
    active(page);
    history.replaceState(null, "", shellUrl(page));
    const inner = frame.contentDocument;
    inner.defaultView.thcTheme?.apply();
    document.title = inner.title ? inner.title + " · THC docs" : "THC documentation";
    // API generators own search, index and source links. Keep external and
    // unlisted targets out of the frame while their local links stay in place.
    inner.addEventListener("click", event => {
      const link = event.target.closest?.("a[href]");
      if (!link) return;
      if (!route(link.href)) {
        if (/^(javascript|data):/i.test(link.href)) { event.preventDefault(); return; }
        link.target = "_blank"; link.rel = "noopener noreferrer";
      }
      else link.target = "_self";
    }, true);
    inner.defaultView.addEventListener("hashchange", () => {
      const next = frameRoute();
      if (next) { current = next; history.replaceState(null, "", shellUrl(next)); }
    });
  }
  frame.addEventListener("load", syncFrame);
  window.addEventListener("popstate", () => select(new URL(location.href).searchParams.get("page") || "home.html", false));
  select(new URL(location.href).searchParams.get("page") || "home.html", false);
})();
