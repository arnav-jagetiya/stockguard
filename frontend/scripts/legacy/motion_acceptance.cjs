const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const MOTION_DIR = path.join(__dirname, 'verification_evidence', 'motion_acceptance');
if (!fs.existsSync(MOTION_DIR)) {
  fs.mkdirSync(MOTION_DIR, { recursive: true });
}

const CHROME_PATH = fs.existsSync('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')
  ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

async function runMotionAcceptance() {
  console.log('[Motion Acceptance] Starting motion and transition verification...');
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu'],
    defaultViewport: { width: 1440, height: 900 },
  });

  const page = await browser.newPage();

  try {
    // 1. Authenticate
    console.log('1. Authenticating as administrator...');
    await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('input[type="email"]');
    await page.type('input[type="email"]', 'admin@stockguard.dev');
    await page.type('input[type="password"]', 'replace-with-secure-admin-password');
    await page.click('button[type="submit"]');
    await page.waitForSelector('button[data-testid="theme-tray-trigger"]', { timeout: 10000 });
    await new Promise(r => setTimeout(r, 1000));
    console.log('   Authenticated and dashboard ready.');

    // 2. Theme Tray Motion & Popover Animation
    console.log('2. Testing Theme Tray Popover Animation...');
    await page.waitForSelector('button[data-testid="theme-tray-trigger"]');
    await new Promise(r => setTimeout(r, 400));
    await page.screenshot({ path: path.join(MOTION_DIR, '01_theme_tray_closed.png') });
    await page.click('button[data-testid="theme-tray-trigger"]');
    await new Promise(r => setTimeout(r, 80)); // Mid-opening zoom-in-95
    await page.screenshot({ path: path.join(MOTION_DIR, '02_theme_tray_animating_in.png') });
    await new Promise(r => setTimeout(r, 200)); // Fully opened
    await page.screenshot({ path: path.join(MOTION_DIR, '03_theme_tray_open.png') });
    // Switch to dark mode
    await page.waitForSelector('button[data-theme-mode="dark"]');
    await page.click('button[data-theme-mode="dark"]');
    await new Promise(r => setTimeout(r, 300));
    await page.screenshot({ path: path.join(MOTION_DIR, '04_theme_transition_dark.png') });

    // 3. Modal / Dialog Motion (Reconciliation Sweep Modal)
    console.log('3. Testing Modal Backdrop & Zoom-in Motion...');
    await page.goto('http://localhost:5173/app/reconciliation', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('button[data-testid="trigger-sweep-btn"]', { timeout: 10000 });
    await new Promise(r => setTimeout(r, 300));
    await page.click('button[data-testid="trigger-sweep-btn"]');
    await new Promise(r => setTimeout(r, 80)); // Backdrop fade-in + dialog zoom-in-95
    await page.screenshot({ path: path.join(MOTION_DIR, '05_modal_animating_in.png') });
    await new Promise(r => setTimeout(r, 250)); // Fully visible modal
    await page.screenshot({ path: path.join(MOTION_DIR, '06_modal_fully_open.png') });

    // Dismiss modal via Cancel
    const cancelBtn = await page.evaluateHandle(() => {
      const buttons = Array.from(document.querySelectorAll('button'));
      return buttons.find(b => b.textContent && b.textContent.includes('Cancel'));
    });
    if (cancelBtn) {
      await cancelBtn.click();
      await new Promise(r => setTimeout(r, 200));
      await page.screenshot({ path: path.join(MOTION_DIR, '07_modal_dismissed.png') });
    }

    // 4. Mobile Navigation Drawer Slide-In Motion
    console.log('4. Testing Mobile Navigation Drawer Animation...');
    await page.setViewport({ width: 390, height: 844 }); // iPhone viewport
    await page.goto('http://localhost:5173/app/dashboard', { waitUntil: 'domcontentloaded' });
    await page.screenshot({ path: path.join(MOTION_DIR, '08_mobile_view.png') });
    const mobileMenuBtn = await page.$('button[aria-label="Open navigation menu"]');
    if (mobileMenuBtn) {
      await mobileMenuBtn.click();
      await new Promise(r => setTimeout(r, 80)); // Mid slide-in
      await page.screenshot({ path: path.join(MOTION_DIR, '09_drawer_sliding_in.png') });
      await new Promise(r => setTimeout(r, 200)); // Fully open drawer
      await page.screenshot({ path: path.join(MOTION_DIR, '10_drawer_open.png') });
    }

    // Restore desktop viewport
    await page.setViewport({ width: 1440, height: 900 });

    // 5. 3D Warehouse Visualization Canvas Motion
    console.log('5. Testing 3D Warehouse WebGL Canvas Motion on Landing Page...');
    await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('canvas', { timeout: 15000 });
    await new Promise(r => setTimeout(r, 1200));
    await page.screenshot({ path: path.join(MOTION_DIR, '11_warehouse_3d_webgl_canvas.png') });
    console.log('   Captured 3D Warehouse WebGL Canvas.');

    // 5b. Camera Presets (Plan View, Aisle Cam, Dock 01, Isometric)
    console.log('5b. Testing Camera Presets...');
    const clickButtonByText = async (text) => {
      await page.evaluate((btnText) => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const target = buttons.find(b => b.textContent && b.textContent.includes(btnText));
        if (target) target.click();
      }, text);
      await new Promise(r => setTimeout(r, 900)); // Smooth GSAP transition
    };

    await clickButtonByText('Plan View');
    await page.screenshot({ path: path.join(MOTION_DIR, '11b_camera_plan_view.png') });

    await clickButtonByText('Aisle Cam');
    await page.screenshot({ path: path.join(MOTION_DIR, '11c_camera_aisle_cam.png') });

    await clickButtonByText('Dock 01');
    await page.screenshot({ path: path.join(MOTION_DIR, '11d_camera_dock_01.png') });

    await clickButtonByText('Isometric');
    await page.screenshot({ path: path.join(MOTION_DIR, '11e_camera_isometric.png') });

    // 5c. Storytelling Step Transitions (Receive -> Allocate -> Reserve -> Transfer -> Fulfill -> Reconcile)
    console.log('5c. Testing Storytelling Step Transitions...');
    for (let step = 1; step <= 6; step++) {
      await page.evaluate((stepNum) => {
        const buttons = Array.from(document.querySelectorAll('button'));
        const target = buttons.find(b => b.textContent && b.textContent.trim().startsWith(String(stepNum)));
        if (target) target.click();
      }, step);
      await new Promise(r => setTimeout(r, 800));
      await page.screenshot({ path: path.join(MOTION_DIR, `11f_story_step_${step}.png`) });
    }
    console.log('   Captured all 6 storytelling walkthrough steps.');

    // 5d. Raycast Click Inspection
    console.log('5d. Testing Raycast Click on Canvas to trigger Inspection HUD...');
    const canvas = await page.$('canvas');
    if (canvas) {
      const box = await canvas.boundingBox();
      if (box) {
        // Click slightly above center to hit a shelf pallet
        await page.mouse.click(box.x + box.width * 0.5, box.y + box.height * 0.45);
        await new Promise(r => setTimeout(r, 500));
        await page.screenshot({ path: path.join(MOTION_DIR, '11g_raycast_inspected_pallet_hud.png') });
        console.log('   Captured raycast pallet inspection HUD.');
      }
    }

    // 6. Reduced Motion Accessibility Check
    console.log('6. Testing Prefers-Reduced-Motion Media Query Behavior...');
    await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
    const bodyTransitionDuration = await page.evaluate(() => {
      const el = document.querySelector('body');
      const style = window.getComputedStyle(el);
      return style.transitionDuration;
    });
    console.log(`   Reduced motion active: body transition-duration = ${bodyTransitionDuration}`);
    await page.screenshot({ path: path.join(MOTION_DIR, '12_reduced_motion_active.png') });

    console.log('\n=== ALL MOTION & TRANSITION ACCEPTANCE TESTS COMPLETED ===');
  } catch (err) {
    console.error('Fatal error during motion acceptance:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

runMotionAcceptance();
