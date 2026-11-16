import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Modal } from '../../components/common/Modal';
import { ThemeSelector } from '../../components/common/ThemeSelector';
import {
  ShieldCheck,
  ArrowRight,
  AlertCircle,
  Sparkles,
  Eye,
  EyeOff,
  HelpCircle,
  CheckCircle2,
} from 'lucide-react';

export const LoginPage: React.FC = () => {
  const navigate = useNavigate();
  const { login } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isAccessModalOpen, setIsAccessModalOpen] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) {
      setErrorMessage('Please provide both email and password.');
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

  const fillAdminCredentials = () => {
    setEmail('admin@stockguard.dev');
    setPassword('replace-with-secure-admin-password');
    setErrorMessage(null);
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
      <Link to="/" className="flex items-center gap-3 mb-8 group">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-500 to-indigo-600 flex items-center justify-center text-slate-950 font-bold shadow-lg shadow-cyan-500/20 group-hover:shadow-[0_0_25px_rgba(6,182,212,0.6)] transition-all">
          <ShieldCheck className="w-6 h-6 text-slate-950 stroke-[2.5]" />
        </div>
        <div className="flex flex-col text-left">
          <div className="flex items-center gap-2">
            <span className="font-bold text-base tracking-wider text-slate-900 dark:text-slate-100">
              STOCKGUARD
            </span>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-cyan-500/10 text-cyan-700 dark:text-cyan-400 border border-cyan-500/30">
              CONSOLE
            </span>
          </div>
          <span className="text-xs text-slate-500 dark:text-slate-400">Operations Sign-In</span>
        </div>
      </Link>

      {/* Card */}
      <div className="w-full max-w-md bg-white dark:bg-[#0f172a] border border-slate-200 dark:border-slate-800 rounded-2xl p-8 shadow-xl dark:shadow-2xl space-y-6">
        <div className="space-y-1">
          <h2 className="text-xl font-bold text-slate-900 dark:text-white tracking-tight">
            Sign in to console
          </h2>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Access tenant inventory balances, reconciliation tools, and audit logs.
          </p>
        </div>

        {errorMessage && (
          <div className="p-3.5 rounded-xl bg-rose-50 dark:bg-rose-500/10 border border-rose-300 dark:border-rose-500/30 text-rose-700 dark:text-rose-300 text-xs flex items-start gap-2.5">
            <AlertCircle className="w-4 h-4 text-rose-500 dark:text-rose-400 shrink-0 mt-0.5" />
            <span>{errorMessage}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            label="Email Address"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="admin@stockguard.dev"
            required
            autoComplete="email"
          />

          <Input
            label="Password"
            type={showPassword ? 'text' : 'password'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••••••"
            required
            autoComplete="current-password"
            rightElement={
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="hover:text-slate-700 dark:hover:text-slate-300 transition-colors p-1"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            }
          />

          <Button
            type="submit"
            className="w-full"
            isLoading={isLoading}
            icon={<ArrowRight className="w-4 h-4" />}
          >
            Authenticate Session
          </Button>
        </form>

        {/* Demo Fast-fill & Account Guidance */}
        <div className="pt-4 border-t border-slate-200 dark:border-slate-800 space-y-3">
          <div className="flex items-center justify-between text-[11px] text-slate-500 dark:text-slate-400 font-mono">
            <span className="flex items-center gap-1">
              <Sparkles className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" /> Development Credential:
            </span>
          </div>

          <button
            type="button"
            onClick={fillAdminCredentials}
            className="w-full px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-cyan-500/40 text-slate-700 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors cursor-pointer text-left flex items-center justify-between"
          >
            <div>
              <div className="font-semibold text-xs flex items-center gap-1.5">
                Primary Administrator
                <span className="text-[10px] px-1 py-0.2 bg-emerald-100 dark:bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 rounded">
                  Active
                </span>
              </div>
              <div className="text-[11px] text-slate-500 font-mono mt-0.5">admin@stockguard.dev</div>
            </div>
            <span className="text-[11px] text-cyan-600 dark:text-cyan-400 font-medium">Auto-Fill &rarr;</span>
          </button>

          <div className="flex items-center justify-center pt-2">
            <button
              type="button"
              onClick={() => setIsAccessModalOpen(true)}
              className="text-xs text-slate-500 dark:text-slate-400 hover:text-cyan-600 dark:hover:text-cyan-400 transition-colors flex items-center gap-1"
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
            <ul className="list-disc list-inside space-y-1 text-slate-600 dark:text-slate-400">
              <li>
                <strong>Team Invitation:</strong> An existing Organization Administrator can invite your email via the Team Management console.
              </li>
              <li>
                <strong>Initial Tenant Setup:</strong> The platform administrator bootstraps initial tenant organizations using secure environment credentials.
              </li>
              <li>
                <strong>Local Development:</strong> Use the documented development bootstrap account (<code className="text-cyan-600 dark:text-cyan-400 font-mono">admin@stockguard.dev</code> / <code className="text-cyan-600 dark:text-cyan-400 font-mono">replace-with-secure-admin-password</code>).
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
