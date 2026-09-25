/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
(() => {
  const video = document.querySelector(".thc-contact-bot video");
  const button = document.querySelector(".thc-animation-toggle");
  if (!video || !button) return;
  const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
  const update = () => { button.textContent = video.paused ? "Play animation" : "Pause animation"; };
  const respectMotion = () => { if (reducedMotion.matches) video.pause(); };
  video.addEventListener("play", update);
  video.addEventListener("pause", update);
  reducedMotion.addEventListener("change", respectMotion);
  button.addEventListener("click", () => {
    if (video.paused) video.play().catch(update);
    else video.pause();
  });
  respectMotion();
  update();
  button.hidden = false;
})();
