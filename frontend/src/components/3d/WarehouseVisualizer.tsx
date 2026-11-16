import React, { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import {
  Play,
  RotateCcw,
  ShieldCheck,
  Truck,
  Boxes,
  BookmarkCheck,
  ArrowLeftRight,
  CheckCircle2,
} from 'lucide-react';

export type CameraPreset = 'isometric' | 'topDown' | 'aisle' | 'dock';

export interface StoryStep {
  id: number;
  title: string;
  subtitle: string;
  description: string;
  databaseConcept: string;
  cameraPreset: CameraPreset;
  activeZone: string;
  icon: React.ReactNode;
}

export const WarehouseVisualizer: React.FC<{ className?: string }> = ({ className = '' }) => {
  const mountRef = useRef<HTMLDivElement>(null);
  const [activePreset, setActivePreset] = useState<CameraPreset>('isometric');
  const [activeStep, setActiveStep] = useState<number>(1);
  const [isAutoPlaying, setIsAutoPlaying] = useState<boolean>(false);
  const [hoveredInfo, setHoveredInfo] = useState<{
    bay: string;
    sku: string;
    product: string;
    onHand: number;
    reserved: number;
    available: number;
    zone: string;
    status: string;
  } | null>(null);

  // References to communicate between React state and Three.js animation loop
  const cameraTargetRef = useRef<THREE.Vector3>(new THREE.Vector3(18, 14, 20));
  const lookAtTargetRef = useRef<THREE.Vector3>(new THREE.Vector3(0, 1.5, 0));
  const agvRef = useRef<THREE.Group | null>(null);
  const activeStepRef = useRef<number>(1);
  useEffect(() => {
    activeStepRef.current = activeStep;
  }, [activeStep]);

  const storySteps: StoryStep[] = [
    {
      id: 1,
      title: '1. Receive Stock at Inbound Dock',
      subtitle: 'Physical receipt & initial ledger entry',
      description: 'Shipment arrives at Dock 01. Stock is verified against purchase PO and ingested into an append-only double-entry ledger with an initial debit transaction.',
      databaseConcept: 'INSERT INTO inventory_transactions (type: RECEIPT, isolation: READ_COMMITTED)',
      cameraPreset: 'dock',
      activeZone: 'Dock 01 — Receiving Staging',
      icon: <Truck className="w-4 h-4 text-[#C6F36B] dark:text-[#C6F36B]" />,
    },
    {
      id: 2,
      title: '2. Allocate to High-Density Racks',
      subtitle: 'Put-away assignment & location balance',
      description: 'Inventory is assigned to Bay A-02 on Tier 2. The inventory balance record is locked with SELECT FOR UPDATE to prevent phantom over-allocation.',
      databaseConcept: 'SELECT * FROM inventory_balances WHERE warehouse_id = ? FOR UPDATE',
      cameraPreset: 'aisle',
      activeZone: 'Zone A — High-Density Racks',
      icon: <Boxes className="w-4 h-4 text-[#6CD8C0] dark:text-[#6CD8C0]" />,
    },
    {
      id: 3,
      title: '3. Serialized Customer Reservation',
      subtitle: 'Concurrency lock & available stock reduction',
      description: 'Customer order places an active reservation. Available stock drops from 120 to 100 while On-Hand remains 120. A TTL expiration timer arms.',
      databaseConcept: 'UPDATE inventory_balances SET reserved = reserved + 20, version = version + 1',
      cameraPreset: 'isometric',
      activeZone: 'Zone A — Bay A-02-T2',
      icon: <BookmarkCheck className="w-4 h-4 text-amber-400" />,
    },
    {
      id: 4,
      title: '4. Inter-Warehouse Autonomous Transfer',
      subtitle: 'Atomic pairing & double-entry movement',
      description: 'AGV unit moves stock from Zone A to Zone B cross-dock corridor. Origin on_hand decrements as destination on_hand increments in a single atomic transaction.',
      databaseConcept: 'BEGIN; DEBIT origin; CREDIT destination; COMMIT;',
      cameraPreset: 'isometric',
      activeZone: 'Transit Corridor B-01',
      icon: <ArrowLeftRight className="w-4 h-4 text-[#6CD8C0]" />,
    },
    {
      id: 5,
      title: '5. Pick & Fulfill Order',
      subtitle: 'Reservation conversion & dispatch',
      description: 'Order enters pick/pack lifecycle. Reserved stock converts to fulfilled status, decrementing both physical on-hand and active reservation counts.',
      databaseConcept: 'UPDATE orders SET status = "FULFILLED"; RELEASE reservation;',
      cameraPreset: 'aisle',
      activeZone: 'Zone B — Active Pick Bay',
      icon: <CheckCircle2 className="w-4 h-4 text-[#C6F36B]" />,
    },
    {
      id: 6,
      title: '6. Automated MVCC Reconciliation',
      subtitle: 'Zero phantom stock verification sweep',
      description: 'Scheduled reconciliation worker executes an MVCC snapshot audit, summing ledger movements against snapshot balance. Verified: Zero drift.',
      databaseConcept: 'SELECT balance = SUM(ledger) FROM audit_sweep; Discrepancies: 0',
      cameraPreset: 'topDown',
      activeZone: 'Entire Facility Mesh',
      icon: <ShieldCheck className="w-4 h-4 text-[#C6F36B]" />,
    },
  ];

  // Camera presets map
  const setPreset = (preset: CameraPreset) => {
    setActivePreset(preset);
    switch (preset) {
      case 'isometric':
        cameraTargetRef.current.set(18, 14, 20);
        lookAtTargetRef.current.set(0, 1.5, 0);
        break;
      case 'topDown':
        cameraTargetRef.current.set(0, 26, 0.1);
        lookAtTargetRef.current.set(0, 0, 0);
        break;
      case 'aisle':
        cameraTargetRef.current.set(-1, 2.5, 12);
        lookAtTargetRef.current.set(-1, 2.5, -6);
        break;
      case 'dock':
        cameraTargetRef.current.set(14, 5, -8);
        lookAtTargetRef.current.set(8, 1.2, -6);
        break;
    }
  };

  const handleStepSelect = (stepId: number) => {
    setActiveStep(stepId);
    const step = storySteps.find((s) => s.id === stepId);
    if (step) {
      setPreset(step.cameraPreset);
    }
  };

  // Autoplay loop timer
  useEffect(() => {
    if (!isAutoPlaying) return;
    const interval = setInterval(() => {
      setActiveStep((prev) => {
        const next = prev >= 6 ? 1 : prev + 1;
        const step = storySteps.find((s) => s.id === next);
        if (step) setPreset(step.cameraPreset);
        return next;
      });
    }, 4500);
    return () => clearInterval(interval);
  }, [isAutoPlaying]);

  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // Detect dark or light mode
    const isDark = document.documentElement.classList.contains('dark');

    // 1. Scene, Camera, Renderer
    const scene = new THREE.Scene();
    const bgColor = isDark ? 0x0b100f : 0xf4f5ef;
    const fogColor = isDark ? 0x0b100f : 0xf4f5ef;
    scene.background = new THREE.Color(bgColor);
    scene.fog = new THREE.FogExp2(fogColor, 0.022);

    const width = container.clientWidth;
    const height = container.clientHeight || 520;

    const camera = new THREE.PerspectiveCamera(42, width / height, 0.1, 150);
    camera.position.set(18, 14, 20);
    camera.lookAt(0, 1.5, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    container.appendChild(renderer.domElement);

    // 2. Lighting System
    const ambientLight = new THREE.AmbientLight(isDark ? 0x7a8a80 : 0xd8ded6, 0.85);
    scene.add(ambientLight);

    const mainLight = new THREE.DirectionalLight(isDark ? 0xc6f36b : 0xffffff, isDark ? 1.4 : 1.8);
    mainLight.position.set(15, 22, 12);
    mainLight.castShadow = true;
    mainLight.shadow.mapSize.width = 1024;
    mainLight.shadow.mapSize.height = 1024;
    mainLight.shadow.camera.near = 0.5;
    mainLight.shadow.camera.far = 60;
    mainLight.shadow.camera.left = -20;
    mainLight.shadow.camera.right = 20;
    mainLight.shadow.camera.top = 20;
    mainLight.shadow.camera.bottom = -20;
    scene.add(mainLight);

    const telemetryLight = new THREE.PointLight(isDark ? 0x6cd8c0 : 0x147c68, 2.0, 35);
    telemetryLight.position.set(-8, 9, -6);
    scene.add(telemetryLight);

    // 3. Concrete Floor with Zone Markings & Hazard Guides
    const floorGeo = new THREE.PlaneGeometry(42, 34);
    const floorMat = new THREE.MeshStandardMaterial({
      color: isDark ? 0x111a17 : 0xe9eee6,
      roughness: 0.85,
      metalness: 0.15,
    });
    const floor = new THREE.Mesh(floorGeo, floorMat);
    floor.rotation.x = -Math.PI / 2;
    floor.receiveShadow = true;
    scene.add(floor);

    // Grid lines for precision spatial alignment
    const gridHelper = new THREE.GridHelper(36, 36, isDark ? 0x26362e : 0xd9e1d7, isDark ? 0x16221e : 0xe9eee6);
    gridHelper.position.y = 0.005;
    scene.add(gridHelper);

    // 4. Industrial Heavy-Duty Storage Racks
    const racksGroup = new THREE.Group();
    scene.add(racksGroup);

    const uprightGeo = new THREE.BoxGeometry(0.14, 5.2, 0.14);
    const uprightMat = new THREE.MeshStandardMaterial({
      color: isDark ? 0x26362e : 0x657369,
      metalness: 0.75,
      roughness: 0.3,
    });

    const beamGeo = new THREE.BoxGeometry(4.2, 0.1, 0.9);
    const beamMat = new THREE.MeshStandardMaterial({
      color: isDark ? 0xb45309 : 0xb45309, // Industrial Safety Amber
      metalness: 0.6,
      roughness: 0.35,
    });

    // Color-coded cargo pallets
    const palletWoodGeo = new THREE.BoxGeometry(1.0, 0.12, 0.8);
    const palletWoodMat = new THREE.MeshStandardMaterial({ color: 0x8a6f47, roughness: 0.9 });

    const boxGeo = new THREE.BoxGeometry(0.9, 0.65, 0.72);
    const statusMaterials = [
      new THREE.MeshStandardMaterial({ color: isDark ? 0xc6f36b : 0x456b1f, roughness: 0.25, metalness: 0.2 }), // AVAILABLE (Signal Lime)
      new THREE.MeshStandardMaterial({ color: 0xf59e0b, roughness: 0.3, metalness: 0.2 }),                      // RESERVED (Amber)
      new THREE.MeshStandardMaterial({ color: 0x6cd8c0, roughness: 0.25, metalness: 0.2 }),                      // TELEMETRY / IN-TRANSIT
      new THREE.MeshStandardMaterial({ color: 0xf43f5e, roughness: 0.3, metalness: 0.2 }),                      // AUDIT DISCREPANCY
    ];

    const interactivePallets: THREE.Mesh[] = [];

    // Construct 3 distinct storage aisles (Aisle 1, Aisle 2, Aisle 3)
    const aisleRows = [-3.8, 0, 3.8];
    const columns = [-7.5, -3.2, 1.1, 5.4];

    aisleRows.forEach((rowZ, rowIndex) => {
      // 2 rack banks per aisle
      [-0.8, 0.8].forEach((bankOffset) => {
        const currentZ = rowZ + bankOffset;

        columns.forEach((colX) => {
          // 4 Vertical structural posts
          const p1 = new THREE.Mesh(uprightGeo, uprightMat);
          p1.position.set(colX - 2.0, 2.6, currentZ - 0.4);
          const p2 = new THREE.Mesh(uprightGeo, uprightMat);
          p2.position.set(colX + 2.0, 2.6, currentZ - 0.4);
          const p3 = new THREE.Mesh(uprightGeo, uprightMat);
          p3.position.set(colX - 2.0, 2.6, currentZ + 0.4);
          const p4 = new THREE.Mesh(uprightGeo, uprightMat);
          p4.position.set(colX + 2.0, 2.6, currentZ + 0.4);
          racksGroup.add(p1, p2, p3, p4);

          // 3 Horizontal shelving tiers
          for (let tier = 1; tier <= 3; tier++) {
            const shelfY = tier * 1.55;
            const beam = new THREE.Mesh(beamGeo, beamMat);
            beam.position.set(colX, shelfY, currentZ);
            racksGroup.add(beam);

            // 3 Pallet slots per shelf beam
            [-1.2, 0, 1.2].forEach((slotOffset, slotIdx) => {
              if (Math.random() > 0.12) {
                const palletGroup = new THREE.Group();
                palletGroup.position.set(colX + slotOffset, shelfY + 0.08, currentZ);

                const wood = new THREE.Mesh(palletWoodGeo, palletWoodMat);
                wood.position.y = 0.06;
                palletGroup.add(wood);

                const matIdx = Math.floor(Math.random() * statusMaterials.length);
                const box = new THREE.Mesh(boxGeo, statusMaterials[matIdx]);
                box.position.y = 0.45;
                box.castShadow = true;
                box.receiveShadow = true;

                // Attach rich operational metadata for raycasting HUD
                box.userData = {
                  bay: `BAY-A0${rowIndex + 1}-C${colX.toFixed(0)}-T${tier}-S${slotIdx + 1}`,
                  sku: `SKU-${1000 + (rowIndex * 30 + tier * 10 + slotIdx)}`,
                  product: ['Precision Servo Motor', 'Industrial Optical Sensor', 'High-Torque Actuator', 'MVCC Telemetry Module'][slotIdx % 4],
                  onHand: 40 + Math.floor(Math.random() * 180),
                  reserved: Math.floor(Math.random() * 25),
                  available: 0,
                  zone: `Zone A — Aisle 0${rowIndex + 1}`,
                  status: ['AVAILABLE', 'RESERVED_ACTIVE', 'IN_TRANSIT', 'AUDIT_FLAGGED'][matIdx],
                };
                box.userData.available = box.userData.onHand - box.userData.reserved;

                palletGroup.add(box);
                interactivePallets.push(box);
                racksGroup.add(palletGroup);
              }
            });
          }
        });
      });
    });

    // 5. Inbound Receiving Dock 01 Staging Platform
    const dockGeo = new THREE.BoxGeometry(6.5, 0.4, 5.0);
    const dockMat = new THREE.MeshStandardMaterial({
      color: isDark ? 0x16221e : 0xdde4da,
      roughness: 0.6,
      metalness: 0.3,
    });
    const dock = new THREE.Mesh(dockGeo, dockMat);
    dock.position.set(11, 0.2, -7.5);
    dock.receiveShadow = true;
    scene.add(dock);

    // Inbound pallets on dock
    for (let i = -1; i <= 1; i++) {
      const p = new THREE.Mesh(boxGeo, statusMaterials[0]);
      p.position.set(11 + i * 1.5, 0.8, -7.5);
      p.castShadow = true;
      p.userData = {
        bay: `DOCK-01-BAY-${i + 2}`,
        sku: `SKU-INBOUND-${800 + i}`,
        product: 'Raw Inbound Inventory Shipment',
        onHand: 250,
        reserved: 0,
        available: 250,
        zone: 'Dock 01 — Staging Bay',
        status: 'RECEIVED_UNALLOCATED',
      };
      interactivePallets.push(p);
      scene.add(p);
    }

    // 6. Automated Guided Vehicle (AGV) with Transport Pallet
    const agvGroup = new THREE.Group();
    agvGroup.position.set(0, 0.25, 0);

    const agvBodyGeo = new THREE.BoxGeometry(1.8, 0.35, 1.1);
    const agvBodyMat = new THREE.MeshStandardMaterial({
      color: isDark ? 0x1b2922 : 0x26362e,
      metalness: 0.8,
      roughness: 0.2,
    });
    const agvBody = new THREE.Mesh(agvBodyGeo, agvBodyMat);
    agvBody.castShadow = true;
    agvGroup.add(agvBody);

    // AGV Status Beacon
    const beaconGeo = new THREE.CylinderGeometry(0.08, 0.08, 0.2, 16);
    const beaconMat = new THREE.MeshStandardMaterial({
      color: 0xc6f36b,
      emissive: 0xc6f36b,
      emissiveIntensity: 0.8,
    });
    const beacon = new THREE.Mesh(beaconGeo, beaconMat);
    beacon.position.set(0.7, 0.3, 0.4);
    agvGroup.add(beacon);

    // Pallet transported by AGV
    const agvCargo = new THREE.Mesh(boxGeo, statusMaterials[2]);
    agvCargo.position.set(0, 0.55, 0);
    agvCargo.castShadow = true;
    agvCargo.userData = {
      bay: 'AGV-UNIT-01 (MOBILE)',
      sku: 'SKU-TRANS-904',
      product: 'In-Transit Cross-Dock Shipment',
      onHand: 50,
      reserved: 50,
      available: 0,
      zone: 'Autonomous Transit Lane',
      status: 'IN_TRANSIT_ATOMIC',
    };
    interactivePallets.push(agvCargo);
    agvGroup.add(agvCargo);

    scene.add(agvGroup);
    agvRef.current = agvGroup;

    // 7. Raycasting for Pallet Hover / Click Interaction
    const raycaster = new THREE.Raycaster();
    const mouse = new THREE.Vector2();

    const handlePointerMove = (e: MouseEvent) => {
      const rect = renderer.domElement.getBoundingClientRect();
      mouse.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      mouse.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;

      raycaster.setFromCamera(mouse, camera);
      const intersects = raycaster.intersectObjects(interactivePallets);

      if (intersects.length > 0) {
        const target = intersects[0].object;
        if (target.userData && target.userData.bay) {
          setHoveredInfo(target.userData as typeof hoveredInfo);
          document.body.style.cursor = 'pointer';
        }
      } else {
        setHoveredInfo(null);
        document.body.style.cursor = 'default';
      }
    };

    container.addEventListener('mousemove', handlePointerMove);

    // 8. Animation & Camera Lerp Loop
    let animationFrameId: number;
    const clock = new THREE.Clock();

    const animate = () => {
      animationFrameId = requestAnimationFrame(animate);
      const elapsed = clock.getElapsedTime();

      // Smooth camera interpolation towards active target
      const lerpFactor = prefersReducedMotion ? 1.0 : 0.045;
      camera.position.lerp(cameraTargetRef.current, lerpFactor);

      // Camera look-at target smooth lerp
      camera.lookAt(lookAtTargetRef.current);

      if (!prefersReducedMotion) {
        // AGV Waypoint navigation across transit aisle
        if (agvRef.current) {
          const t = elapsed * 0.7;
          agvRef.current.position.x = Math.sin(t) * 7.0;
          agvRef.current.position.z = Math.cos(t * 0.5) * 1.8;
          // Rotate slightly based on velocity
          agvRef.current.rotation.y = Math.cos(t) > 0 ? 0 : Math.PI;
        }

        // Gentle ambient orbit when in isometric mode
        if (activePreset === 'isometric') {
          const wobble = Math.sin(elapsed * 0.15) * 0.8;
          camera.position.x = 18 + wobble;
        }
      }

      renderer.render(scene, camera);
    };

    animate();

    // 9. Resize Observer
    const handleResize = () => {
      if (!container) return;
      const w = container.clientWidth;
      const h = container.clientHeight || 520;
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      container.removeEventListener('mousemove', handlePointerMove);
      cancelAnimationFrame(animationFrameId);
      document.body.style.cursor = 'default';
      if (container && renderer.domElement) {
        container.removeChild(renderer.domElement);
      }
      renderer.dispose();
    };
  }, [activePreset]);

  return (
    <div className={`relative w-full rounded-2xl overflow-hidden border border-[#D9E1D7] dark:border-[#26362E] bg-[#F4F5EF] dark:bg-[#0B100F] shadow-xl ${className}`}>
      {/* 3D WebGL Canvas Viewport */}
      <div ref={mountRef} className="h-[480px] sm:h-[560px] w-full relative touch-none" />

      {/* Top Floating Spatial HUD & Camera Presets */}
      <div className="absolute top-4 left-4 right-4 flex flex-wrap items-center justify-between gap-3 pointer-events-none">
        {/* Status Badge */}
        <div className="pointer-events-auto inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-white/90 dark:bg-[#111A17]/90 backdrop-blur-md border border-[#D9E1D7] dark:border-[#26362E] text-xs font-mono shadow-md">
          <span className="w-2 h-2 rounded-full bg-[#456B1F] dark:bg-[#C6F36B] animate-pulse" />
          <span className="font-semibold text-[#18231D] dark:text-[#EEF3EA]">SPATIAL WAREHOUSE TWIN</span>
          <span className="text-[#8A988E] dark:text-[#6C7D71]">|</span>
          <span className="text-[#456B1F] dark:text-[#C6F36B] font-bold">144 ACTIVE BAYS</span>
        </div>

        {/* Camera Preset Switches */}
        <div className="pointer-events-auto inline-flex items-center gap-1 p-1 rounded-xl bg-white/90 dark:bg-[#111A17]/90 backdrop-blur-md border border-[#D9E1D7] dark:border-[#26362E] shadow-md text-xs">
          <button
            onClick={() => setPreset('isometric')}
            className={`px-2.5 py-1 rounded-lg font-medium transition-colors cursor-pointer ${
              activePreset === 'isometric'
                ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/20 text-[#456B1F] dark:text-[#C6F36B] font-semibold'
                : 'text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]'
            }`}
          >
            Isometric
          </button>
          <button
            onClick={() => setPreset('topDown')}
            className={`px-2.5 py-1 rounded-lg font-medium transition-colors cursor-pointer ${
              activePreset === 'topDown'
                ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/20 text-[#456B1F] dark:text-[#C6F36B] font-semibold'
                : 'text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]'
            }`}
          >
            Plan View
          </button>
          <button
            onClick={() => setPreset('aisle')}
            className={`px-2.5 py-1 rounded-lg font-medium transition-colors cursor-pointer ${
              activePreset === 'aisle'
                ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/20 text-[#456B1F] dark:text-[#C6F36B] font-semibold'
                : 'text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]'
            }`}
          >
            Aisle Cam
          </button>
          <button
            onClick={() => setPreset('dock')}
            className={`px-2.5 py-1 rounded-lg font-medium transition-colors cursor-pointer ${
              activePreset === 'dock'
                ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/20 text-[#456B1F] dark:text-[#C6F36B] font-semibold'
                : 'text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]'
            }`}
          >
            Dock 01
          </button>
        </div>
      </div>

      {/* Floating Raycast Telemetry HUD (When Hovered) */}
      {hoveredInfo && (
        <div className="absolute top-20 right-4 w-72 rounded-xl bg-white/95 dark:bg-[#111A17]/95 backdrop-blur-md border border-[#D9E1D7] dark:border-[#26362E] p-3.5 shadow-2xl z-20 pointer-events-none animate-in fade-in zoom-in-95 duration-100 space-y-2">
          <div className="flex items-center justify-between pb-1.5 border-b border-[#D9E1D7] dark:border-[#26362E]">
            <span className="font-mono text-xs font-bold text-[#456B1F] dark:text-[#C6F36B]">{hoveredInfo.bay}</span>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#E9EEE6] dark:bg-[#1B2922] text-[#18231D] dark:text-[#EEF3EA]">
              {hoveredInfo.status}
            </span>
          </div>
          <div className="space-y-1">
            <p className="text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]">{hoveredInfo.product}</p>
            <p className="text-[11px] font-mono text-[#657369] dark:text-[#9AA99E]">{hoveredInfo.sku}</p>
          </div>
          <div className="grid grid-cols-3 gap-1 pt-1 text-center font-mono text-[11px] border-t border-[#D9E1D7] dark:border-[#26362E]">
            <div className="bg-[#F0F4EE] dark:bg-[#16221E] p-1.5 rounded">
              <span className="text-[9px] text-[#8A988E] dark:text-[#6C7D71] block">ON-HAND</span>
              <span className="font-bold text-[#18231D] dark:text-[#EEF3EA]">{hoveredInfo.onHand}</span>
            </div>
            <div className="bg-[#F0F4EE] dark:bg-[#16221E] p-1.5 rounded">
              <span className="text-[9px] text-[#8A988E] dark:text-[#6C7D71] block">RESERVED</span>
              <span className="font-bold text-amber-600 dark:text-amber-400">{hoveredInfo.reserved}</span>
            </div>
            <div className="bg-[#F0F4EE] dark:bg-[#16221E] p-1.5 rounded">
              <span className="text-[9px] text-[#8A988E] dark:text-[#6C7D71] block">AVAILABLE</span>
              <span className="font-bold text-[#456B1F] dark:text-[#C6F36B]">{hoveredInfo.available}</span>
            </div>
          </div>
        </div>
      )}

      {/* Interactive Storytelling Experience Bar (Bottom Controls) */}
      <div className="p-4 sm:p-5 bg-white/95 dark:bg-[#111A17]/95 border-t border-[#D9E1D7] dark:border-[#26362E] space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B] font-mono">
                Interactive Operational Lifecycle
              </span>
              <span className="text-xs text-[#8A988E] dark:text-[#6C7D71]">·</span>
              <span className="text-xs text-[#657369] dark:text-[#9AA99E]">Guided Database Invariants</span>
            </div>
            <h4 className="text-sm sm:text-base font-bold text-[#18231D] dark:text-[#EEF3EA] mt-0.5">
              {storySteps[activeStep - 1].title}
            </h4>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setIsAutoPlaying(!isAutoPlaying)}
              className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all cursor-pointer ${
                isAutoPlaying
                  ? 'bg-[#456B1F] dark:bg-[#C6F36B] text-white dark:text-[#0B100F] border-[#456B1F] dark:border-[#C6F36B]'
                  : 'bg-[#E9EEE6] dark:bg-[#1B2922] text-[#18231D] dark:text-[#EEF3EA] border-[#D9E1D7] dark:border-[#26362E]'
              }`}
            >
              {isAutoPlaying ? <RotateCcw className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
              {isAutoPlaying ? 'Auto-Advancing' : 'Play Lifecycle'}
            </button>
          </div>
        </div>

        {/* Story Details Card */}
        <div className="p-3.5 rounded-xl bg-[#F0F4EE] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] flex flex-col md:flex-row md:items-center justify-between gap-3 text-xs">
          <div className="space-y-1">
            <p className="text-[#18231D] dark:text-[#EEF3EA] leading-relaxed">
              {storySteps[activeStep - 1].description}
            </p>
            <div className="flex items-center gap-2 text-[#456B1F] dark:text-[#6CD8C0] font-mono text-[11px]">
              <span className="font-semibold">Backend Primitive:</span>
              <code className="px-1.5 py-0.5 rounded bg-white dark:bg-[#0B100F] border border-[#D9E1D7] dark:border-[#26362E]">
                {storySteps[activeStep - 1].databaseConcept}
              </code>
            </div>
          </div>
          <span className="shrink-0 text-[11px] font-mono px-2 py-1 rounded bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-[#657369] dark:text-[#9AA99E] self-start md:self-center">
            {storySteps[activeStep - 1].activeZone}
          </span>
        </div>

        {/* 6 Step Navigation Selector */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
          {storySteps.map((step) => {
            const isActive = step.id === activeStep;
            return (
              <button
                key={step.id}
                onClick={() => handleStepSelect(step.id)}
                className={`flex flex-col text-left p-2.5 rounded-xl border transition-all cursor-pointer ${
                  isActive
                    ? 'bg-white dark:bg-[#1B2922] border-[#456B1F] dark:border-[#C6F36B] shadow-xs ring-1 ring-[#456B1F]/30 dark:ring-[#C6F36B]/30'
                    : 'bg-[#F9FAF7] dark:bg-[#111A17] border-[#D9E1D7] dark:border-[#26362E] hover:border-[#657369]/40 dark:hover:border-[#9AA99E]/40'
                }`}
              >
                <div className="flex items-center justify-between w-full mb-1">
                  <span className="shrink-0">{step.icon}</span>
                  <span className="text-[10px] font-mono font-bold text-[#8A988E] dark:text-[#6C7D71]">0{step.id}</span>
                </div>
                <span className={`text-xs font-bold truncate ${isActive ? 'text-[#456B1F] dark:text-[#C6F36B]' : 'text-[#18231D] dark:text-[#EEF3EA]'}`}>
                  {step.title.split('. ')[1]}
                </span>
                <span className="text-[10px] text-[#657369] dark:text-[#9AA99E] truncate mt-0.5">
                  {step.subtitle}
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};
