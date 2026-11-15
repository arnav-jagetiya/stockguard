import React from 'react';
import { Navbar } from '../../components/layout/Navbar';
import { HeroSection } from './HeroSection';
import { CapabilitiesSection } from './CapabilitiesSection';
import { InteractiveDemoSection } from './InteractiveDemoSection';
import { IntegritySection } from './IntegritySection';
import { ArchitectureSection } from './ArchitectureSection';
import { FooterSection } from './FooterSection';

export const MarketingPage: React.FC = () => {
  return (
    <div className="min-h-screen bg-[#080c14] text-slate-100 flex flex-col selection:bg-cyan-500/20 selection:text-cyan-300">
      <Navbar />
      <main className="flex-1">
        <HeroSection />
        <CapabilitiesSection />
        <InteractiveDemoSection />
        <IntegritySection />
        <ArchitectureSection />
      </main>
      <FooterSection />
    </div>
  );
};
