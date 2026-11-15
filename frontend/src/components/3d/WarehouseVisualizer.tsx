import React, { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { Eye, RotateCw } from 'lucide-react';

export const WarehouseVisualizer: React.FC = () => {
  const mountRef = useRef<HTMLDivElement>(null);
  const [hoveredInfo, setHoveredInfo] = useState<{
    bay: string;
    sku: string;
    onHand: number;
    reserved: number;
    status: string;
  } | null>(null);

  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    // Check reduced motion preference
    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // 1. Scene, Camera, Renderer
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x080c14);
    scene.fog = new THREE.FogExp2(0x080c14, 0.025);

    const width = container.clientWidth;
    const height = container.clientHeight || 480;

    const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
    camera.position.set(16, 14, 18);
    camera.lookAt(0, 0, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    container.appendChild(renderer.domElement);

    // 2. Lights
    const ambientLight = new THREE.AmbientLight(0x94a3b8, 0.6);
    scene.add(ambientLight);

    const dirLight = new THREE.DirectionalLight(0x06b6d4, 1.8);
    dirLight.position.set(12, 18, 10);
    dirLight.castShadow = true;
    dirLight.shadow.mapSize.width = 1024;
    dirLight.shadow.mapSize.height = 1024;
    scene.add(dirLight);

    const accentLight = new THREE.PointLight(0x6366f1, 2.5, 30);
    accentLight.position.set(-6, 8, -6);
    scene.add(accentLight);

    // 3. Grid Floor
    const gridHelper = new THREE.GridHelper(30, 30, 0x1e293b, 0x0f172a);
    gridHelper.position.y = -0.01;
    scene.add(gridHelper);

    // 4. Warehouse Racks and Cargo Pallets
    const racksGroup = new THREE.Group();
    scene.add(racksGroup);

    const rackGeo = new THREE.BoxGeometry(0.12, 4.5, 0.12);
    const rackMat = new THREE.MeshStandardMaterial({ color: 0x334155, metalness: 0.8, roughness: 0.2 });

    const beamGeo = new THREE.BoxGeometry(3.6, 0.08, 0.8);
    const beamMat = new THREE.MeshStandardMaterial({ color: 0x1e293b, metalness: 0.5, roughness: 0.4 });

    const palletGeo = new THREE.BoxGeometry(0.9, 0.65, 0.7);
    const palletMaterials = [
      new THREE.MeshStandardMaterial({ color: 0x06b6d4, roughness: 0.3, metalness: 0.2 }), // Cyan: Available
      new THREE.MeshStandardMaterial({ color: 0x10b981, roughness: 0.3, metalness: 0.2 }), // Emerald: Stocked
      new THREE.MeshStandardMaterial({ color: 0x6366f1, roughness: 0.3, metalness: 0.2 }), // Indigo: Reserved
      new THREE.MeshStandardMaterial({ color: 0xf59e0b, roughness: 0.3, metalness: 0.2 }), // Amber: Discrepancy
    ];

    const interactivePallets: THREE.Mesh[] = [];

    // Create 4 rows of 3 racks each
    for (let row = -2; row <= 2; row += 1.5) {
      if (row === 0) continue; // aisle in center
      for (let col = -6; col <= 6; col += 4.2) {
        // Vertical pillars
        const pillar1 = new THREE.Mesh(rackGeo, rackMat);
        pillar1.position.set(col - 1.8, 2.25, row - 0.4);
        const pillar2 = new THREE.Mesh(rackGeo, rackMat);
        pillar2.position.set(col + 1.8, 2.25, row - 0.4);
        const pillar3 = new THREE.Mesh(rackGeo, rackMat);
        pillar3.position.set(col - 1.8, 2.25, row + 0.4);
        const pillar4 = new THREE.Mesh(rackGeo, rackMat);
        pillar4.position.set(col + 1.8, 2.25, row + 0.4);
        racksGroup.add(pillar1, pillar2, pillar3, pillar4);

        // Horizontal shelves (3 tiers)
        for (let tier = 1; tier <= 3; tier++) {
          const shelfY = tier * 1.35;
          const beam = new THREE.Mesh(beamGeo, beamMat);
          beam.position.set(col, shelfY, row);
          racksGroup.add(beam);

          // Pallets on shelf
          for (let p = -1; p <= 1; p++) {
            if (Math.random() > 0.15) {
              const matIndex = Math.floor(Math.random() * palletMaterials.length);
              const pallet = new THREE.Mesh(palletGeo, palletMaterials[matIndex]);
              pallet.position.set(col + p * 1.1, shelfY + 0.35, row);
              pallet.castShadow = true;
              pallet.receiveShadow = true;

              // Attach custom metadata
              pallet.userData = {
                bay: `BAY-${Math.abs(row).toFixed(0)}-${Math.abs(col).toFixed(0)}-T${tier}`,
                sku: `SKU-${1000 + Math.floor(Math.random() * 9000)}`,
                onHand: 40 + Math.floor(Math.random() * 200),
                reserved: Math.floor(Math.random() * 25),
                status: ['AVAILABLE', 'OPTIMAL', 'RESERVED_ACTIVE', 'AUDITING'][matIndex],
              };

              interactivePallets.push(pallet);
              racksGroup.add(pallet);
            }
          }
        }
      }
    }

    // 5. Automated Transfer Cart (moving along aisle)
    const cartGeo = new THREE.BoxGeometry(1.6, 0.4, 1.0);
    const cartMat = new THREE.MeshStandardMaterial({ color: 0x22d3ee, metalness: 0.8, roughness: 0.1 });
    const cart = new THREE.Mesh(cartGeo, cartMat);
    cart.position.set(-6, 0.2, 0);
    cart.castShadow = true;
    scene.add(cart);

    // Pallet on top of cart
    const cartPallet = new THREE.Mesh(palletGeo, palletMaterials[0]);
    cartPallet.position.set(0, 0.5, 0);
    cart.add(cartPallet);

    // 6. Raycaster for hover interactions
    const raycaster = new THREE.Raycaster();
    const mouse = new THREE.Vector2();

    const handleMouseMove = (e: MouseEvent) => {
      const rect = renderer.domElement.getBoundingClientRect();
      mouse.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      mouse.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;

      raycaster.setFromCamera(mouse, camera);
      const intersects = raycaster.intersectObjects(interactivePallets);

      if (intersects.length > 0) {
        const top = intersects[0].object;
        if (top.userData) {
          setHoveredInfo(top.userData as typeof hoveredInfo);
        }
      } else {
        setHoveredInfo(null);
      }
    };

    container.addEventListener('mousemove', handleMouseMove);

    // 7. Animation loop
    let animationFrameId: number;
    let clock = new THREE.Clock();

    const animate = () => {
      animationFrameId = requestAnimationFrame(animate);
      const elapsed = clock.getElapsedTime();

      if (!prefersReducedMotion) {
        // Slow gentle orbit
        const radius = 22;
        const angle = elapsed * 0.08;
        camera.position.x = Math.cos(angle) * radius;
        camera.position.z = Math.sin(angle) * radius;
        camera.lookAt(0, 1.5, 0);

        // Move AGV cart along aisle
        cart.position.x = Math.sin(elapsed * 0.8) * 6;
      }

      renderer.render(scene, camera);
    };

    animate();

    // 8. Responsive resize handler
    const handleResize = () => {
      if (!container) return;
      const w = container.clientWidth;
      const h = container.clientHeight || 480;
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      container.removeEventListener('mousemove', handleMouseMove);
      cancelAnimationFrame(animationFrameId);
      renderer.dispose();
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement);
      }
    };
  }, []);

  return (
    <div className="relative w-full h-[520px] rounded-2xl overflow-hidden border border-slate-800 bg-[#080c14] shadow-2xl">
      {/* 3D WebGL Canvas */}
      <div ref={mountRef} className="w-full h-full cursor-grab active:cursor-grabbing" />

      {/* Real-time HUD Overlay */}
      <div className="absolute top-4 left-4 pointer-events-none flex flex-col gap-2">
        <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-900/80 backdrop-blur border border-slate-800 text-xs text-slate-300">
          <RotateCw className="w-3.5 h-3.5 text-cyan-400 animate-spin" />
          <span className="font-mono text-cyan-300">LIVE WAREHOUSE TELEMETRY · 3D MVCC TWIN</span>
        </div>
        <div className="flex items-center gap-4 text-[11px] font-mono text-slate-400 px-3 py-1 bg-slate-950/60 rounded backdrop-blur">
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-cyan-400" /> Available
          </span>
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-indigo-400" /> Reserved
          </span>
          <span className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-amber-400" /> Discrepancy
          </span>
        </div>
      </div>

      {/* Hover Inspection Tooltip */}
      {hoveredInfo && (
        <div className="absolute bottom-4 right-4 z-20 pointer-events-none p-3.5 rounded-xl bg-[#0e172a]/95 border border-cyan-500/40 backdrop-blur shadow-2xl space-y-1.5 text-left animate-in fade-in duration-100">
          <div className="flex items-center justify-between gap-4">
            <span className="text-[10px] font-mono text-cyan-400 uppercase font-semibold">
              {hoveredInfo.bay}
            </span>
            <span className="text-[10px] font-mono text-slate-400">
              {hoveredInfo.status}
            </span>
          </div>
          <div className="text-sm font-bold text-white font-mono">{hoveredInfo.sku}</div>
          <div className="flex items-center gap-4 text-xs font-mono pt-1 border-t border-slate-800">
            <div>
              <span className="text-slate-400">On Hand: </span>
              <span className="text-emerald-400 font-bold">{hoveredInfo.onHand}</span>
            </div>
            <div>
              <span className="text-slate-400">Reserved: </span>
              <span className="text-indigo-400 font-bold">{hoveredInfo.reserved}</span>
            </div>
          </div>
        </div>
      )}

      {/* Interaction Hint */}
      <div className="absolute bottom-4 left-4 pointer-events-none text-[11px] text-slate-500 font-mono flex items-center gap-1.5">
        <Eye className="w-3.5 h-3.5 text-slate-400" /> Hover over cargo pallets to inspect real-time bay telemetry
      </div>
    </div>
  );
};
