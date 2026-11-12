// Optional real-browser verification. Requires Playwright and installed Chromium.
const { chromium } = require("playwright");
const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "..");
const env = Object.fromEntries(
  fs
    .readFileSync(path.join(root, ".env"), "utf8")
    .trim()
    .split("\n")
    .map((line) => line.split("=")),
);
const compose = (...args) =>
  execFileSync("docker", ["compose", ...args], { cwd: root, stdio: "pipe" });
const results = [];
const check = (name) => {
  results.push(name);
  console.log("PASS " + name);
};
let browser;
(async () => {
  browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
    headless: true,
    args: ["--no-sandbox"],
  });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.setDefaultNavigationTimeout(15000);
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  async function login(user) {
    await page.goto("http://localhost:8080/login");
    await page.getByRole("textbox", { name: "Username" }).fill(user);
    await page
      .getByLabel("Password")
      .fill(env[user === "admin" ? "ADMIN_PASSWORD" : "OPERATOR_PASSWORD"]);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL("http://localhost:8080/");
  }
  async function submit(key = crypto.randomUUID(), quantity = "2") {
    await page.goto("http://localhost:8080/");
    await page
      .locator('select[name="product"]')
      .selectOption("SENSOR-KIT");
    await page.getByLabel("Quantity", { exact: true }).fill(quantity);
    await page.getByLabel("Idempotency key").fill(key);
    await page.getByRole("button", { name: "Accept order" }).click();
    await page.waitForURL("**/orders/**");
    return page.url();
  }
  async function state(url, status, timeout = 40000) {
    const end = Date.now() + timeout;
    do {
      await page.goto(url);
      if (
        await page
          .locator(".metrics")
          .getByText(status, { exact: true })
          .count()
      )
        return;
      await page.waitForTimeout(1000);
    } while (Date.now() < end);
    throw new Error("State not reached: " + status);
  }
  async function action(value, reason) {
    await page.getByLabel("Reason", { exact: true }).fill(reason);
    await page.locator('select[name="action"]').selectOption(value);
    await page.getByRole("button", { name: "Record & execute" }).click();
    await page.waitForLoadState("networkidle");
  }
  await login("operator");
  const key = crypto.randomUUID();
  const first = await submit(key);
  await state(first, "FULFILLED");
  check("operator submission and worker fulfillment");
  assert.equal(await submit(key), first);
  check("same key returns same order");
  await page.goto("http://localhost:8080/");
  await page.locator('select[name="product"]').selectOption("SENSOR-KIT");
  await page.getByLabel("Quantity", { exact: true }).fill("3");
  await page.getByLabel("Idempotency key").fill(key);
  const conflict = page.waitForResponse(
    (r) => r.url().endsWith("/orders") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Accept order" }).click();
  assert.equal((await conflict).status(), 409);
  assert.match(await page.textContent("body"), /different content/);
  check("conflicting key shows HTTP 409 explanation");
  await page.goto(first);
  assert.equal(
    await page.getByRole("button", { name: "Record & execute" }).count(),
    0,
  );
  check("operator recovery controls hidden");
  await page.getByRole("button", { name: "Sign out" }).click();
  await login("admin");
  compose("stop", "worker");
  let duplicate = await submit();
  await action("DUPLICATE", "Browser verification: duplicate event");
  compose("start", "worker");
  await state(duplicate, "FULFILLED");
  for (let i = 0; i < 10; i++) {
    await page.reload();
    if (await page.getByText("DUPLICATE", { exact: true }).count()) break;
    await page.waitForTimeout(1000);
  }
  assert.ok(await page.getByText("DUPLICATE", { exact: true }).count());
  check("Failure Lab duplicate shows committed duplicate observation");
  compose("stop", "worker");
  let transient = await submit();
  await action("TRANSIENT", "Browser verification: transient error");
  compose("start", "worker");
  await state(transient, "FULFILLED");
  assert.ok(await page.getByText("RETRY", { exact: true }).count());
  check("Failure Lab transient retries then fulfills");
  compose("stop", "worker");
  let permanent = await submit();
  await action("PERMANENT", "Browser verification: business rejection");
  compose("start", "worker");
  await state(permanent, "REJECTED");
  check("Failure Lab permanent rejection");
  compose("stop", "worker");
  let failed = await submit();
  await action("EXHAUST", "Browser verification: bounded exhaustion");
  compose("start", "worker");
  await state(failed, "FAILED", 60000);
  assert.ok(await page.getByText("EXHAUSTED", { exact: true }).count());
  check("Failure Lab bounded retries reach failed state");
  await action("REPLAY", "Browser verification: authorized recovery");
  await state(failed, "FULFILLED");
  assert.ok(await page.getByText("REPLAY · admin", { exact: true }).count());
  check("administrator replay fulfills and preserves audit history");
  fs.mkdirSync(path.join(root, "docs/screenshots"), { recursive: true });
  await page.screenshot({
    path: path.join(root, "docs/screenshots/recovery.png"),
    fullPage: true,
  });
  compose("stop", "rabbitmq");
  let outage = await submit();
  await page.waitForTimeout(6500);
  await state(outage, "ACCEPTED");
  assert.ok(
    await page.getByText("Pending confirmation", { exact: true }).count(),
  );
  check("broker outage leaves accepted order and pending outbox");
  compose("start", "rabbitmq");
  await state(outage, "FULFILLED", 60000);
  check("broker recovery drains pending outbox");
  await page.goto("http://localhost:8080/?q=FAILED");
  assert.match(await page.textContent("body"), /No orders found/);
  check("failed order search reflects recovered state");
  await page.goto("http://localhost:8080/");
  await page.screenshot({
    path: path.join(root, "docs/screenshots/desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  assert.ok(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  );
  await page.screenshot({
    path: path.join(root, "docs/screenshots/mobile.png"),
    fullPage: true,
  });
  check("mobile layout fits 390px viewport");
  assert.deepEqual(errors, []);
  check("no browser JavaScript errors");
  fs.writeFileSync(
    path.join(root, "docs/browser-results.json"),
    JSON.stringify(
      {
        checks: results,
        screenshots: ["desktop.png", "mobile.png", "recovery.png"],
      },
      null,
      2,
    ) + "\n",
  );
  await browser.close();
})().catch(async (e) => {
  if (browser) await browser.close();
  try {
    compose("start", "rabbitmq", "worker");
  } catch {}
  console.error(e);
  process.exit(1);
});
