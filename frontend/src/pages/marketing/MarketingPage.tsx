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
    <div className="min-h-screen bg-[#F4F5EF] dark:bg-[#0B100F] text-[#18231D] dark:text-[#EEF3EA] flex flex-col selection:bg-[#C6F36B]/30 selection:text-[#18231D] dark:selection:text-[#C6F36B] transition-colors">
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
