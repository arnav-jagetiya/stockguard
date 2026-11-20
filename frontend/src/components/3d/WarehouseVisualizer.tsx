import React, { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { useTheme } from '../../context/ThemeContext';
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

interface SceneElements {
  scene: THREE.Scene;
  renderer: THREE.WebGLRenderer;
  ambientLight: THREE.AmbientLight;
  mainLight: THREE.DirectionalLight;
  telemetryLight: THREE.PointLight;
  floorMat: THREE.MeshStandardMaterial;
  dockMat: THREE.MeshStandardMaterial;
  uprightMat: THREE.MeshStandardMaterial;
  agvBodyMat: THREE.MeshStandardMaterial;
  availableMat: THREE.MeshStandardMaterial;
  transferMat: THREE.MeshStandardMaterial;
  gridHelper: THREE.GridHelper | null;
}

const STORY_STEPS: StoryStep[] = [
  {
    id: 1,
    title: '1. Receive Stock at Inbound Dock',
    subtitle: 'Receipt Delta: (+Q on-hand, 0 reserved)',
    description: 'Shipment arrives at Dock 01. Physical goods are verified against the purchase order and recorded with invariant delta (+Q, 0), incrementing on-hand stock while reserved stock remains zero.',
    databaseConcept: 'RECEIPT: (+Q, 0) | on_hand = on_hand + Q, reserved unchanged',
    cameraPreset: 'dock',
    activeZone: 'Dock 01 — Receiving Staging',
    icon: <Truck className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />,
  },
  {
    id: 2,
    title: '2. High-Density Rack Allocation',
    subtitle: 'Physical bay put-away & concurrency protection',
    description: 'Inventory is put away into Bay A-02 on Tier 2. Row-level concurrency is enforced via SELECT FOR UPDATE to guarantee serialized balance updates without lost updates.',
    databaseConcept: 'SELECT * FROM inventory_balances WHERE id = ? FOR UPDATE',
    cameraPreset: 'aisle',
    activeZone: 'Zone A — High-Density Racks',
    icon: <Boxes className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" />,
  },
  {
    id: 3,
    title: '3. Serialized Customer Reservation',
    subtitle: 'Reservation Delta: (0 on-hand, +Q reserved)',
    description: 'Customer order places an active reservation. Inventory balance transitions with invariant delta (0, +Q): Available drops (Available = On-Hand - Reserved) while physical On-Hand remains constant. Cancel/Release reverses with (0, -Q).',
    databaseConcept: 'RESERVE: (0, +Q) | RELEASE: (0, -Q) | TTL armed',
    cameraPreset: 'isometric',
    activeZone: 'Zone A — Bay A-02-T2',
    icon: <BookmarkCheck className="w-4 h-4 text-amber-600 dark:text-amber-400" />,
  },
  {
    id: 4,
    title: '4. Immediate Atomic Transfer',
    subtitle: 'Dual-entry: Source OUT (-Q, 0) & Destination IN (+Q, 0)',
    description: 'Transfers are strictly immediate and atomic with zero in-transit intermediate state. Source facility logs Transfer OUT (-Q, 0) and destination logs Transfer IN (+Q, 0) within a single database transaction boundary.',
    databaseConcept: 'ATOMIC TRANSFER: Source OUT (-Q, 0) + Dest IN (+Q, 0) in 1 TX',
    cameraPreset: 'isometric',
    activeZone: 'Dual-Facility Transfer Gateway (Immediate)',
    icon: <ArrowLeftRight className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" />,
  },
  {
    id: 5,
    title: '5. Order Picking & Fulfillment',
    subtitle: 'Fulfillment Delta: (-Q on-hand, -Q reserved)',
    description: 'Customer order dispatch commits fulfillment. The active reservation is released and physical on-hand is permanently deducted with invariant delta (-Q, -Q), posting an immutable credit ledger record.',
    databaseConcept: 'FULFILL: (-Q, -Q) | on_hand = on_hand - Q, reserved = reserved - Q',
    cameraPreset: 'aisle',
    activeZone: 'Zone B — Active Pick Bay',
    icon: <CheckCircle2 className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />,
  },
  {
    id: 6,
    title: '6. Balance vs Ledger Reconciliation',
    subtitle: 'Integrity Check: Balance On-Hand vs. Σ(Ledger Deltas)',
    description: 'Scheduled reconciliation worker executes an automated audit sweep (REPEATABLE READ snapshot isolation), summing all historical double-entry ledger deltas and comparing them directly against current inventory balance records to detect drift.',
    databaseConcept: 'AUDIT: inventory_balances.on_hand == SUM(ledger_entries.delta_on_hand)',
    cameraPreset: 'topDown',
    activeZone: 'Entire Facility Mesh (Audit Sweep)',
    icon: <ShieldCheck className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />,
  },
];

export const WarehouseVisualizer: React.FC<{ className?: string }> = ({ className = '' }) => {
  const mountRef = useRef<HTMLDivElement>(null);
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';

  const [activePreset, setActivePreset] = useState<CameraPreset>('isometric');
  const [activeStep, setActiveStep] = useState<number>(1);
  const [isAutoPlaying, setIsAutoPlaying] = useState<boolean>(false);
  const [webglError, setWebglError] = useState<boolean>(false);
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
  const activePresetRef = useRef<CameraPreset>('isometric');
  const agvRef = useRef<THREE.Group | null>(null);
  const activeStepRef = useRef<number>(1);
  const sceneElementsRef = useRef<SceneElements | null>(null);

  useEffect(() => {
    activeStepRef.current = activeStep;
  }, [activeStep]);

  useEffect(() => {
    activePresetRef.current = activePreset;
  }, [activePreset]);

  const storySteps = STORY_STEPS;

  // Camera presets map — lerps smoothly without destroying the WebGL scene
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
        const step = STORY_STEPS.find((s) => s.id === next);
        if (step) setPreset(step.cameraPreset);
        return next;
      });
    }, 4500);
    return () => clearInterval(interval);
  }, [isAutoPlaying]);

  // Reactive theme update effect: updates scene colors, lights, materials, fog, and grid instantaneously
  useEffect(() => {
    const el = sceneElementsRef.current;
    if (!el) return;

    // 1. Background & Fog
    const bgColor = isDark ? 0x0b100f : 0xf4f5ef;
    const fogColor = isDark ? 0x0b100f : 0xf4f5ef;
    el.scene.background = new THREE.Color(bgColor);
    el.scene.fog = new THREE.FogExp2(fogColor, isDark ? 0.022 : 0.020);

    // 2. Exposure & Tonemapping
    el.renderer.toneMappingExposure = isDark ? 1.15 : 1.05;

    // 3. Ambient Lighting
    el.ambientLight.color.set(isDark ? 0x7a8a80 : 0xd8ded6);
    el.ambientLight.intensity = isDark ? 0.85 : 0.95;

    // 4. Directional Sun/Key Light
    el.mainLight.color.set(isDark ? 0xc6f36b : 0xffffff);
    el.mainLight.intensity = isDark ? 1.4 : 1.7;

    // 5. Telemetry Fill Light
    el.telemetryLight.color.set(isDark ? 0x6cd8c0 : 0x147c68);
    el.telemetryLight.intensity = isDark ? 2.0 : 1.6;

    // 6. Floor & Staging Materials
    el.floorMat.color.set(isDark ? 0x111a17 : 0xe9eee6);
    el.floorMat.roughness = isDark ? 0.85 : 0.78;
    el.dockMat.color.set(isDark ? 0x16221e : 0xdde4da);

    // 7. Racks & AGV Structural Materials
    el.uprightMat.color.set(isDark ? 0x26362e : 0x657369);
    el.agvBodyMat.color.set(isDark ? 0x1b2922 : 0x26362e);

    // 8. Color-coded pallet status materials
    el.availableMat.color.set(isDark ? 0xc6f36b : 0x456b1f);
    el.transferMat.color.set(isDark ? 0x6cd8c0 : 0x147c68);

    // 9. Spatial Precision Grid Alignment
    if (el.gridHelper) {
      el.scene.remove(el.gridHelper);
      el.gridHelper.geometry.dispose();
      if (Array.isArray(el.gridHelper.material)) {
        el.gridHelper.material.forEach((m) => m.dispose());
      } else {
        el.gridHelper.material.dispose();
      }
    }
    const newGrid = new THREE.GridHelper(
      36,
      36,
      isDark ? 0x26362e : 0xd9e1d7,
      isDark ? 0x16221e : 0xe2e8df
    );
    newGrid.position.y = 0.005;
    el.scene.add(newGrid);
    el.gridHelper = newGrid;
  }, [isDark]);

  // Main Three.js Scene Setup (Mounts once, never destroyed on camera/theme change)
  useEffect(() => {
    let isMounted = true;
    const container = mountRef.current;
    if (!container) return;

    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const initialIsDark = document.documentElement.classList.contains('dark');

    // 1. Scene, Camera, Renderer
    const scene = new THREE.Scene();
    const bgColor = initialIsDark ? 0x0b100f : 0xf4f5ef;
    const fogColor = initialIsDark ? 0x0b100f : 0xf4f5ef;
    scene.background = new THREE.Color(bgColor);
    scene.fog = new THREE.FogExp2(fogColor, initialIsDark ? 0.022 : 0.020);

    const width = container.clientWidth;
    const height = container.clientHeight || 520;

    const camera = new THREE.PerspectiveCamera(42, width / height, 0.1, 150);
    camera.position.set(18, 14, 20);
    camera.lookAt(0, 1.5, 0);

    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
    } catch {
      void Promise.resolve().then(() => {
        if (isMounted) {
          setWebglError(true);
        }
      });
      return () => {
        isMounted = false;
      };
    }

    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = initialIsDark ? 1.15 : 1.05;
    container.appendChild(renderer.domElement);

    // 2. Lighting System
    const ambientLight = new THREE.AmbientLight(initialIsDark ? 0x7a8a80 : 0xd8ded6, initialIsDark ? 0.85 : 0.95);
    scene.add(ambientLight);

    const mainLight = new THREE.DirectionalLight(initialIsDark ? 0xc6f36b : 0xffffff, initialIsDark ? 1.4 : 1.7);
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

    const telemetryLight = new THREE.PointLight(initialIsDark ? 0x6cd8c0 : 0x147c68, initialIsDark ? 2.0 : 1.6, 35);
    telemetryLight.position.set(-8, 9, -6);
    scene.add(telemetryLight);

    // 3. Concrete Floor with Zone Markings & Hazard Guides
    const floorGeo = new THREE.PlaneGeometry(42, 34);
    const floorMat = new THREE.MeshStandardMaterial({
      color: initialIsDark ? 0x111a17 : 0xe9eee6,
      roughness: initialIsDark ? 0.85 : 0.78,
      metalness: 0.15,
    });
    const floor = new THREE.Mesh(floorGeo, floorMat);
    floor.rotation.x = -Math.PI / 2;
    floor.receiveShadow = true;
    scene.add(floor);

    // Grid lines for precision spatial alignment
    const gridHelper = new THREE.GridHelper(
      36,
      36,
      initialIsDark ? 0x26362e : 0xd9e1d7,
      initialIsDark ? 0x16221e : 0xe2e8df
    );
    gridHelper.position.y = 0.005;
    scene.add(gridHelper);

    // 4. Industrial Heavy-Duty Storage Racks
    const racksGroup = new THREE.Group();
    scene.add(racksGroup);

    const uprightGeo = new THREE.BoxGeometry(0.14, 5.2, 0.14);
    const uprightMat = new THREE.MeshStandardMaterial({
      color: initialIsDark ? 0x26362e : 0x657369,
      metalness: 0.75,
      roughness: 0.3,
    });

    const beamGeo = new THREE.BoxGeometry(4.2, 0.1, 0.9);
    const beamMat = new THREE.MeshStandardMaterial({
      color: 0xb45309, // Industrial Safety Amber
      metalness: 0.6,
      roughness: 0.35,
    });

    // Color-coded cargo pallets
    const palletWoodGeo = new THREE.BoxGeometry(1.0, 0.12, 0.8);
    const palletWoodMat = new THREE.MeshStandardMaterial({ color: 0x8a6f47, roughness: 0.9 });

    const boxGeo = new THREE.BoxGeometry(0.9, 0.65, 0.72);
    const availableMat = new THREE.MeshStandardMaterial({
      color: initialIsDark ? 0xc6f36b : 0x456b1f,
      roughness: 0.25,
      metalness: 0.2,
    });
    const reservedMat = new THREE.MeshStandardMaterial({
      color: 0xf59e0b,
      roughness: 0.3,
      metalness: 0.2,
    });
    const transferMat = new THREE.MeshStandardMaterial({
      color: initialIsDark ? 0x6cd8c0 : 0x147c68,
      roughness: 0.25,
      metalness: 0.2,
    });
    const discrepancyMat = new THREE.MeshStandardMaterial({
      color: 0xf43f5e,
      roughness: 0.3,
      metalness: 0.2,
    });

    const statusMaterials = [availableMat, reservedMat, transferMat, discrepancyMat];
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
                  status: ['AVAILABLE', 'RESERVED_ACTIVE', 'DUAL_LEDGER_TRANSFER', 'AUDIT_FLAGGED'][matIdx],
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
      color: initialIsDark ? 0x16221e : 0xdde4da,
      roughness: 0.6,
      metalness: 0.3,
    });
    const dock = new THREE.Mesh(dockGeo, dockMat);
    dock.position.set(11, 0.2, -7.5);
    dock.receiveShadow = true;
    scene.add(dock);

    // Inbound pallets on dock
    for (let i = -1; i <= 1; i++) {
      const p = new THREE.Mesh(boxGeo, availableMat);
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

    // 6. Automated Guided Vehicle (AGV) with Industrial Wheel Casters & Forks
    const agvGroup = new THREE.Group();
    agvGroup.position.set(0, 0.25, 0);

    const agvBodyGeo = new THREE.BoxGeometry(1.8, 0.35, 1.1);
    const agvBodyMat = new THREE.MeshStandardMaterial({
      color: initialIsDark ? 0x1b2922 : 0x26362e,
      metalness: 0.85,
      roughness: 0.2,
    });
    const agvBody = new THREE.Mesh(agvBodyGeo, agvBodyMat);
    agvBody.castShadow = true;
    agvGroup.add(agvBody);

    // AGV Wheel Casters
    const wheelGeo = new THREE.CylinderGeometry(0.12, 0.12, 0.1, 16);
    const wheelMat = new THREE.MeshStandardMaterial({ color: 0x18231d, roughness: 0.7 });
    [[-0.6, -0.5], [0.6, -0.5], [-0.6, 0.5], [0.6, 0.5]].forEach(([wx, wz]) => {
      const wheel = new THREE.Mesh(wheelGeo, wheelMat);
      wheel.rotation.z = Math.PI / 2;
      wheel.position.set(wx, -0.12, wz);
      agvGroup.add(wheel);
    });

    // AGV Status Warning Beacon
    const beaconGeo = new THREE.CylinderGeometry(0.08, 0.08, 0.2, 16);
    const beaconMat = new THREE.MeshStandardMaterial({
      color: 0xc6f36b,
      emissive: 0xc6f36b,
      emissiveIntensity: 0.8,
    });
    const beacon = new THREE.Mesh(beaconGeo, beaconMat);
    beacon.position.set(0.7, 0.3, 0.4);
    agvGroup.add(beacon);

    // Pallet transported by AGV with wooden base
    const agvWood = new THREE.Mesh(palletWoodGeo, palletWoodMat);
    agvWood.position.set(0, 0.24, 0);
    agvGroup.add(agvWood);

    const agvCargo = new THREE.Mesh(boxGeo, transferMat);
    agvCargo.position.set(0, 0.62, 0);
    agvCargo.castShadow = true;
    agvCargo.userData = {
      bay: 'AGV-UNIT-01 (ACTIVE RELOCATION)',
      sku: 'SKU-TRANS-904',
      product: 'Atomic Transfer Relocation (Simulated)',
      onHand: 50,
      reserved: 0,
      available: 50,
      zone: 'Autonomous Guided Movement (Immediate)',
      status: 'ATOMIC DUAL-LEDGER (OUT: -50 / IN: +50)',
    };
    interactivePallets.push(agvCargo);
    agvGroup.add(agvCargo);

    scene.add(agvGroup);
    agvRef.current = agvGroup;

    // Save references to scene elements for instantaneous live theme updates
    sceneElementsRef.current = {
      scene,
      renderer,
      ambientLight,
      mainLight,
      telemetryLight,
      floorMat,
      dockMat,
      uprightMat,
      agvBodyMat,
      availableMat,
      transferMat,
      gridHelper,
    };

    // 7. Raycasting for Pallet Hover / Click / Touch Interaction
    const raycaster = new THREE.Raycaster();
    const mouse = new THREE.Vector2();

    const updatePointer = (clientX: number, clientY: number) => {
      const rect = renderer.domElement.getBoundingClientRect();
      mouse.x = ((clientX - rect.left) / rect.width) * 2 - 1;
      mouse.y = -((clientY - rect.top) / rect.height) * 2 + 1;

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

    const handlePointerMove = (e: MouseEvent) => {
      updatePointer(e.clientX, e.clientY);
    };

    const handleTouch = (e: TouchEvent) => {
      if (e.touches && e.touches.length > 0) {
        updatePointer(e.touches[0].clientX, e.touches[0].clientY);
      }
    };

    container.addEventListener('mousemove', handlePointerMove);
    container.addEventListener('touchstart', handleTouch, { passive: true });
    container.addEventListener('touchmove', handleTouch, { passive: true });

    // 8. Visibility & Intersection Observers (Cease GPU consumption when off-screen)
    let isVisible = true;
    const observer = new IntersectionObserver(
      ([entry]) => {
        isVisible = entry.isIntersecting && document.visibilityState === 'visible';
      },
      { threshold: 0.05 }
    );
    observer.observe(container);

    const handleVisibilityChange = () => {
      isVisible = document.visibilityState === 'visible';
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    // 9. Animation & Camera Lerp Loop
    let animationFrameId: number;
    const clock = new THREE.Clock();

    const animate = () => {
      animationFrameId = requestAnimationFrame(animate);
      if (!isVisible) return; // Do not consume GPU when off-screen or tab is backgrounded

      const elapsed = clock.getElapsedTime();

      // Smooth camera interpolation towards active target
      const lerpFactor = prefersReducedMotion ? 1.0 : 0.045;
      camera.position.lerp(cameraTargetRef.current, lerpFactor);

      // Camera look-at target smooth lerp
      camera.lookAt(lookAtTargetRef.current);

      if (!prefersReducedMotion) {
        // AGV Smooth Continuous Waypoint Navigation & Heading
        if (agvRef.current) {
          const speed = 0.5;
          const t = elapsed * speed;
          const px = Math.sin(t) * 7.5;
          const pz = Math.sin(t * 2) * 1.5;
          const vx = Math.cos(t) * 7.5 * speed;
          const vz = Math.cos(t * 2) * 3.0 * speed;
          const targetHeading = Math.atan2(vz, vx);

          agvRef.current.position.set(px, 0.25, pz);
          agvRef.current.rotation.y = targetHeading;
        }

        // Gentle ambient orbit when in isometric mode
        if (activePresetRef.current === 'isometric') {
          const wobble = Math.sin(elapsed * 0.15) * 0.8;
          camera.position.x = 18 + wobble;
        }
      }

      renderer.render(scene, camera);
    };

    animate();

    // 10. Resize Observer
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
      isMounted = false;
      window.removeEventListener('resize', handleResize);
      container.removeEventListener('mousemove', handlePointerMove);
      container.removeEventListener('touchstart', handleTouch);
      container.removeEventListener('touchmove', handleTouch);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      observer.disconnect();
      cancelAnimationFrame(animationFrameId);
      document.body.style.cursor = 'default';
      sceneElementsRef.current = null;
      if (container && renderer.domElement) {
        container.removeChild(renderer.domElement);
      }
      renderer.dispose();
    };
  }, []); // Mounts once and handles camera presets and themes reactively

  return (
    <div className={`relative w-full rounded-2xl overflow-hidden border border-[#D9E1D7] dark:border-[#26362E] bg-[#F4F5EF] dark:bg-[#0B100F] shadow-xl ${className}`}>
      {/* 3D WebGL Canvas Viewport */}
      <div ref={mountRef} className="h-[480px] sm:h-[560px] w-full relative touch-none" />

      {/* WebGL Failure Fallback */}
      {webglError && (
        <div className="absolute inset-0 flex flex-col items-center justify-center p-6 text-center bg-[#F4F5EF] dark:bg-[#0B100F] z-30">
          <Boxes className="w-12 h-12 text-[#456B1F] dark:text-[#C6F36B] mb-3" />
          <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA]">3D Hardware Acceleration Unavailable</h3>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] max-w-md mt-1">
            WebGL acceleration is disabled or unsupported in this browser environment. The interactive operational controls and lifecycle storytelling steps remain fully operational below.
          </p>
        </div>
      )}

      {/* Top Floating Spatial HUD & Camera Presets */}
      <div className="absolute top-4 left-4 right-4 flex flex-wrap items-center justify-between gap-3 pointer-events-none">
        {/* Status Badge */}
        <div className="pointer-events-auto inline-flex flex-wrap items-center gap-2 px-3 py-1.5 rounded-xl bg-white/90 dark:bg-[#111A17]/90 backdrop-blur-md border border-[#D9E1D7] dark:border-[#26362E] text-xs font-mono shadow-md">
          <span className="w-2 h-2 rounded-full bg-[#456B1F] dark:bg-[#C6F36B] animate-pulse" />
          <span className="font-semibold text-[#18231D] dark:text-[#EEF3EA]">SPATIAL WAREHOUSE TWIN</span>
          <span className="text-[#8A988E] dark:text-[#6C7D71]">|</span>
          <span className="text-[#456B1F] dark:text-[#C6F36B] font-bold">144 ACTIVE BAYS</span>
          <span className="text-[#8A988E] dark:text-[#6C7D71]">|</span>
          <span className="text-amber-700 dark:text-amber-400 font-bold uppercase text-[10px] bg-amber-500/10 px-1.5 py-0.5 rounded border border-amber-500/30">
            Simulated Demonstration · Illustrative Data
          </span>
        </div>

        {/* Camera Preset Switches */}
        <div
          role="group"
          aria-label="3D Warehouse Camera Perspectives"
          className="pointer-events-auto inline-flex items-center gap-1 p-1 rounded-xl bg-white/90 dark:bg-[#111A17]/90 backdrop-blur-md border border-[#D9E1D7] dark:border-[#26362E] shadow-md text-xs"
        >
          <button
            onClick={() => setPreset('isometric')}
            aria-pressed={activePreset === 'isometric'}
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
            aria-pressed={activePreset === 'topDown'}
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
            aria-pressed={activePreset === 'aisle'}
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
            aria-pressed={activePreset === 'dock'}
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
            <span className="text-[9px] font-mono font-bold px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/30">
              SIMULATED PALLET
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
              aria-label={isAutoPlaying ? 'Pause lifecycle walkthrough' : 'Play lifecycle walkthrough'}
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

        {/* 6 Step Navigation Selector — Un-truncated titles & subtitles */}
        <div
          role="tablist"
          aria-label="Lifecycle Stages"
          className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2"
        >
          {storySteps.map((step) => {
            const isActive = step.id === activeStep;
            return (
              <button
                key={step.id}
                role="tab"
                aria-selected={isActive}
                aria-label={`Step ${step.id}: ${step.title.split('. ')[1]}`}
                onClick={() => handleStepSelect(step.id)}
                className={`flex flex-col text-left p-3 rounded-xl border transition-all cursor-pointer min-h-[92px] justify-between ${
                  isActive
                    ? 'bg-white dark:bg-[#1B2922] border-[#456B1F] dark:border-[#C6F36B] shadow-xs ring-1 ring-[#456B1F]/30 dark:ring-[#C6F36B]/30'
                    : 'bg-[#F9FAF7] dark:bg-[#111A17] border-[#D9E1D7] dark:border-[#26362E] hover:border-[#657369]/40 dark:hover:border-[#9AA99E]/40'
                }`}
              >
                <div className="flex items-center justify-between w-full mb-1">
                  <span className="shrink-0">{step.icon}</span>
                  <span className="text-[10px] font-mono font-bold text-[#8A988E] dark:text-[#6C7D71]">0{step.id}</span>
                </div>
                <div className="space-y-0.5 flex-1 flex flex-col justify-center">
                  <span className={`text-xs font-bold leading-tight ${isActive ? 'text-[#456B1F] dark:text-[#C6F36B]' : 'text-[#18231D] dark:text-[#EEF3EA]'}`}>
                    {step.title.split('. ')[1]}
                  </span>
                  <span className="text-[10px] text-[#657369] dark:text-[#9AA99E] leading-tight line-clamp-2">
                    {step.subtitle}
                  </span>
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};
