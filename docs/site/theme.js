/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
(() => {
  "use strict";
  const key = "thc-docs-appearance";
  const media = matchMedia("(prefers-color-scheme: dark)");
  if (window.parent !== window) document.documentElement.dataset.thcEmbedded = "";
  let inMemoryChoice = null;
  function choice() {
    // The shell remains authoritative even when browser storage is unavailable.
    try {
      if (window.parent !== window && window.parent.thcTheme) return window.parent.thcTheme.choice();
    } catch (_) { /* A directly embedded cross-origin page uses its own choice. */ }
    if (inMemoryChoice) return inMemoryChoice;
    try {
      const saved = localStorage.getItem(key);
      if (saved === "light" || saved === "dark") return saved;
    } catch (_) { /* Private browsing can disable storage. */ }
    return "system";
  }
  function apply() {
    const selected = choice();
    const dark = selected === "dark" || (selected === "system" && media.matches);
    document.documentElement.dataset.thcTheme = dark ? "dark" : "light";
    // Dokka's own components still use their native theme class.
    document.documentElement.classList.toggle("theme-dark", dark);
    document.querySelectorAll("[data-thc-appearance]").forEach(control => {
      control.setAttribute("aria-pressed", String(control.dataset.thcAppearance === selected));
    });
    try {
      document.getElementById("thc-content")?.contentWindow.thcTheme?.apply();
    } catch (_) { /* External pages do not participate in the shared theme. */ }
  }
  function set(selected) {
    if (!["system", "light", "dark"].includes(selected)) return;
    inMemoryChoice = selected;
    try {
      if (selected === "system") localStorage.removeItem(key);
      else localStorage.setItem(key, selected);
    } catch (_) { /* Keep the explicit choice in this page's memory. */ }
    apply();
  }
  window.thcTheme = { apply, set, choice };
  window.addEventListener("storage", event => {
    if (event.key === key || event.key === null) { inMemoryChoice = null; apply(); }
  });
  media.addEventListener("change", apply);
  // Dokka initializes its own theme after this head script. Reconcile only
  // the theme class, without changing its search or navigation settings.
  new MutationObserver(apply).observe(document.documentElement,
    { attributes: true, attributeFilter: ["class"] });
  document.addEventListener("DOMContentLoaded", apply);
  window.addEventListener("load", apply);
  apply();
})();
