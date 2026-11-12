document.addEventListener("submit", (event) => {
  const button = event.target.querySelector("button");
  if (button) {
    button.dataset.label = button.textContent;
    button.textContent = "Working…";
    button.disabled = true;
  }
});
window.addEventListener("pageshow", () => {
  document.querySelectorAll("button[data-label]").forEach((button) => {
    button.textContent = button.dataset.label;
    button.disabled = false;
  });
});
