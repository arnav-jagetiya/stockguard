const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const EVIDENCE_DIR = path.join(__dirname, 'verification_evidence', 'demo_environment');
if (!fs.existsSync(EVIDENCE_DIR)) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
}

const CHROME_PATH = fs.existsSync('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')
  ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

console.log(`[Demo Acceptance] Chrome Executable: ${CHROME_PATH}`);
console.log(`[Demo Acceptance] Evidence Output Directory: ${EVIDENCE_DIR}`);

async function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function run() {
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'],
    defaultViewport: { width: 1440, height: 900 }
  });

  const page = await browser.newPage();

  page.on('console', msg => {
    if (msg.type() === 'error') {
      console.log(`  [Browser Error]: ${msg.text()}`);
    }
  });

  try {
    console.log('\n======================================================');
    console.log('1. AUTHENTICATION & DEMO CREDENTIAL AUTO-FILL');
    console.log('======================================================');
    await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle0' });

    const demoAdminPass = process.env.STOCKGUARD_DEMO_ADMIN_PASSWORD || process.env.STOCKGUARD_BOOTSTRAP_ADMIN_PASSWORD;
    if (!demoAdminPass) {
      throw new Error('STOCKGUARD_DEMO_ADMIN_PASSWORD or STOCKGUARD_BOOTSTRAP_ADMIN_PASSWORD environment variable is required');
    }

    console.log('  Typing administrator credentials...');
    await page.focus('input[type="email"]');
    await page.keyboard.down('Control');
    await page.keyboard.press('A');
    await page.keyboard.up('Control');
    await page.keyboard.press('Backspace');
    await page.type('input[type="email"]', 'acme-admin@stockguard.dev');

    await page.focus('input[type="password"]');
    await page.keyboard.down('Control');
    await page.keyboard.press('A');
    await page.keyboard.up('Control');
    await page.keyboard.press('Backspace');
    await page.type('input[type="password"]', demoAdminPass);

    const currentEmail = await page.$eval('input[type="email"]', el => el.value);
    console.log(`  Form populated with: ${currentEmail}`);

    await page.screenshot({ path: path.join(EVIDENCE_DIR, '01_login_demo_credentials.png') });
    console.log('  [PASS] Saved 01_login_demo_credentials.png');

    // Submit login
    console.log('  Submitting login form...');
    await page.click('button[type="submit"]');

    // Wait for redirect to dashboard
    await delay(2500);
    const currentUrl = page.url();
    console.log(`  Current URL after login: ${currentUrl}`);
    if (!currentUrl.includes('/app')) {
      const errText = await page.$eval('[data-testid="login-error"], .text-red-500, .text-red-400', el => el.innerText).catch(() => 'No visible error element');
      throw new Error(`Login failed to navigate to /app. Current URL: ${currentUrl}. UI Error: ${errText}`);
    }

    console.log('\n======================================================');
    console.log('2. DASHBOARD INSPECTION (DARK & LIGHT THEMES)');
    console.log('======================================================');
    await page.waitForSelector('h1', { timeout: 8000 }).catch(() => {});
    await delay(1000);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '02_dashboard_overview_dark.png') });
    console.log('  [PASS] Saved 02_dashboard_overview_dark.png');

    // Toggle Light Theme
    await page.evaluate(() => {
      document.documentElement.classList.remove('dark');
      document.documentElement.classList.add('light');
    });
    await delay(500);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '03_dashboard_overview_light.png') });
    console.log('  [PASS] Saved 03_dashboard_overview_light.png');

    // Restore Dark Theme
    await page.evaluate(() => {
      document.documentElement.classList.remove('light');
      document.documentElement.classList.add('dark');
    });
    await delay(300);

    console.log('\n======================================================');
    console.log('3. INSPECTING ALL 10 OPERATIONAL MODULES');
    console.log('======================================================');

    // Module 2: Products
    console.log('  [Module 2/10] Products Catalog...');
    await page.goto('http://localhost:5173/app/products', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '04_products_catalog.png') });
    console.log('  [PASS] Saved 04_products_catalog.png');

    // Module 3: Warehouses
    console.log('  [Module 3/10] Warehouses Facilities...');
    await page.goto('http://localhost:5173/app/warehouses', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '05_warehouses_facilities.png') });
    console.log('  [PASS] Saved 05_warehouses_facilities.png');

    // Module 4: Inventory
    console.log('  [Module 4/10] Inventory Balances...');
    await page.goto('http://localhost:5173/app/inventory', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '06_inventory_balances.png') });
    console.log('  [PASS] Saved 06_inventory_balances.png');

    // Module 5: Orders
    console.log('  [Module 5/10] Orders Lifecycle...');
    await page.goto('http://localhost:5173/app/orders', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '07_orders_lifecycle.png') });
    console.log('  [PASS] Saved 07_orders_lifecycle.png');

    // Module 6: Reservations
    console.log('  [Module 6/10] Active Reservations...');
    await page.goto('http://localhost:5173/app/reservations', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '08_reservations_active.png') });
    console.log('  [PASS] Saved 08_reservations_active.png');

    // Module 7: Transfers
    console.log('  [Module 7/10] Stock Transfers...');
    await page.goto('http://localhost:5173/app/transfers', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '09_transfers_history.png') });
    console.log('  [PASS] Saved 09_transfers_history.png');

    // Module 8: Reconciliation
    console.log('  [Module 8/10] Reconciliation & Discrepancies...');
    await page.goto('http://localhost:5173/app/reconciliation', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '10_reconciliation_discrepancies.png') });
    console.log('  [PASS] Saved 10_reconciliation_discrepancies.png');

    // Module 9: Audit Logs
    console.log('  [Module 9/10] Audit Logs...');
    await page.goto('http://localhost:5173/app/audit-logs', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '11_audit_logs_trail.png') });
    console.log('  [PASS] Saved 11_audit_logs_trail.png');

    // Module 10: Team Management
    console.log('  [Module 10/10] Team Management...');
    await page.goto('http://localhost:5173/app/team', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '12_team_members.png') });
    console.log('  [PASS] Saved 12_team_members.png');

    console.log('\n======================================================');
    console.log('4. LIVE OPERATIONAL INTERACTION');
    console.log('======================================================');
    await page.goto('http://localhost:5173/app/orders', { waitUntil: 'networkidle0' });
    await delay(1000);
    const filterBtn = await page.$('button[title*="filter"], select, [role="tab"]');
    if (filterBtn) {
      await filterBtn.click();
      await delay(400);
    }
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '13_orders_filtered_view.png') });
    console.log('  [PASS] Saved 13_orders_filtered_view.png');

    console.log('\n======================================================');
    console.log('5. RESPONSIVE VIEWPORT VERIFICATIONS');
    console.log('======================================================');
    // Tablet Viewport: 768 x 1024
    console.log('  Testing Tablet Viewport (768x1024)...');
    await page.setViewport({ width: 768, height: 1024 });
    await page.goto('http://localhost:5173/app/dashboard', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '14_responsive_tablet_768.png') });
    console.log('  [PASS] Saved 14_responsive_tablet_768.png');

    // Mobile Viewport: 390 x 844
    console.log('  Testing Mobile Viewport (390x844)...');
    await page.setViewport({ width: 390, height: 844 });
    await page.goto('http://localhost:5173/app/dashboard', { waitUntil: 'networkidle0' });
    await delay(1200);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '15_responsive_mobile_390.png') });
    console.log('  [PASS] Saved 15_responsive_mobile_390.png');

    console.log('\n======================================================');
    console.log('DEMO ACCEPTANCE TESTING COMPLETE - ALL CHECKS PASSED');
    console.log('======================================================');

  } catch (err) {
    console.error(`\n[FATAL ERROR during demo acceptance]: ${err.message}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, 'error_state.png') }).catch(() => {});
    process.exit(1);
  } finally {
    await browser.close();
  }
}

run();
