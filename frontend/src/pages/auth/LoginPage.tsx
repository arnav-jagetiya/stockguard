import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Modal } from '../../components/common/Modal';
import { ThemeSelector } from '../../components/common/ThemeSelector';
import {
  ShieldCheck,
  Lock,
  Mail,
  AlertCircle,
  Eye,
  EyeOff,
  HelpCircle,
  CheckCircle2,
} from 'lucide-react';

export const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isAccessModalOpen, setIsAccessModalOpen] = useState(false);

  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password) {
      setErrorMessage('Please enter both email and password.');
      return;
    }

    setErrorMessage(null);
    setIsLoading(true);
    try {
      await login(email, password);
      navigate('/app/dashboard');
    } catch (err: unknown) {
      if (err instanceof Error) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Authentication failed. Please check your credentials.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-[#080c14] flex flex-col justify-center items-center px-4 py-12 relative overflow-hidden bg-grid-pattern transition-colors">
      {/* Top Controls: Return to Home & Theme Selector */}
      <div className="absolute top-6 left-6 right-6 flex items-center justify-between">
        <Link
          to="/"
          className="text-xs font-medium text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-200 transition-colors flex items-center gap-1.5"
        >
          &larr; Back to Platform Overview
        </Link>
        <ThemeSelector />
      </div>

      {/* Background Radial Glow */}
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[550px] h-[550px] bg-gradient-to-tr from-cyan-500/10 via-indigo-500/5 to-transparent rounded-full blur-3xl pointer-events-none -z-10" />

      {/* Brand Header */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center mb-8">
        <div className="inline-flex items-center justify-center p-3 rounded-2xl bg-gradient-to-br from-cyan-500 to-indigo-600 shadow-xl shadow-cyan-500/20 mb-4 ring-4 ring-cyan-500/10">
          <ShieldCheck className="w-8 h-8 text-white" />
        </div>
        <h2 className="text-2xl font-extrabold tracking-tight text-slate-900 dark:text-white">
          STOCKGUARD Console
        </h2>
        <p className="mt-2 text-xs text-slate-500 dark:text-slate-400 max-w-xs mx-auto">
          High-concurrency inventory integrity & double-entry ledger reconciliation.
        </p>
      </div>

      {/* Authentication Card */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white/80 dark:bg-[#0f172a]/80 backdrop-blur-xl py-8 px-6 sm:px-10 shadow-2xl rounded-2xl border border-slate-200/80 dark:border-slate-800/80 space-y-6">
          {errorMessage && (
            <div className="p-3.5 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-start gap-2.5 text-xs text-rose-600 dark:text-rose-400 animate-in fade-in">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5 text-rose-500" />
              <span>{errorMessage}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <Input
                label="Email Address"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@company.com"
                icon={<Mail className="w-4 h-4 text-slate-400" />}
                autoComplete="email"
              />
            </div>

            <div>
              <Input
                label="Account Password"
                type={showPassword ? 'text' : 'password'}
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••••••"
                icon={<Lock className="w-4 h-4 text-slate-400" />}
                autoComplete="current-password"
                rightElement={
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 focus:outline-none transition-colors"
                    title={showPassword ? 'Hide password' : 'Show password'}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? (
                      <EyeOff className="w-4 h-4" />
                    ) : (
                      <Eye className="w-4 h-4" />
                    )}
                  </button>
                }
              />
            </div>

            <Button
              type="submit"
              variant="primary"
              className="w-full mt-2"
              isLoading={isLoading}
            >
              Sign In to Console
            </Button>
          </form>

          {/* Account Guidance */}
          <div className="pt-4 border-t border-slate-200 dark:border-slate-800 flex items-center justify-center">
            <button
              type="button"
              onClick={() => setIsAccessModalOpen(true)}
              className="text-xs text-slate-500 dark:text-slate-400 hover:text-cyan-600 dark:hover:text-cyan-400 transition-colors flex items-center gap-1 cursor-pointer"
            >
              <HelpCircle className="w-3.5 h-3.5" /> Need an account or access?
            </button>
          </div>
        </div>
      </div>

      <p className="mt-8 text-xs text-slate-500 text-center font-mono">
        Secured with BCrypt · HttpOnly Refresh Cookies · CSRF Validation · Tenant Isolation
      </p>

      {/* Account Access Guidance Modal */}
      <Modal
        isOpen={isAccessModalOpen}
        onClose={() => setIsAccessModalOpen(false)}
        title="Enterprise Account Access"
        description="STOCKGUARD multi-tenant authorization policy"
      >
        <div className="space-y-4 text-xs text-slate-600 dark:text-slate-300 leading-relaxed">
          <p>
            STOCKGUARD is an enterprise inventory and reconciliation platform. For security and regulatory auditability, public self-registration is strictly disabled.
          </p>

          <div className="p-3 rounded-lg bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-2">
            <div className="font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
              <CheckCircle2 className="w-4 h-4 text-cyan-500" /> How to obtain access:
            </div>
            <ul className="list-disc list-inside space-y-1.5 text-slate-600 dark:text-slate-400">
              <li>
                <strong>Administrator Provisioning:</strong> An Organization Administrator can grant your user account access to the organization via the Team & Access Governance console.
              </li>
              <li>
                <strong>Initial Tenant Setup:</strong> The platform administrator bootstraps initial tenant organizations and root identities using secure environment configuration.
              </li>
              <li>
                <strong>Local Development:</strong> Use the locally configured development bootstrap administrator credentials provided during application initialization.
              </li>
            </ul>
          </div>

          <div className="flex justify-end pt-2">
            <Button size="sm" variant="secondary" onClick={() => setIsAccessModalOpen(false)}>
              Got it
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
};
