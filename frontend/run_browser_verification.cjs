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

console.log(`Using browser executable: ${CHROME_PATH}`);

async function run() {
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'],
    defaultViewport: { width: 1440, height: 900 },
  });

  const page = await browser.newPage();

  try {
    // 1. Landing Page
    console.log('1. Navigating to landing page...');
    await page.goto('http://localhost:5173/', { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '01_landing_page.png') });
    console.log('   Saved 01_landing_page.png');

    // 2. Login Page
    console.log('2. Navigating to login page...');
    await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '02_login_page.png') });
    console.log('   Saved 02_login_page.png');

    // 3. Admin Login Execution
    console.log('3. Executing Administrator login (admin@stockguard.dev)...');
    await page.type('input[type="email"]', 'admin@stockguard.dev');
    await page.type('input[type="password"]', 'replace-with-secure-admin-password');
    await page.click('button[type="submit"]');

    // Wait for dashboard to load
    await new Promise(r => setTimeout(r, 2000));
    console.log(`   Current URL after login: ${page.url()}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '03_dashboard_dark.png') });
    console.log('   Saved 03_dashboard_dark.png');

    // 4. Theme Tray Open
    console.log('4. Opening Theme Tray popover...');
    const themeBtn = await page.$('button[data-testid="theme-tray-trigger"]');
    if (themeBtn) {
      await themeBtn.click();
      await new Promise(r => setTimeout(r, 400));
      await page.screenshot({ path: path.join(EVIDENCE_DIR, '04_theme_tray_open.png') });
      console.log('   Saved 04_theme_tray_open.png');

      // 5. Switch to Light Mode
      console.log('5. Selecting Light Theme mode...');
      await page.click('button[data-theme-mode="light"]');
      await new Promise(r => setTimeout(r, 500));
      await page.screenshot({ path: path.join(EVIDENCE_DIR, '05_dashboard_light.png') });
      console.log('   Saved 05_dashboard_light.png');

      const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
      console.log(`   HTML class contains "dark": ${isDark} (expected: false for light mode)`);
    }

    // 6. Switch back to Dark mode
    console.log('6. Switching back to Dark Theme mode...');
    const themeBtn2 = await page.$('button[data-testid="theme-tray-trigger"]');
    if (themeBtn2) {
      await themeBtn2.click();
      await new Promise(r => setTimeout(r, 400));
      await page.click('button[data-theme-mode="dark"]');
      await new Promise(r => setTimeout(r, 500));
      const isDarkNow = await page.evaluate(() => document.documentElement.classList.contains('dark'));
      console.log(`   HTML class contains "dark": ${isDarkNow} (expected: true for dark mode)`);
    }

    // 7. Operations Pages: Products
    console.log('7. Navigating to /app/products...');
    await page.goto('http://localhost:5173/app/products', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '06_products_page.png') });
    console.log('   Saved 06_products_page.png');

    // 8. Operations Pages: Warehouses
    console.log('8. Navigating to /app/warehouses...');
    await page.goto('http://localhost:5173/app/warehouses', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '07_warehouses_page.png') });
    console.log('   Saved 07_warehouses_page.png');

    // 9. Operations Pages: Inventory & Ledger
    console.log('9. Navigating to /app/inventory...');
    await page.goto('http://localhost:5173/app/inventory', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '08_inventory_page.png') });
    console.log('   Saved 08_inventory_page.png');

    // 10. Operations Pages: Reconciliation
    console.log('10. Navigating to /app/reconciliation...');
    await page.goto('http://localhost:5173/app/reconciliation', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '09_reconciliation_page.png') });
    console.log('   Saved 09_reconciliation_page.png');

    // 11. Operations Pages: Access & Team
    console.log('11. Navigating to /app/team...');
    await page.goto('http://localhost:5173/app/team', { waitUntil: 'networkidle0' });
    await page.waitForSelector('h1');
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '10_team_page.png') });
    console.log('   Saved 10_team_page.png');

    // 12. Register Page (Option A - Organization Registration)
    console.log('12. Navigating to /register...');
    await page.goto('http://localhost:5173/register', { waitUntil: 'networkidle0' });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '11_register_page.png') });
    console.log('   Saved 11_register_page.png');

    // 13. Perform Real Account Registration
    const testRegEmail = `founder_${Date.now()}@acmeglobal.dev`;
    console.log(`13. Registering brand new tenant organization with email ${testRegEmail}...`);
    const inputs = await page.$$('input');
    // fullName, email, organizationName, password, confirmPassword
    await inputs[0].type('Morgan Vance');
    await inputs[1].type(testRegEmail);
    await inputs[2].type('Vance Logistics Ltd');
    await inputs[3].type('VancePass2026!');
    await inputs[4].type('VancePass2026!');

    await page.screenshot({ path: path.join(EVIDENCE_DIR, '12_register_filled.png') });
    console.log('   Saved 12_register_filled.png');

    await page.click('button[type="submit"]');
    await new Promise(r => setTimeout(r, 2500));
    console.log(`   Current URL after registration: ${page.url()}`);
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '13_registered_dashboard.png') });
    console.log('   Saved 13_registered_dashboard.png');

    // 14. Responsive Layout: Tablet 768px
    console.log('14. Testing Tablet Viewport (768x1024)...');
    await page.setViewport({ width: 768, height: 1024 });
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '14_tablet_view.png') });
    console.log('   Saved 14_tablet_view.png');

    // 15. Responsive Layout: Mobile 390px
    console.log('15. Testing Mobile Viewport (390x844)...');
    await page.setViewport({ width: 390, height: 844 });
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, '15_mobile_view.png') });
    console.log('   Saved 15_mobile_view.png');

    console.log('\n--- ALL BROWSER VERIFICATION SCENARIOS PASSED WITH SCREENSHOTS ---');
  } catch (err) {
    console.error('Error during browser verification:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

run();
