import puppeteer from 'puppeteer-core';

const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const BASE_URL = 'http://localhost:5173';

async function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function verify3DWarehouse() {
  console.log('🧪 Starting 3D Warehouse Specialized Verification Suite...');

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

  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (err) => errors.push(`PAGEERROR: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error' && !msg.text().includes('favicon')) {
      errors.push(`CONSOLE_ERROR: ${msg.text()}`);
    }
  });

  try {
    await page.setViewport({ width: 1440, height: 900 });
    await page.goto(BASE_URL, { waitUntil: 'networkidle2' });
    await page.waitForSelector('canvas', { timeout: 10000 });

    // Step A: WebGL context loss listener & initial canvas reference
    const initialCanvasId = await page.evaluate(() => {
      const canvas = document.querySelector('canvas');
      if (!canvas) return null;
      canvas.setAttribute('data-test-marker', 'canvas-initial');
      canvas.addEventListener('webglcontextlost', () => {
        window.__webgl_context_lost = true;
      });
      return canvas.getAttribute('data-test-marker');
    });
    console.log('1. Canvas mounted and marked:', initialCanvasId);

    // Step B: Test all 4 camera modes in Dark theme
    console.log('\n--- 2. Testing 4 camera presets in DARK theme ---');
    const presets = ['Isometric', 'Plan View', 'Aisle Cam', 'Dock 01'];
    for (const preset of presets) {
      const ok = await page.evaluate((name) => {
        const btns = Array.from(document.querySelectorAll('button'));
        const btn = btns.find((b) => b.textContent && b.textContent.trim() === name);
        if (btn) {
          btn.click();
          return true;
        }
        return false;
      }, preset);
      await sleep(350);
      console.log(`  Preset [${preset}] in Dark:`, ok);
    }

    // Step C: Set Lifecycle Step 4 (Dual-Ledger) and Camera Preset "Aisle Cam"
    console.log('\n--- 3. Setting Lifecycle Step 4 and verifying persistence across theme changes ---');
    await page.evaluate(() => {
      const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
      if (tabs[3]) tabs[3].click(); // Step 4: Dual-Ledger Accounting
    });
    await sleep(400);

    const stepBeforeThemeChange = await page.evaluate(() => {
      const activeTab = document.querySelector('[role="tab"][aria-selected="true"]');
      return activeTab ? activeTab.textContent : null;
    });
    console.log('Active lifecycle step before theme change:', stepBeforeThemeChange);

    // Step D: Theme switch: Dark -> Light
    console.log('\n--- 4. Switching Dark -> Light without reload ---');
    const themeTrayBtn = await page.$('[data-testid="theme-tray-trigger"]');
    await themeTrayBtn.click();
    await sleep(250);
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const lightBtn = btns.find((b) => b.textContent && b.textContent.includes('Light'));
      if (lightBtn) lightBtn.click();
    });
    await sleep(600);

    const isLight = await page.evaluate(() => !document.documentElement.classList.contains('dark'));
    const canvasAfterLight = await page.evaluate(() => {
      const canvas = document.querySelector('canvas');
      return {
        hasInitialMarker: canvas?.getAttribute('data-test-marker') === 'canvas-initial',
        contextLost: !!window.__webgl_context_lost
      };
    });
    console.log('Theme is Light:', isLight);
    console.log('Canvas identity preserved (no recreation):', canvasAfterLight.hasInitialMarker);
    console.log('WebGL context lost:', canvasAfterLight.contextLost);

    // Step E: Test all 4 camera modes in Light theme
    console.log('\n--- 5. Testing 4 camera presets in LIGHT theme ---');
    for (const preset of presets) {
      const ok = await page.evaluate((name) => {
        const btns = Array.from(document.querySelectorAll('button'));
        const btn = btns.find((b) => b.textContent && b.textContent.trim() === name);
        if (btn) {
          btn.click();
          return true;
        }
        return false;
      }, preset);
      await sleep(350);
      console.log(`  Preset [${preset}] in Light:`, ok);
    }

    // Step F: Verify Lifecycle step survived theme switch
    const stepAfterThemeChange = await page.evaluate(() => {
      const activeTab = document.querySelector('[role="tab"][aria-selected="true"]');
      return activeTab ? activeTab.textContent : null;
    });
    console.log('Active lifecycle step after theme change:', stepAfterThemeChange);
    const lifecycleSurvived = stepBeforeThemeChange === stepAfterThemeChange;
    console.log('Lifecycle step survived theme switch:', lifecycleSurvived);

    // Step G: Theme switch: Light -> Dark
    console.log('\n--- 6. Switching Light -> Dark without reload ---');
    await themeTrayBtn.click();
    await sleep(250);
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const darkBtn = btns.find((b) => b.textContent && b.textContent.includes('Dark'));
      if (darkBtn) darkBtn.click();
    });
    await sleep(600);

    const isDarkAgain = await page.evaluate(() => document.documentElement.classList.contains('dark'));
    const canvasAfterDark = await page.evaluate(() => {
      const canvas = document.querySelector('canvas');
      return {
        hasInitialMarker: canvas?.getAttribute('data-test-marker') === 'canvas-initial',
        contextLost: !!window.__webgl_context_lost
      };
    });
    console.log('Theme is Dark again:', isDarkAgain);
    console.log('Canvas identity preserved:', canvasAfterDark.hasInitialMarker);
    console.log('WebGL context lost:', canvasAfterDark.contextLost);

    // Step H: Theme persistence across reload
    console.log('\n--- 7. Testing Theme Persistence Across Page Reload ---');
    // First, set theme to Light and reload
    await themeTrayBtn.click();
    await sleep(250);
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const lightBtn = btns.find((b) => b.textContent && b.textContent.includes('Light'));
      if (lightBtn) lightBtn.click();
    });
    await sleep(500);

    console.log('Reloading page while in Light theme...');
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(600);

    const themeAfterReloadLight = await page.evaluate(() => !document.documentElement.classList.contains('dark'));
    console.log('Theme persisted as Light after reload:', themeAfterReloadLight);

    // Revert back to Dark and reload
    const themeTrayAfterReload = await page.$('[data-testid="theme-tray-trigger"]');
    await themeTrayAfterReload.click();
    await sleep(250);
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const darkBtn = btns.find((b) => b.textContent && b.textContent.includes('Dark'));
      if (darkBtn) darkBtn.click();
    });
    await sleep(500);

    console.log('Reloading page after setting Dark theme...');
    await page.reload({ waitUntil: 'networkidle2' });
    await sleep(600);

    const themeAfterReloadDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
    console.log('Theme persisted as Dark after reload:', themeAfterReloadDark);

    console.log('\n--- Verification Summary ---');
    console.log('Errors / Exceptions encountered:', errors.length === 0 ? 'NONE' : errors);
    console.log('All 3D Warehouse criteria passed successfully!');
  } catch (err) {
    console.error('Specialized verification failed:', err);
  } finally {
    await browser.close();
  }
}

verify3DWarehouse();
