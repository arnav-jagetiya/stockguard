import React, { useState, useEffect } from 'react';
import { useNavigate, Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { getDemoCredentialsApi } from '../../api/auth';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { ThemeTray } from '../../components/common/ThemeTray';
import {
  ShieldCheck,
  Lock,
  Mail,
  AlertCircle,
  Eye,
  EyeOff,
  Sparkles,
  ArrowRight,
  UserPlus,
} from 'lucide-react';

const SAVED_EMAIL_KEY = 'stockguard_saved_email';

export const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [rememberEmail, setRememberEmail] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isDemoLoading, setIsDemoLoading] = useState(false);
  const [isDemoVisitorLoading, setIsDemoVisitorLoading] = useState(false);

  const isDev = import.meta.env.DEV;

  const { login, loginDemoVisitor } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const handleDemoVisitorLogin = async () => {
    setErrorMessage(null);
    setIsDemoVisitorLoading(true);
    try {
      await loginDemoVisitor();
      navigate('/app/dashboard');
    } catch (err: unknown) {
      if (err instanceof Error) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Failed to establish public demo session.');
      }
    } finally {
      setIsDemoVisitorLoading(false);
    }
  };

  // Auto-login if demo=true query parameter is present or load saved email
  useEffect(() => {
    if (searchParams.get('demo') === 'true') {
      handleDemoVisitorLogin();
      return;
    }

    const saved = localStorage.getItem(SAVED_EMAIL_KEY);
    if (saved) {
      setEmail(saved);
      setRememberEmail(true);
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password) {
      setErrorMessage('Please enter both email and password.');
      return;
    }

    setErrorMessage(null);
    setIsLoading(true);

    try {
      if (rememberEmail) {
        localStorage.setItem(SAVED_EMAIL_KEY, email.trim());
      } else {
        localStorage.removeItem(SAVED_EMAIL_KEY);
      }

      await login(email.trim(), password);
      navigate('/app/dashboard');
    } catch (err: unknown) {
      if (err instanceof Error) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Authentication failed. Please check your credentials and retry.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleFetchDemoCredentials = async () => {
    setIsDemoLoading(true);
    setErrorMessage(null);
    try {
      const demo = await getDemoCredentialsApi();
      if (demo && demo.email) {
        setEmail(demo.email);
        if (demo.password) {
          setPassword(demo.password);
        }
      }
    } catch {
      setErrorMessage('Development demo credentials are only available when the backend dev profile is active.');
    } finally {
      setIsDemoLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#F4F5EF] dark:bg-[#0B100F] flex flex-col justify-center items-center px-4 py-12 relative overflow-hidden transition-colors">
      {/* Top Controls: Return to Home & Theme Tray */}
      <div className="absolute top-6 left-6 right-6 flex items-center justify-between z-20">
        <Link
          to="/"
          className="text-xs font-medium text-[#657369] hover:text-[#18231D] dark:text-[#9AA99E] dark:hover:text-[#EEF3EA] transition-colors flex items-center gap-1.5"
        >
          &larr; Back to Platform Overview
        </Link>
        <ThemeTray />
      </div>

      {/* Background Radial Glow */}
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[550px] h-[550px] bg-gradient-to-tr from-[#C6F36B]/10 via-[#147C68]/5 to-transparent rounded-full blur-3xl pointer-events-none -z-10" />

      {/* Brand Header */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center mb-6">
        <div className="inline-flex items-center justify-center p-3 rounded-2xl bg-[#456B1F] dark:bg-[#C6F36B] shadow-xl shadow-[#456B1F]/20 mb-3 ring-4 ring-[#456B1F]/10 dark:ring-[#C6F36B]/20">
          <ShieldCheck className="w-8 h-8 text-white dark:text-[#0B100F]" />
        </div>
        <h2 className="text-2xl font-extrabold tracking-tight text-[#18231D] dark:text-[#EEF3EA]">
          Sign In to STOCKGUARD
        </h2>
        <p className="mt-1.5 text-xs text-[#657369] dark:text-[#9AA99E] max-w-xs mx-auto">
          High-concurrency inventory integrity & double-entry ledger console.
        </p>
      </div>

      {/* Authentication Card */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white/95 dark:bg-[#111A17]/95 backdrop-blur-xl py-8 px-6 sm:px-9 shadow-2xl rounded-2xl border border-[#D9E1D7] dark:border-[#26362E] space-y-5">
          {errorMessage && (
            <div
              data-testid="login-error"
              className="p-3.5 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-start gap-2.5 text-xs text-rose-600 dark:text-rose-400 animate-in fade-in"
            >
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5 text-rose-500" />
              <span>{errorMessage}</span>
            </div>
          )}

          {/* Public Demonstration Card */}
          <div className="p-4 rounded-xl bg-gradient-to-br from-[#456B1F]/10 via-[#147C68]/05 to-[#456B1F]/10 dark:from-[#C6F36B]/15 dark:via-[#6CD8C0]/08 dark:to-[#C6F36B]/05 border border-[#456B1F]/30 dark:border-[#C6F36B]/30 space-y-2.5">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-[#18231D] dark:text-[#EEF3EA] flex items-center gap-1.5">
                <Sparkles className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />
                Public Demonstration
              </span>
              <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-[#456B1F]/15 dark:bg-[#C6F36B]/20 text-[#456B1F] dark:text-[#C6F36B] font-bold border border-[#456B1F]/20 dark:border-[#C6F36B]/30">
                READ-ONLY
              </span>
            </div>
            <p className="text-[11px] text-[#657369] dark:text-[#9AA99E] leading-relaxed">
              Explore Acme Industrial Supply Pvt. Ltd. with real inventory balances, 3 facilities, active orders, and immutable audit logs. No credentials required.
            </p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="w-full border-[#456B1F]/40 dark:border-[#C6F36B]/40 text-[#456B1F] dark:text-[#C6F36B] hover:bg-[#E9EEE6] dark:hover:bg-[#C6F36B]/15 font-semibold flex items-center justify-center gap-2 cursor-pointer shadow-xs"
              onClick={handleDemoVisitorLogin}
              isLoading={isDemoVisitorLoading}
            >
              <span>Explore as Demo Visitor</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </Button>
          </div>

          <div className="relative flex items-center justify-center">
            <div className="border-t border-[#D9E1D7] dark:border-[#26362E] w-full" />
            <span className="bg-white dark:bg-[#111A17] px-2 text-[10px] text-[#8A988E] dark:text-[#6C7D71] font-mono uppercase tracking-wider shrink-0">
              Or sign in with account
            </span>
            <div className="border-t border-[#D9E1D7] dark:border-[#26362E] w-full" />
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <Input
                label="Work Email Address"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@company.com"
                icon={<Mail className="w-4 h-4 text-[#8A988E]" />}
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
                icon={<Lock className="w-4 h-4 text-[#8A988E]" />}
                autoComplete="current-password"
                rightElement={
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] focus:outline-none transition-colors cursor-pointer"
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

            {/* Remember Email Checkbox */}
            <div className="flex items-center justify-between text-xs">
              <label className="flex items-center gap-2 cursor-pointer text-[#657369] dark:text-[#9AA99E] select-none">
                <input
                  type="checkbox"
                  checked={rememberEmail}
                  onChange={(e) => setRememberEmail(e.target.checked)}
                  className="rounded border-[#D9E1D7] dark:border-[#26362E] text-[#456B1F] dark:text-[#C6F36B] focus:ring-[#456B1F]/30 w-3.5 h-3.5"
                />
                <span>Remember work email</span>
              </label>

              {isDev && (
                <button
                  type="button"
                  onClick={handleFetchDemoCredentials}
                  disabled={isDemoLoading}
                  className="text-[11px] font-medium text-[#147C68] dark:text-[#6CD8C0] hover:underline flex items-center gap-1 cursor-pointer disabled:opacity-50"
                  title="Populate development bootstrap credentials via dev API"
                >
                  <Sparkles className="w-3 h-3" /> {isDemoLoading ? 'Loading Demo...' : 'Fill Demo Admin'}
                </button>
              )}
            </div>

            <Button
              type="submit"
              variant="primary"
              className="w-full mt-2 cursor-pointer flex items-center justify-center gap-2"
              isLoading={isLoading}
            >
              <span>Sign In to Console</span>
              <ArrowRight className="w-4 h-4" />
            </Button>
          </form>

          {/* Create Account Link */}
          <div className="pt-4 border-t border-[#D9E1D7] dark:border-[#26362E] flex flex-col items-center gap-3">
            <div className="text-xs text-[#657369] dark:text-[#9AA99E] flex items-center justify-center gap-1.5">
              <span>Need a new organization tenant?</span>
              <Link
                to="/register"
                className="font-semibold text-[#456B1F] dark:text-[#C6F36B] hover:underline flex items-center gap-1 cursor-pointer"
              >
                <UserPlus className="w-3.5 h-3.5" />
                <span>Create Account</span>
              </Link>
            </div>
          </div>
        </div>
      </div>

      <p className="mt-6 text-[11px] text-[#657369] dark:text-[#9AA99E] text-center font-mono">
        Secured with BCrypt · HttpOnly Refresh Cookies · CSRF Validation · Tenant Isolation
      </p>
    </div>
  );
};
