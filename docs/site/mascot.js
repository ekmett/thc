/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
(() => {
  const video = document.querySelector(".thc-contact-bot video");
  const button = document.querySelector(".thc-animation-toggle");
  if (!video || !button) return;
  const update = () => { button.textContent = video.paused ? "Play animation" : "Pause animation"; };
  video.addEventListener("play", update);
  video.addEventListener("pause", update);
  const toggle = () => {
    if (video.paused) video.play().catch(update);
    else video.pause();
  };
  video.addEventListener("click", toggle);
  button.addEventListener("click", toggle);
  video.play().catch(update);
  update();
  button.hidden = false;
})();
