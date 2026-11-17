import puppeteer from 'puppeteer-core';

const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const BASE_URL = 'http://localhost:5173';
const API_URL = 'http://localhost:8080';

const results = {
  viewports: {},
  themeSwitching3D: false,
  cameraPresets: {},
  lifecycleStory: false,
  reconciliationSim: false,
  demoLogin: false,
  demoBanner: false,
  modules: {},
  mutationDefense: false,
  consoleErrors: []
};

async function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runAcceptance() {
  console.log('🚀 Starting STOCKGUARD Milestone 11A Automated Browser Acceptance Suite...');

  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--use-gl=angle',
      '--use-angle=swiftshader',
      '--window-size=1440,900'
    ]
  });

  try {
    const page = await browser.newPage();

    page.on('console', (msg) => {
      const type = msg.type();
      const text = msg.text();
      if (type === 'error' && !text.includes('favicon') && !text.includes('403')) {
        results.consoleErrors.push(text);
      }
    });

    // 1. Viewport: Desktop 1440x900 Landing Page
    console.log('\n--- 1. Testing Desktop 1440x900 ---');
    await page.setViewport({ width: 1440, height: 900 });
    await page.goto(BASE_URL, { waitUntil: 'networkidle2' });
    const pageTitle = await page.title();
    console.log('Page Title:', pageTitle);

    // Verify Title & Meta
    const hasBrandedTitle = pageTitle.includes('STOCKGUARD') && pageTitle.includes('Living Warehouse');
    console.log('Branded Title Valid:', hasBrandedTitle);

    // Check Landing Page Navbar links
    const navText = await page.$eval('nav', (el) => el.innerText);
    const hasSpatialLink = navText.includes('Spatial Twin');
    const hasCapabilitiesLink = navText.includes('Capabilities');
    const hasStorytellingLink = navText.includes('Storytelling Lab');
    const hasDemoButton = navText.includes('Demo') || navText.includes('Explore Live Demo');
    const hasRegisterButton = navText.includes('Register') || navText.includes('Create Account');
    console.log('Navbar Elements:', { hasSpatialLink, hasCapabilitiesLink, hasStorytellingLink, hasDemoButton, hasRegisterButton });
    results.viewports['1440x900'] = {
      title: hasBrandedTitle,
      navbarClean: hasDemoButton && !hasRegisterButton
    };

    // 2. 3D Warehouse Theme Integration Test
    console.log('\n--- 2. Testing 3D Warehouse Theme Integration ---');
    // Wait for 3D visualizer canvas
    await page.waitForSelector('canvas', { timeout: 10000 });
    const canvasExists = await page.$eval('canvas', (c) => !!c);
    console.log('WebGL Canvas mounted:', canvasExists);

    // Check initial dark mode state
    const initialIsDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
    console.log('Initial Theme is dark:', initialIsDark);

    // Click ThemeTray to switch theme
    const themeTrayBtn = await page.$('[data-testid="theme-tray-trigger"]');
    if (themeTrayBtn) {
      await themeTrayBtn.click();
      await sleep(300);
      // Click "Light" option
      const lightOption = await page.evaluate(() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const lightBtn = buttons.find((b) => b.textContent && b.textContent.includes('Light'));
        if (lightBtn) {
          lightBtn.click();
          return true;
        }
        return false;
      });
      console.log('Switched to Light theme:', lightOption);
      await sleep(500);

      const isLightNow = await page.evaluate(() => !document.documentElement.classList.contains('dark'));
      console.log('DOM updated to Light:', isLightNow);

      // Verify canvas is still mounted and alive without page reload
      const canvasStillMounted = await page.$eval('canvas', (c) => !!c);

      // Switch back to Dark
      await themeTrayBtn.click();
      await sleep(300);
      const darkOption = await page.evaluate(() => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const darkBtn = buttons.find((b) => b.textContent && b.textContent.includes('Dark'));
        if (darkBtn) {
          darkBtn.click();
          return true;
        }
        return false;
      });
      console.log('Switched back to Dark theme:', darkOption);
      await sleep(500);

      const isDarkAgain = await page.evaluate(() => document.documentElement.classList.contains('dark'));
      console.log('DOM restored to Dark:', isDarkAgain);

      results.themeSwitching3D = canvasExists && isLightNow && canvasStillMounted && isDarkAgain;
    }

    // 3. Camera Presets
    console.log('\n--- 3. Testing Camera Presets ---');
    const presets = ['Isometric', 'Plan View', 'Aisle Cam', 'Dock 01'];
    for (const preset of presets) {
      const clicked = await page.evaluate((name) => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const btn = buttons.find((b) => b.textContent && b.textContent.trim() === name);
        if (btn) {
          btn.click();
          return true;
        }
        return false;
      }, preset);
      await sleep(300);
      results.cameraPresets[preset] = clicked;
      console.log(`Camera Preset [${preset}] clicked:`, clicked);
    }

    // 4. Lifecycle Storytelling Cards
    console.log('\n--- 4. Testing Lifecycle Storytelling ---');
    const storyCardClicks = await page.evaluate(() => {
      const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
      let successCount = 0;
      for (const tab of tabs) {
        tab.click();
        successCount++;
      }
      return successCount;
    });
    console.log('Lifecycle Story Cards clicked:', storyCardClicks);
    results.lifecycleStory = storyCardClicks >= 6;

    // 4b. Interactive Simulator
    console.log('\n--- 4b. Testing Interactive Simulator ---');
    await page.evaluate(() => {
      const section = document.getElementById('storytelling');
      if (section) section.scrollIntoView();
    });
    await sleep(500);

    const simPassed = await page.evaluate(async () => {
      const buttons = Array.from(document.querySelectorAll('button'));
      const btn1 = buttons.find((b) => b.textContent && b.textContent.includes('Inject Discrepancy'));
      if (!btn1) return 'btn1_missing';
      btn1.click();

      await new Promise((r) => setTimeout(r, 500));
      const buttons2 = Array.from(document.querySelectorAll('button'));
      const btn2 = buttons2.find((b) => b.textContent && b.textContent.includes('Trigger MVCC Reconciliation'));
      if (!btn2 || btn2.disabled) return 'btn2_missing_or_disabled';
      btn2.click();

      // handleRunReconciliation has a 600ms internal timeout
      await new Promise((r) => setTimeout(r, 1200));
      const buttons3 = Array.from(document.querySelectorAll('button'));
      const btn3 = buttons3.find((b) => b.textContent && b.textContent.includes('Authorize Human Resolution'));
      if (!btn3 || btn3.disabled) return 'btn3_missing_or_disabled';
      btn3.click();

      await new Promise((r) => setTimeout(r, 500));
      const buttons4 = Array.from(document.querySelectorAll('button'));
      const resetBtn = buttons4.find((b) => b.textContent && b.textContent.includes('Reset Simulator'));
      if (resetBtn) resetBtn.click();
      return 'ok';
    });
    console.log('Interactive Simulator result:', simPassed);
    results.reconciliationSim = (simPassed === 'ok');

    // 5. Test Responsive Viewports (1366x768, 768x1024, 390x844)
    console.log('\n--- 5. Testing Viewports ---');
    // Laptop 1366x768
    await page.setViewport({ width: 1366, height: 768 });
    await sleep(400);
    const laptopCanvas = await page.$eval('canvas', (c) => !!c);
    results.viewports['1366x768'] = { canvasRendered: laptopCanvas };

    // Tablet 768x1024
    await page.setViewport({ width: 768, height: 1024 });
    await sleep(400);
    const tabletCanvas = await page.$eval('canvas', (c) => !!c);
    results.viewports['768x1024'] = { canvasRendered: tabletCanvas };

    // Mobile 390x844
    await page.setViewport({ width: 390, height: 844 });
    await sleep(400);
    const mobileMenuBtn = await page.$('button[aria-label="Open navigation menu"]');
    let mobileDrawerSuccess = false;
    if (mobileMenuBtn) {
      await mobileMenuBtn.click();
      await sleep(300);
      const drawerVisible = await page.evaluate(() => !!document.querySelector('[role="dialog"]'));
      console.log('Mobile Drawer Opened:', drawerVisible);

      // Close drawer
      const closeBtn = await page.$('button[aria-label="Close navigation menu"]');
      if (closeBtn) await closeBtn.click();
      await sleep(300);
      const drawerClosed = await page.evaluate(() => !document.querySelector('[role="dialog"]'));
      console.log('Mobile Drawer Closed:', drawerClosed);
      mobileDrawerSuccess = drawerVisible && drawerClosed;
    }
    results.viewports['390x844'] = {
      mobileDrawer: mobileDrawerSuccess
    };

    // Restore Desktop Viewport for App Testing
    await page.setViewport({ width: 1440, height: 900 });

    // 6. Public Demo Experience Journey
    console.log('\n--- 6. Testing Public Demo Login Flow ---');
    let demoAuthToken = null;
    let demoOrgId = null;
    page.on('response', async (res) => {
      if (res.url().includes('/api/auth/demo-login') && res.status() === 200) {
        try {
          const json = await res.json();
          demoAuthToken = json.accessToken || json.token;
          const orgs = json.availableOrganizations || json.organizations || [];
          if (orgs.length > 0) {
            demoOrgId = orgs[0].id;
          }
        } catch {}
      }
    });

    await page.goto(`${BASE_URL}/login?demo=true`, { waitUntil: 'networkidle2' });
    await page.waitForNavigation({ timeout: 15000 }).catch(() => {});
    await sleep(2000);

    const currentUrl = page.url();
    console.log('Post-Demo-Login URL:', currentUrl);
    results.demoLogin = currentUrl.includes('/app/dashboard');

    // 7. Verify AppShell Demo Banner & VIEWER Role
    console.log('\n--- 7. Testing AppShell Demo Banner ---');
    const demoBannerExists = await page.evaluate(() => {
      const banner = document.querySelector('[data-testid="demo-visitor-banner"]');
      if (!banner) return false;
      const text = banner.textContent || '';
      return text.includes('Public Demo Mode (Read-Only)') && text.includes('VIEWER ROLE');
    });
    console.log('Demo Banner Valid:', demoBannerExists);
    results.demoBanner = demoBannerExists;

    // 8. Test All 10 Dashboard Operations Modules
    console.log('\n--- 8. Testing All 10 Operations Modules ---');
    const modules = [
      { name: 'Dashboard', path: '/app/dashboard', check: () => true },
      {
        name: 'Products',
        path: '/app/products',
        check: async () => {
          // Check that 30 products table exists and "Create Product" is absent
          const hasCreate = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('button'));
            return btns.some((b) => b.textContent && b.textContent.includes('Create Product'));
          });
          const rowCount = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
          return !hasCreate && rowCount > 0;
        }
      },
      {
        name: 'Warehouses',
        path: '/app/warehouses',
        check: async () => {
          const hasCreate = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('button'));
            return btns.some((b) => b.textContent && b.textContent.includes('Create Facility'));
          });
          const rowCount = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
          return !hasCreate && rowCount >= 3;
        }
      },
      {
        name: 'Inventory',
        path: '/app/inventory',
        check: async () => {
          const hasReceive = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('button'));
            return btns.some((b) => b.textContent && (b.textContent.includes('Receive Stock') || b.textContent.includes('Opening Balance') || b.textContent.includes('Stock Adjustment')));
          });
          const rowCount = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
          return !hasReceive && rowCount > 0;
        }
      },
      {
        name: 'Orders',
        path: '/app/orders',
        check: async () => {
          const hasCreate = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('button'));
            return btns.some((b) => b.textContent && (b.textContent.includes('Create Order') || b.textContent.includes('Reserve') || b.textContent.includes('Confirm')));
          });
          const rowCount = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
          return !hasCreate && rowCount > 0;
        }
      },
      {
        name: 'Reservations',
        path: '/app/reservations',
        check: async () => {
          const hasSearchInput = await page.evaluate(() => !!document.querySelector('input'));
          return hasSearchInput;
        }
      },
      {
        name: 'Transfers',
        path: '/app/transfers',
        check: async () => {
          const hasExecute = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('button'));
            return btns.some((b) => b.textContent && b.textContent.includes('Execute Transfer'));
          });
          return !hasExecute;
        }
      },
      {
        name: 'Reconciliation',
        path: '/app/reconciliation',
        check: async () => {
          const hasTriggerSweep = await page.evaluate(() => !!document.querySelector('[data-testid="trigger-sweep-btn"]'));
          const noticeExists = await page.evaluate(() => {
            return document.body.textContent.includes('Operational Verification Context');
          });
          return !hasTriggerSweep && noticeExists;
        }
      },
      {
        name: 'Audit Logs',
        path: '/app/audit-logs',
        check: async () => {
          const hasTable = await page.evaluate(() => !!document.querySelector('table'));
          const rowCount = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
          return hasTable && rowCount > 0;
        }
      },
      {
        name: 'Team',
        path: '/app/team',
        check: async () => {
          const hasAddMember = await page.evaluate(() => {
            const btns = Array.from(document.querySelectorAll('button'));
            return btns.some((b) => b.textContent && b.textContent.includes('Add Member'));
          });
          const rowCount = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
          return !hasAddMember && rowCount >= 8;
        }
      }
    ];

    for (const mod of modules) {
      await page.goto(`${BASE_URL}${mod.path}`, { waitUntil: 'networkidle2' });
      await sleep(1000);
      const passed = await mod.check();
      results.modules[mod.name] = passed;
      console.log(`Module [${mod.name}]:`, passed ? 'PASSED (Read-only verified)' : 'FAILED');
    }

    // 9. Server-Side Mutation Protection Verification
    console.log('\n--- 9. Testing Server-side Mutation Rejection ---');
    console.log('Demo Auth Token present:', !!demoAuthToken);
    console.log('Demo Org ID:', demoOrgId);

    const mutationResponseStatus = await page.evaluate(async (api, token, orgId) => {
      try {
        const res = await fetch(`${api}/api/products`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
            'X-Organization-Id': orgId,
            'X-StockGuard-CSRF': '1'
          },
          body: JSON.stringify({
            sku: 'HACK-999',
            name: 'Malicious Product'
          })
        });
        return res.status;
      } catch {
        return 0;
      }
    }, API_URL, demoAuthToken, demoOrgId);

    console.log('Direct POST /api/products response code:', mutationResponseStatus);
    results.mutationDefense = (mutationResponseStatus === 403);

    console.log('\n=== ACCEPTANCE TEST RESULTS SUMMARY ===');
    console.log(JSON.stringify(results, null, 2));

  } catch (err) {
    console.error('Acceptance suite encountered an error:', err);
  } finally {
    await browser.close();
  }
}

runAcceptance();
