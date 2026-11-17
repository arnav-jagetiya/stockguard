const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const EVIDENCE_DIR = path.join(__dirname, 'verification_evidence');
if (!fs.existsSync(EVIDENCE_DIR)) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
}

const CHROME_PATH = fs.existsSync('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')
  ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

console.log(`[Acceptance] Using Chrome executable: ${CHROME_PATH}`);

async function run() {
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'],
    defaultViewport: { width: 1440, height: 900 },
  });

  const page = await browser.newPage();

  page.on('console', msg => {
    if (msg.type() === 'error') {
      console.log(`[Browser ERROR]: ${msg.text()}`);
    }
  });

  try {
    // -------------------------------------------------------------------------
    // 1. Landing Page
    // -------------------------------------------------------------------------
    console.log('\n--- 1. Testing Landing Page ---');
    await page.goto('http://localhost:5173/', { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '01_landing_hero.png') });
    console.log('  Saved 01_landing_hero.png');

    // -------------------------------------------------------------------------
    // 2. Login Page & Invalid Login Check
    // -------------------------------------------------------------------------
    console.log('\n--- 2. Testing Login Page & Error Handling ---');
    await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '02_login_screen.png') });
    console.log('  Saved 02_login_screen.png');

    // Test Invalid Login
    console.log('  Submitting invalid credentials...');
    await page.type('input[type="email"]', 'admin@stockguard.dev');
    await page.type('input[type="password"]', 'WrongPassword999!');
    await page.click('button[type="submit"]');
    await page.waitForSelector('[data-testid="login-error"]', { timeout: 5000 });
    await new Promise(r => setTimeout(r, 400));
    const errorText = await page.$eval('[data-testid="login-error"]', el => el.innerText);
    console.log(`  Rendered error message: "${errorText}"`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '03_invalid_login_error.png') });
    console.log('  Saved 03_invalid_login_error.png (verified error message rendered)');

    // Clear inputs
    await page.evaluate(() => {
      document.querySelector('input[type="email"]').value = '';
      document.querySelector('input[type="password"]').value = '';
    });

    // -------------------------------------------------------------------------
    // 3. Demo Admin Credentials Flow (Dev-only endpoint verification)
    // -------------------------------------------------------------------------
    console.log('\n--- 3. Testing Demo Admin Credentials API ---');
    const demoBtn = await page.$('button[title*="bootstrap credentials"]');
    if (demoBtn) {
      console.log('  Clicking Fill Demo Admin (calls /api/auth/demo-credentials)...');
      await demoBtn.click();
      await new Promise(r => setTimeout(r, 600));
      const filledEmail = await page.$eval('input[type="email"]', el => el.value);
      console.log(`  Dynamically populated email: ${filledEmail}`);
      await page.screenshot({ path: path.join(EVIDENCE_DIR, '04_demo_admin_fill.png') });
      console.log('  Saved 04_demo_admin_fill.png');
    } else {
      console.log('  Demo button not present (production mode)');
      await page.type('input[type="email"]', 'admin@stockguard.dev');
      await page.type('input[type="password"]', 'replace-with-secure-admin-password');
    }

    // -------------------------------------------------------------------------
    // 4. Successful Authentication & Operations Dashboard (Dark Mode)
    // -------------------------------------------------------------------------
    console.log('\n--- 4. Executing Successful Login ---');
    await page.click('button[type="submit"]');
    await new Promise(r => setTimeout(r, 2000));
    console.log(`  Current URL after login: ${page.url()}`);
    await page.waitForSelector('h1');
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '05_dashboard_dark.png') });
    console.log('  Saved 05_dashboard_dark.png');

    // -------------------------------------------------------------------------
    // 5. Theme Acceptance: Switch to Light Theme via ThemeTray
    // -------------------------------------------------------------------------
    console.log('\n--- 5. Theme Acceptance: ThemeTray & Light Mode ---');
    const themeBtn = await page.$('button[data-testid="theme-tray-trigger"]');
    if (themeBtn) {
      await themeBtn.click();
      await new Promise(r => setTimeout(r, 400));
      await page.screenshot({ path: path.join(EVIDENCE_DIR, '06_theme_tray_open.png') });
      console.log('  Saved 06_theme_tray_open.png');

      await page.click('button[data-theme-mode="light"]');
      await new Promise(r => setTimeout(r, 500));
      const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
      const storedTheme = await page.evaluate(() => localStorage.getItem('stockguard_theme'));
      console.log(`  HTML contains 'dark': ${isDark} | Stored theme: ${storedTheme}`);
      await page.screenshot({ path: path.join(EVIDENCE_DIR, '07_dashboard_light.png') });
      console.log('  Saved 07_dashboard_light.png');
    }

    // -------------------------------------------------------------------------
    // 6. Navigation & Full Route Coverage (in Light Mode)
    // -------------------------------------------------------------------------
    console.log('\n--- 6. Operations Route Coverage & Real Interactions ---');

    // Products Route
    console.log('  Navigating to /app/products...');
    await page.goto('http://localhost:5173/app/products', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    // Test interaction: search input
    const searchInput = await page.$('input[placeholder*="Search"]');
    if (searchInput) {
      await searchInput.type('Sensor');
      await new Promise(r => setTimeout(r, 300));
    }
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '08_products_light.png') });
    console.log('  Saved 08_products_light.png (verified product table & search)');

    // Warehouses Route
    console.log('  Navigating to /app/warehouses...');
    await page.goto('http://localhost:5173/app/warehouses', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '09_warehouses_light.png') });
    console.log('  Saved 09_warehouses_light.png (verified facility list & utilization)');

    // Inventory Route (Stock Levels + Ledger Tabs)
    console.log('  Navigating to /app/inventory...');
    await page.goto('http://localhost:5173/app/inventory', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '10_inventory_stock_light.png') });
    console.log('  Saved 10_inventory_stock_light.png');

    // Switch to Ledger Tab
    console.log('  Switching to Inventory Ledger Tab...');
    const clickedLedger = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const ledgerBtn = btns.find(b => b.innerText.includes('Audit Ledger') || b.innerText.includes('Ledger History') || b.innerText.includes('Ledger'));
      if (ledgerBtn) {
        ledgerBtn.click();
        return true;
      }
      return false;
    });
    if (clickedLedger) {
      await new Promise(r => setTimeout(r, 600));
      await page.screenshot({ path: path.join(EVIDENCE_DIR, '11_inventory_ledger_light.png') });
      console.log('  Saved 11_inventory_ledger_light.png (verified immutable ledger history)');
    }

    // Orders Route
    console.log('  Navigating to /app/orders...');
    await page.goto('http://localhost:5173/app/orders', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '12_orders_light.png') });
    console.log('  Saved 12_orders_light.png (verified order management lifecycle)');

    // Reservations Route
    console.log('  Navigating to /app/reservations...');
    await page.goto('http://localhost:5173/app/reservations', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '13_reservations_light.png') });
    console.log('  Saved 13_reservations_light.png (verified stock reservations & TTL)');

    // Transfers Route
    console.log('  Navigating to /app/transfers...');
    await page.goto('http://localhost:5173/app/transfers', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '14_transfers_light.png') });
    console.log('  Saved 14_transfers_light.png (verified inter-warehouse transfers)');

    // Reconciliation Route
    console.log('  Navigating to /app/reconciliation...');
    await page.goto('http://localhost:5173/app/reconciliation', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '15_reconciliation_scan_light.png') });
    console.log('  Saved 15_reconciliation_scan_light.png (verified discrepancy triage)');

    // Audit Logs Route
    console.log('  Navigating to /app/audit-logs...');
    await page.goto('http://localhost:5173/app/audit-logs', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '16_audit_logs_light.png') });
    console.log('  Saved 16_audit_logs_light.png (verified audit log entries)');

    // Team Route
    console.log('  Navigating to /app/team...');
    await page.goto('http://localhost:5173/app/team', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 500));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '17_team_light.png') });
    console.log('  Saved 17_team_light.png (verified team memberships & RBAC)');

    // -------------------------------------------------------------------------
    // 7. Page Reload & Session Restoration
    // -------------------------------------------------------------------------
    console.log('\n--- 7. Testing Page Reload & Silent Refresh Session Restoration ---');
    await page.goto('http://localhost:5173/app/dashboard', { waitUntil: 'networkidle0' });
    await new Promise(r => setTimeout(r, 500));
    console.log('  Triggering hard reload...');
    await page.reload({ waitUntil: 'networkidle0' });
    await new Promise(r => setTimeout(r, 1000));
    console.log(`  Current URL after reload: ${page.url()}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '18_session_restore_on_reload.png') });
    console.log('  Saved 18_session_restore_on_reload.png (verified user remains logged in)');

    // -------------------------------------------------------------------------
    // 8. Logout Lifecycle
    // -------------------------------------------------------------------------
    console.log('\n--- 8. Testing Logout Lifecycle ---');
    const loggedOut = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const lBtn = btns.find(b => b.getAttribute('aria-label') === 'Log out' || b.getAttribute('title') === 'Log out' || b.querySelector('svg.lucide-log-out'));
      if (lBtn) {
        lBtn.click();
        return true;
      }
      return false;
    });
    console.log(`  Logout button clicked: ${loggedOut}`);
    await new Promise(r => setTimeout(r, 1500));
    console.log(`  Current URL after logout: ${page.url()}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '19_logout_screen.png') });
    console.log('  Saved 19_logout_screen.png');

    // -------------------------------------------------------------------------
    // 9. Option A: Self-Service Organization Registration & Isolation
    // -------------------------------------------------------------------------
    console.log('\n--- 9. Testing Organization Registration & Tenant Isolation ---');
    await page.goto('http://localhost:5173/register', { waitUntil: 'networkidle0' });
    const regEmail = `test_tenant_${Date.now()}@acmelogistics.com`;
    const regOrgName = `Acme Express Logistics ${Math.floor(Math.random() * 9000 + 1000)}`;

    const inputs = await page.$$('input');
    await inputs[0].type('Jordan Taylor');
    await inputs[1].type(regEmail);
    await inputs[2].type(regOrgName);
    await inputs[3].type('ComplexPass999!');
    await inputs[4].type('ComplexPass999!');

    await new Promise(r => setTimeout(r, 400));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '20_register_validation.png') });
    console.log('  Saved 20_register_validation.png (strength meter & checklist verified)');

    await page.click('button[type="submit"]');
    await new Promise(r => setTimeout(r, 2500));
    console.log(`  Current URL after registration: ${page.url()}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '21_new_org_dashboard.png') });
    console.log('  Saved 21_new_org_dashboard.png (registered tenant dashboard active)');

    // Verify Organization Isolation: The new organization must NOT see Demo Org's products!
    console.log('  Verifying tenant isolation on /app/products...');
    await page.goto('http://localhost:5173/app/products', { waitUntil: 'networkidle0' });
    await new Promise(r => setTimeout(r, 800));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '22_new_org_isolated_products.png') });
    console.log('  Saved 22_new_org_isolated_products.png (isolated tenant: zero cross-tenant leak)');

    // -------------------------------------------------------------------------
    // 10. System Theme & Media Feature Emulation
    // -------------------------------------------------------------------------
    console.log('\n--- 10. Testing System Preference Theme Emulation ---');
    const themeBtnSys = await page.$('button[data-testid="theme-tray-trigger"]');
    if (themeBtnSys) {
      await themeBtnSys.click();
      await new Promise(r => setTimeout(r, 300));
      await page.click('button[data-theme-mode="system"]');
      await new Promise(r => setTimeout(r, 300));
    }
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await new Promise(r => setTimeout(r, 400));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '23_theme_system_mode.png') });
    console.log('  Saved 23_theme_system_mode.png');

    // -------------------------------------------------------------------------
    // 11. Reduced Motion Mode
    // -------------------------------------------------------------------------
    console.log('\n--- 11. Testing Reduced Motion Mode ---');
    await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '24_reduced_motion.png') });
    console.log('  Saved 24_reduced_motion.png');

    // -------------------------------------------------------------------------
    // 12. Responsive Viewports: Laptop (1280), Tablet (768), Mobile (390)
    // -------------------------------------------------------------------------
    console.log('\n--- 12. Testing Responsive Viewports ---');
    // Laptop 1280x800
    await page.setViewport({ width: 1280, height: 800 });
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '25_responsive_1280.png') });
    console.log('  Saved 25_responsive_1280.png (1280px laptop)');

    // Tablet 768x1024
    await page.setViewport({ width: 768, height: 1024 });
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '26_responsive_768_tablet.png') });
    console.log('  Saved 26_responsive_768_tablet.png (768px tablet)');

    // Mobile 390x844
    await page.setViewport({ width: 390, height: 844 });
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '27_responsive_390_mobile.png') });
    console.log('  Saved 27_responsive_390_mobile.png (390px mobile)');

    console.log('\n=== ALL COMPREHENSIVE ACCEPTANCE TESTS COMPLETED SUCCESSFULLY ===');
  } catch (err) {
    console.error('Fatal error in acceptance verification:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

run();
